# 模型容器适配指南（每个模型 = 一个容器）

本平台采用「**每个模型版本 = 一个 Docker 容器镜像**」的架构：模型以镜像 tar 形式存入存储池，训练/评测任务下发到 Agent 后，Agent 下载镜像 tar → `docker load` → 挂载数据集 → `docker run` 执行容器内统一入口脚本，脚本把训练/评测过程的进度、日志、指标、结果以 JSONL 实时回传。

要接入**任意算法库**（ultralytics / mmdetection / huggingface / scikit-learn / 自研代码），只需保证容器内有一个遵循下述契约的入口脚本。本文档给出完整契约与适配步骤。

> 参考实现：`model-containers/demo-ultralytics-yolo/run.py`（ultralytics YOLO，CPU）。

---

## 1. 端到端数据流

```
训练/评测任务（Training/Evaluation Service）
   │  task.command（含 imageName / imageTarRelPath / datasetRelPath / taskMode / hyperparams）
   ▼
Resource Service：用 {tenantId}/ 拼全 key，预签名 镜像tar + 数据集 → run_task 下发
   ▼
Agent（WS run_task）
   ├─ HTTP GET 下载 镜像tar → docker load（镜像已存在则跳过）
   ├─ HTTP GET 下载 数据集 → 落盘 /data/dataset（只读挂载）
   ├─ docker run -v dataset:/data/dataset:ro -v output:/output -e AISYS_*  <镜像>
   │      └─ 容器内入口脚本：读环境变量 → 调算法库 → 按 JSONL 协议输出 → 写 /output
   └─ 逐行解析 JSONL → WS 上报 status/log/metrics/result → 平台入库（实时监控）
```

---

## 2. 容器环境变量契约（Agent 注入）

入口脚本从这些环境变量读取任务参数：

| 变量 | 含义 | 示例 |
|---|---|---|
| `AISYS_TASK` | 任务模式 | `train` / `eval` |
| `AISYS_DATASET_DIR` | 数据集只读挂载点 | `/data/dataset` |
| `AISYS_OUTPUT_DIR` | 输出目录（可写，checkpoints/结果） | `/output` |
| `AISYS_HYPERPARAMS` | 超参 JSON 字符串 | `{"epochs":3,"imgsz":64,"lr":0.01}` |
| `AISYS_DATASET_FORMAT` | 数据集格式 | `yolo` / `coco` / `csv` / `imagenet` ... |
| `AISYS_TASK_ID` | 任务 ID（仅日志用） | `1024` |

> 数据集内容在 `AISYS_DATASET_DIR` 下，按 `AISYS_DATASET_FORMAT` 的目录/文件约定组织（如 YOLO：`data.yaml` + `images/` + `labels/`）。**脚本应兼容“挂载点为空或不符约定”的情况**（demo 里用合成数据兜底），保证容器总能跑起来。

---

## 3. JSONL 输出协议（标准输出，每行一个 JSON，立即 flush）

容器入口脚本把过程信息**每行一个 JSON** 写到 stdout，Agent 逐行解析后实时回传平台：

```jsonc
{"type":"status","status":"running","progress":5}                 // 进度 0-100
{"type":"log","level":"INFO","step":1,"message":"开始第 1/3 epoch"} // 日志
{"type":"metric","step":1,"total":3,"progress":35,"metrics":{"loss":0.42,"mAP50":0.71}} // 指标
{"type":"result","status":"completed","progress":100,"result":{"mAP50":0.83,"weights":"/output/best.pt"}} // 终态：完成
{"type":"result","status":"failed","progress":100,"error":"OOM"}   // 终态：失败（退出码非 0）
```

| type | 用途 | 必填字段 |
|---|---|---|
| `status` | 进度心跳 | `status`,`progress` |
| `log` | 日志行（前端监控页展示） | `level`(INFO/WARN/ERROR),`message` |
| `metric` | 训练/评测指标（绘曲线） | `step`,`metrics`(任意 k:v) |
| `result` | **终态帧，且仅发一次** | `status`(completed/failed)；完成带 `result`，失败带 `error` |

> 非 JSON 行会被当作日志回传（兼容算法库自带的 print）。脚本务必 `flush`，保证实时性。

---

## 4. 适配一个新算法库（4 步）

### 步骤 1：写入口脚本（替换算法调用部分）

复制 `demo-ultralytics-yolo/run.py`，**保持 `emit/emit_*` 协议函数与 `AISYS_*` 环境变量读取不变**，只替换 `train()` / `evaluate()` 里“调用算法库”的代码。最小骨架：

```python
import json, os, sys
def emit(o): sys.stdout.write(json.dumps(o, ensure_ascii=False)+"\n"); sys.stdout.flush()

def train():
    hp = json.loads(os.environ.get("AISYS_HYPERPARAMS","{}"))
    epochs = hp.get("epochs", 3)
    dataset = os.environ["AISYS_DATASET_DIR"]
    out = os.environ["AISYS_OUTPUT_DIR"]
    # === 这里替换成你的算法库调用 ===
    # 例：mmdet / transformers / sklearn ...
    # 加载数据(dataset) → 训练循环 → 每 epoch 调 emit_metric(...)
    for ep in range(1, epochs+1):
        loss = ...  # 你的算法库返回的损失
        emit({"type":"metric","step":ep,"total":epochs,"progress":int(100*ep/epochs),
              "metrics":{"loss":float(loss)}})
        # 保存 checkpoint 到 out
    return {"weights": out+"/best.pt"}

def main():
    task = os.environ.get("AISYS_TASK","train")
    emit({"type":"status","status":"running","progress":1})
    try:
        result = train() if task=="train" else evaluate()
        emit({"type":"result","status":"completed","result":result})
    except Exception as e:
        emit({"type":"result","status":"failed","error":str(e)}); sys.exit(1)
main()
```

### 步骤 2：写 Dockerfile

```dockerfile
FROM python:3.11-slim
# 算法库的系统依赖（如 opencv 需要 libgl1 等）按需安装
RUN pip install --no-cache-dir <你的算法库及依赖>
COPY run.py /opt/aisys/run.py
RUN mkdir -p /data/dataset /output
ENV AISYS_DATASET_DIR=/data/dataset AISYS_OUTPUT_DIR=/output AISYS_TASK=train
ENTRYPOINT ["python3","/opt/aisys/run.py"]
```

> CPU 场景装 CPU 版 torch：`pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu`（torch 与 torchvision 必须同源同版本，否则 NMS 等算子缺失）。

### 步骤 3：构建并打成 tar

```bash
docker build -t myorg/my-model:v1 .
docker save myorg/my-model:v1 -o my-model-v1.tar
```

### 步骤 4：在平台注册为模型版本

1. 「模型管理」→ 新建模型 → 创建版本，**config 里填 `imageName`**（即 `docker run` 用的镜像名，如 `myorg/my-model:v1`）。
2. 上传 `my-model-v1.tar` 到该版本（平台把 tar 存入存储池 `models/{modelId}/{version}/model.bin`）。
3. 版本变 `ready` 后，即可在「训练中心」选该版本发起训练。

---

## 5. 数据集挂载约定（按格式）

Agent 把数据集版本对象下载到 `/data/dataset`。入口脚本按 `AISYS_DATASET_FORMAT` 解析：

| 格式 | 期望布局（挂载点下） |
|---|---|
| `yolo` | `data.yaml` + `images/{train,val}/*.jpg` + `labels/{train,val}/*.txt` |
| `coco` | `annotations.json` + `images/` |
| `csv` | `*.csv`（每行一条样本） |
| `imagenet` | 按类别子文件夹的图像目录 |

> 平台数据集版本默认以 JSONL（内部格式）存储元数据。若挂载点无符合约定的真实图像/标注，**脚本应内置合成数据兜底**（如 demo 的 `prepare_yolo_dataset`），保证链路可演示；真实训练时上传带图像的数据集即可。

---

## 6. 评测模式（`AISYS_TASK=eval`）

同一容器/脚本支持评测：Agent 注入 `AISYS_TASK=eval`，脚本走 `evaluate()` 分支，在测试集上推理、计算指标（mAP / 准确率等），用 `emit_metric` + `emit_result` 回传。评测结果经「评测中心」聚合为 `EvaluationResult` 与排行榜。

---

## 7. 检查清单（适配新库时自查）

- [ ] 入口脚本读 `AISYS_*` 环境变量，**不硬编码**数据集/输出路径。
- [ ] 输出严格遵守 JSONL 协议（`status`/`log`/`metric`/`result`），`result` 终态帧恰好一次。
- [ ] stdout 每行 flush；非 JSON 的算法库 print 不会破坏解析（Agent 兜底当日志）。
- [ ] 失败时发 `result:failed` 并以非 0 退出；超大数据集不一次性读入内存。
- [ ] Dockerfile 装齐算法库系统依赖（opencv/libgl 等）；CPU torch 与 torchvision 同源。
- [ ] checkpoint / 权重 / 结果写 `$AISYS_OUTPUT_DIR`（平台可下载/回传）。
- [ ] 镜像名与模型版本 config 的 `imageName` 一致。
