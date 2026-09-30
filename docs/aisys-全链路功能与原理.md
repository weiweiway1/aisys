# aisys 全链路功能与原理讲解

本文档面向使用与二次开发，系统讲解 aisys 平台"容器式评测 + agent 工作流管理 + 评测报告动态拼接"三大核心能力的全链路原理。所有结论均以代码实现为据，标注 `file:line` 便于追溯。

---

## 0. 平台定位与全景

aisys 是一个多租户 AI 模型评测/训练平台。后端 Spring Cloud 微服务（Java 25 / Spring Boot 4.1 / Spring Cloud 2025.1.2）编排黑盒测试与训练；Go agent 把每个模型作为 Docker 容器运行；Vue 3 前端做监控与报告。全部服务以容器形式跑在 `deploy/docker-compose.yml`。

**服务地图**：

| 服务 | 模块 | 端口 | 职责 |
|---|---|---|---|
| gateway | aisys-gateway | 8080 | Spring Cloud Gateway，`/api/v1/<domain>/**` 路由 |
| auth | aisys-auth | 8001 | 用户/租户/角色，JWT |
| model | aisys-model | 8002 | 模型与模型版本管理（含镜像 tar） |
| dataset | aisys-dataset | 8003 | 数据集与版本管理 |
| training | aisys-training | 8004 | 训练任务生命周期、checkpoint、日志/指标 |
| evaluation | aisys-evaluation | 8005 | 评测/基准、排行榜、LLM 报告 |
| resource | aisys-resource | 8006 | 计算节点调度、**agent WebSocket 端点**、S3 预签名、状态转发 |
| storage | aisys-storage | 8007 | 文件浏览/上传/**下载**代理 SeaweedFS |
| notification | aisys-notification | 8008 | 通知 |
| monitor | aisys-monitor | 8009 | 审计日志 |
| agent | aisys-agent (Go) | — | 跑 Docker 容器执行训练/评测任务；resource 的 WS 客户端 |
| web | aisys-web (Vue 3) | 8081 | 前端 |

**三大能力一句话**：
- **容器式评测/训练**：每个模型 = 一个 Docker 镜像 tar，agent 拉取加载后以标准挂载/环境变量启动，按统一 JSONL 协议产出。
- **agent 工作流管理**：Go agent 经 WebSocket 长连接接收任务、流式回传 stdout、上传产物，平台侧 MQ 转发落库。
- **评测报告动态拼接**：按任务类型分支选 system prompt，从实际结果 JSONB 反推指标清单，多模型/弱项/子任务错误全量喂给 LLM，生成严肃可执行的三档改进建议。

---

## 1. 容器式评测与训练

### 1.1 "每个模型 = 一个容器"契约

这是平台的**集成边界**（权威文档：`docs/model-container-adapter.md`）。一个模型版本以 Docker 镜像 tar 形式存于 SeaweedFS（S3 兼容）；调度时由 resource 预签名下载 URL，agent 拉取 + `docker load` + 以标准方式启动：

```
docker run --rm --name task-<id>
  -v <datasetDir>:/data/dataset[:ro|:rw]
  -v <outputDir>:/output
  -e AISYS_DATASET_DIR=/data/dataset
  -e AISYS_OUTPUT_DIR=/output
  -e AISYS_TASK=<train|eval>
  -e AISYS_HYPERPARAMS=<json>
  [-e TQDM_DISABLE=1]            # 关键：见 §3.4
  <image>
```

容器入口脚本（参考 `models/yoloV3/run.py`）按 JSONL 协议往 stdout 逐行输出四类帧：

| 帧类型 | 用途 | 示例 |
|---|---|---|
| `status` | 进度/状态 | `{"type":"status","status":"running","progress":35}` |
| `log` | 日志 | `{"type":"log","level":"INFO","message":"..."}` |
| `metric` | 每轮指标 | `{"type":"metric","step":1,"metrics":{"loss":0.42}}` |
| `result` | 终态（恰好一次） | `{"type":"result","status":"completed","result":{"weights":"/output/best.pt","metrics":{...}}}` |

**接入新算法库**：复制参考 `run.py`，只替换 `train()`/`evaluate()`，遵守上述协议即可——无需改平台代码。`run.py` 适配新库的检查清单见 `docs/model-container-adapter.md` 第 7 节（其中明确要求"训练/评测过程中每个 epoch emit metric 帧，`step` 必填"）。

### 1.2 沙箱隔离

agent 用 docker 标准机制隔离被测算法：
- **文件系统**：只挂载输入目录（`/data/dataset`）、输出目录（`/output`）、只读模型目录；不允许访问平台控制目录或其他任务数据。
- **网络**：默认关闭外网，仅授权时开放指定地址（避免容器联网下字体/权重卡住——见 §3.4 坑 1）。
- **资源**：CPU/GPU/内存/显存/运行时间均设上限。
- **镜像校验**：已有镜像做签名校验 + 基础漏洞扫描；仅提供压缩包的算法由平台生成临时构建脚本封装。

### 1.3 镜像 tar 管理

模型版本上传：`aisys-model` 支持分片直传 S3（`S3StorageService.createMultipart + presignUploadPart + completeMultipart`），秒传按 checksum 去重。tar 存到 SeaweedFS，`model_version.storage_path` 记相对路径，`config` JSONB 里放 `imageName` 等。

**镜像缓存坑（重要）**：agent 用 `docker image inspect <imageName>` 判断本地是否已有同名镜像，有就跳过下载/加载（`aisys-agent/executor.go:82`）。这是为避免每次任务重复拉 2GB+ tar 的优化，但副作用是——**改动模型容器（run.py/Dockerfile/依赖）重新打包时，必须换新版本号**（如 `yolo:v3.2`，不能复用 `yolo:v3.1`），否则 agent 用本地缓存的旧镜像、新代码不生效。平台模型版本的 `imageName` 也要同步更新。

---

## 2. Agent 工作流管理

agent 是 Go 写的、跑在计算节点上的守护进程，是 platform 与 docker 之间的桥。

### 2.1 注册与鉴权

agent 启动后向 resource 注册（`POST /api/v1/agent/register`，带 enrollment token），拿到 `agentToken`；随后建 WebSocket 长连接到 `resource:8006/ws/v1/agent/connect`（经 gateway 路由）。**鉴权**：WS 首帧携带 `agentToken`，`AgentWebSocketHandler.afterConnectionEstablished` 校验通过前不接收其它消息。

连接断了自动重连（指数退避 + 重注册）。resource 侧维护 agent → 节点映射，心跳保活。

### 2.2 任务接收与执行

agent 收到 `run_task` 命令（`agent.go:159` `case "run_task"`）后，在独立 goroutine 调 `Executor.Run`（`executor.go:58`）。`Run` 按是否有 `ImageTarUrl` 分发：

- **有 ImageTarUrl** → `runModelContainer`（真容器路径，含镜像加载 + 上传块）
- **无** → `runSim`（旧路径内置 CPU 模拟脚本，无上传块——仅回退用）

`runModelContainer` 流程（`executor.go:76`）：

1. **镜像就绪**（L79-114）：`docker image inspect imageName` 命中缓存则跳过；否则 `downloadAndLoadImage` 拉取 tar + `docker load`，用 load 出的实际标签修正 `imageName`（兼容 tar 内标签与 config 不一致）。
2. **工作目录**：`/tmp/aisys-agent/aisys-task-<id>/`，含 `dataset/`、`output/` 子目录。`defer os.RemoveAll(workDir)` 任务结束清理。
3. **数据集准备**（L127-131）：按预签名 URL 下载到 `datasetDir`；zip/tar 自动解压。`resolveDatasetMountDir`（L735）检测三分割格式（train/val/test）按 taskMode 选子目录 rw 挂载，否则整体 ro。
4. **构建 docker run 参数**（L150）：`--rm --name task-<id>` + 标准挂载/环境变量 + `TQDM_DISABLE=1`（关键，见 §3.4）。
5. **执行 + 流式解析**（L185 `runDockerAndStream`）：`docker run`，`bufio.Scanner` 逐行读 stdout，每行先 `cleanLogLine` 清洗（剥 ANSI、取最后 `\r` 段、截断 2000 runes），再 `handleJsonlLine` 按帧类型分发转发。
6. **产物上传**（L187-205）：容器退出后，若 `OutputUploadUrl != ""`，`findBestWeight` 扫描 `/output` 找权重文件，`uploadFile` PUT 到预签名 S3 URL，发第二个 `status` 上报带 `outputPath`。
7. **终态上报**：`reportTerminal`（L342）发 `result` 帧带容器 result map。

### 2.3 stdout 解析与转发

`handleJsonlLine`（`executor.go:364`）按 JSONL 帧 `type` 分发：

| 帧 | agent 行为 |
|---|---|
| `status` | `agent.send(Report{Type:"status", Data:obj})` |
| `log` | `agent.send(Report{Type:"log", Level, Message})`——**不带 Data:obj**（避免长 message 触发 WS 1009，见 §3.4 坑 2） |
| `metric` | `agent.send(Report{Type:"metrics", Metrics:m, Data:obj})`——Data 透传整个 metric 帧（含 `step`/`progress`），resource 侧据此填 `TaskMetricsMessage.step` |
| `result` | 返回 obj，由 `runDockerAndStream` 在容器退出后用 `reportTerminal` 发 |
| 非 JSON 行 | 当 log 转发（兜底） |

`agent.send` 经 WebSocket 发到 resource。**Report 结构**（`aisys-agent/messages.go:51`）与 resource 侧 `WebSocketMessage.java` 字段对齐（`type/taskId/taskType/data/metrics/level/message`）——改任一侧都要保持同步。

### 2.4 关键工程细节（踩过的坑）

- **不分配 TTY**：`docker run` **不带 `-t`**（`executor.go:150`），配 `TQDM_DISABLE=1`。原因：tqdm/ultralytics 检测到 TTY 会用 `\r` 原地刷新进度条，`bufio.Scanner` 只按 `\n` 分割会把所有 `\r`-刷新累积成单行突破 buffer 上限（`ErrTooLong`）→ scanner 静默退出 → 后续 stdout 全丢。`cleanLogLine` 还会取最后一个 `\r` 段（保留最新进度状态）+ 截断 2000 runes。
- **scanner buffer 64MB**（`executor.go:307`）：防御性，万一还有长行。循环后加 `scanner.Err()` 检查（L339），不再静默吞 `ErrTooLong`。
- **WS 文本缓冲 64KB**（`WebSocketConfig.java` `ServletServerContainerFactoryBean`）：默认 8KB 太小，长 log/metric 帧会触发 `close 1009` 断连，状态帧丢失。提到 64KB + log 帧不带 `Data:obj` 双保险。
- **uploadFile 超时 10 分钟**（`executor.go:497`）：`http.DefaultClient` 无超时会永久挂起（SeaweedFS 不可达时），导致 task goroutine 泄漏、第二个 status 上报永不发出。改 `&http.Client{Timeout: 10*time.Minute}`。
- **镜像缓存**：见 §1.3，复用 imageName 会用旧镜像。

---

## 3. 评测全流程（端到端）

### 3.1 任务创建与子任务拆分

用户在评测中心选 N 个模型版本 + 1 个 benchmark 创建评测任务。`EvaluationTaskServiceImpl.create`（`EvaluationTaskServiceImpl.java:90`）：
- 校验 benchmark 存在且 active。
- 父任务 `evaluation_task` 一行（status=pending）。
- **为每个 model_version 创建一个 `evaluation_subtask`**（拆分，支持多模型同台对比）。

### 3.2 启动与命令下发

`start(id)`（L160）：
- 父任务 → running。
- 对每个未完成子任务：`fillContainerSpec`（L431）经 Feign 解析模型版本（→ 镜像 tar relPath + imageName）+ 数据集版本（→ storagePath + format），快速失败（模型版本不可用/缺镜像直接抛错，不让 agent 跑空伪造分数）。
- 构造 `EvaluationCommandMessage`（含 taskId/subtaskId/benchmarkId/modelVersionId/datasetVersionIds/promptTemplate/metricsConfig/evalConfig + 容器描述），经 **Outbox 模式**发到 `task.command` exchange（路由键 `task.command.eval.<id>`），保证"DB 更新 + 消息投递"一致。

### 3.3 调度与 agent 执行

resource 的调度器消费 `task.command.eval.*`：选节点、预签名 S3 上传 URL（`training/<dispatchId>/output/best.pt` 对应评测则是 `evaluation/<subtaskId>/...`）、构造 `RunTaskCommand` 经 WebSocket 发给 agent。agent 按 §2.2 流程执行容器。

### 3.4 结果回传与聚合

容器产出的 JSONL 经 agent → WS → resource `forwardResult`/`forwardStatus`/`forwardMetrics`（`ForwardServiceImpl.java`）→ MQ `task.status`/`task.log`/`task.metrics`（路由键 `task.<type>.evaluation`）。

evaluation 的 `TaskStatusConsumer.onMessage` → `handleSubtaskStatus`（`EvaluationTaskServiceImpl.java:298`）：
- 更新子任务状态。
- 子任务终态时 `writeResultIfAbsent` 写 `evaluation_result`（每模型一条，含 `overall_scores`/`category_scores` JSONB、`sample_count`、`detail_path`），幂等。
- `aggregateParent`（L345）：统计子任务状态，全成功 → `completed`，部分失败 → `completed_with_errors`，全失败 → `failed`。已显式 stop 的不被迟到子任务复活。
- 聚合到 completed/completed_with_errors → **异步触发 `reportService.generateReportAsync`**（L375），不阻塞主流程。

### 3.5 评测报告动态拼接（核心）

这是平台"评测"的最后一公里，也是最需要通用性的环节。实现在 `EvaluationReportServiceImpl.java`。

#### 3.5.1 LLM 配置（.env 形式，改不编译）

`deploy/.env`（gitignored）放 `LLM_URL`/`LLM_API_KEY`/`LLM_MODEL`/`LLM_TIMEOUT_SECONDS`；`docker-compose.yml` evaluation 服务的 `environment:` 用 `${LLM_URL:-默认}` 读取；`application.yml` 的 `${LLM_URL:默认}` 再读容器 env。三层默认兜底。**改 LLM 配置只编辑 `.env` + `docker compose up -d evaluation`（不带 `--build`），秒级生效不编译**。模板见 `deploy/.env.example`。

`callLlm`（`EvaluationReportServiceImpl.java:callLlm`）用 JDK `HttpClient` 发请求，按 URL 是否含 `/api/generate` 自动选 Ollama 原生格式 或 OpenAI 兼容 `/v1/chat/completions`。`temperature=0.3`（严肃报告需稳定），`max_tokens`/`num_predict=8192`（多模型+多章节不截断）。

#### 3.5.2 上下文数据装配（`buildContext`，通用化）

`buildContext(taskId, tenantId)` 读取并组装：

| 数据 | 来源 | 说明 |
|---|---|---|
| 任务名/时间/config | `evaluation_task` | `task.getName()`/`completedAt`/`config` |
| 每个被测模型结果 | `evaluation_result`（循环所有，不只 get(0)） | `overall_scores`/`category_scores` JSONB、`sample_count` |
| 模型元数据 | Feign `ModelClient.getVersionById` | 经 §4.1 改造，**直接返回** `modelName`/`modelDescription`/`taskType`/`framework`（JOIN model 表，不再恒"未知"） |
| 数据集元数据 | Feign `DatasetClient.getVersionById` | 经 §4.2 改造，**直接返回** `datasetName`/`datasetDescription`/`taskType`/`format`/`sampleCount` |
| benchmark | `benchmark` 表 | `name`/`description`/`metrics_config`/`prompt_template`（后两者是现成钩子） |
| 子任务错误 | `EvaluationSubtaskMapper.selectByParentTaskId` | `errorMessage`/`status`，作为弱项分析的失败模式证据 |
| **指标清单 metricKeys** | **数据反推** | 遍历所有 result 的 `overall_scores`+`category_scores` JSONB 顶层 key，去重收集（`collectMetricKeys`）——**不硬编码 mAP/Top-1，容器 emit 什么就收什么** |

关键设计：`metrics_config` 当前为 null（benchmark 创建时未配），指标真正来源是容器 run.py 的输出（自由 JSONB）。所以**指标清单从数据反推**，`metrics_config` 降级为可选标签补充；红线也改为数据驱动（对"显著偏低或相比同类骤降"的指标标注高风险），不依赖配置阈值。

#### 3.5.3 Prompt 拼装（任务类型分支）

- **System Prompt**（`SYSTEM_PROMPT_TEMPLATE` + `chooseSystemPrompt(modelTaskType)`）：通用骨架，按 `model.type`（`object_detection`/`image_classification`/`time_series`/通用）选指标解读举例（`{metricHint}` 替换）。所有分支共有：
  - 严肃风格约束：禁营销化措辞（"惊艳/强大/完美"等）、每结论必引数值、数据不足显式标注"样本量不足，结论待验证"、不臆测。
  - 六章节结构：①评测概况 ②核心指标解读 ③类别级/分组级详细分析 ④**弱项与风险**（链式：问题描述—证据—量化影响—可能原因—改进建议；按"漏检/误检/类别混淆/定位偏差/置信度异常/输出协议异常"分类；数据驱动红线）⑤**改进建议三档**（立即可做/需返工/需重训练，每条绑定指标与量化预期收益）⑥结论与部署建议（红线判定 + 推荐/受限推荐/不推荐）。
  - 若 `benchmark.prompt_template` 非空，append 到 system 末尾作 per-benchmark 风格补充。
  - 修了 3 个 bug：`{taskName}` 占位符在 system 侧不替换（改为运行时 `replace`）、`## 五、` 补前缀、"基"笔误。
- **User Prompt**（`USER_PROMPT_TEMPLATE`）：通用结构，完全不写死指标名。塞入评测基本信息（含模型/数据集任务类型）、metricKeys 清单、模型/数据集描述、超参、metricsConfig（可选）、多模型结果 JSON、子任务错误。

#### 3.5.4 报告存储与回显

生成的 Markdown + 转换的 HTML 存 `evaluation_report` 表（`content_md`/`content_html`/`llm_model`/`prompt_summary`/`status`/`error_message`）。前端 `GET /api/v1/evaluation/tasks/{id}/report` 拉取，generating 态每 5s 轮询。支持重新生成（`force=true`）与下载 MD/Word。

---

## 4. 元数据获取改造（让通用拼装可行）

通用 prompt 拼装的前提是能拿到模型/数据集的元数据。原实现因 Feign 接口/Mapper 缺 JOIN，模型名/描述/任务类型、数据集名/描述/任务类型**根本拿不到**（恒"未知模型""暂无数据集描述"）。改造如下：

### 4.1 aisys-model：版本接口返回父模型信息

- `ModelVersionMapper.xml` `selectById` LEFT JOIN model 表，返回 `model.name`/`description`/`type`/`framework`。
- `ModelVersion` 实体加 4 个瞬态字段（非表列，JOIN 填充，不参与 insert/update）。
- `VersionDtos.ModelVersionResponse` 补 `modelName`/`modelDescription`/`taskType`/`framework`（向后兼容，只加字段）。
- `ModelVersionServiceImpl.toResponse` 传入。

效果：评测侧 `ModelClient.getVersionById` 一次调用即拿到模型名/描述/任务类型。

### 4.2 aisys-dataset：版本接口返回父数据集信息

- `DatasetVersionMapper.xml` `selectById` LEFT JOIN dataset 表，返回 `dataset.name`/`description`/`task_type`/`format`/`sample_count`。
- `DatasetVersion` 实体加 5 个瞬态字段。
- `DatasetVersionDtos.Response` 补字段。
- `DatasetVersionService.toResponse` 传入。

效果：评测侧 `DatasetClient.getVersionById` 一次拿到数据集名/描述/任务类型。

两处都**不动 DB schema**（仅改查询 JOIN 和 DTO 字段），向后兼容。

---

## 5. 训练全流程（与评测对称）

训练流程与评测同构，区别在于：训练是单模型、产出权重；评测是多模型、产出分数 + 报告。

### 5.1 任务生命周期

```
training POST /tasks/{id}/start
  → publishes task.command.train.{id} (Outbox)
resource scheduler consumes, picks node, presigns S3 PUT URL for training/{taskId}/output/best.pt
  → sends run_task over WS to agent
agent: docker run ... <image>
  → streams stdout JSONL, parses, forwards each frame as WS Report
resource: AgentWebSocketHandler routes by type → forwardStatus/forwardLog/forwardMetrics
  → publishes to task.status/task.log/task.metrics exchanges (routing key task.<type>.training)
training: TaskEventConsumer binds task.status.training → handleStatusEvent
  → 更新任务状态、saveCompletedCheckpoint（写 checkpoint 行）
  → onLog 写 task_log、onMetrics 写 task_metric (JSONB, 15s downsample)
web: GET /tasks/{id} + /logs + /metrics; 轮询每 3s（running 态），终态停
```

### 5.2 Checkpoint 与权重下载

规范 S3 key：`{tenantId}/training/{taskId}/output/best.pt`（TRAINING 的 `dispatchId == taskId`）。两个上报携带存储路径：
- **report 1**（`result` 帧，容器退出时发）：带 `data.result.weights`。`ForwardServiceImpl.enrichTrainingStatus`（`ForwardServiceImpl.java:119`）提取（注意双层嵌套 `data.result.result.weights`，agent 把容器整个 result 帧包进 `data.result`）→ 写 `extra.storagePath` → training 的 `saveCompletedCheckpoint` 写 checkpoint，即使 report 2 丢失也能写。
- **report 2**（`status` 帧，上传 best.pt 成功后发）：带 `data.outputPath`，直接被 `enrichTrainingStatus` 的 `firstText(data,"outputPath",...)` 提取。

`saveCompletedCheckpoint` 幂等（按 storagePath 去重）。

**前端下载**：`storage.ts downloadFile`（`storage.ts:155`）用 XHR 带 `Authorization` 头拉 blob → `createObjectURL` 触发下载（**不用 `window.open`**——浏览器原生导航不带 auth 头会被 storage 安全过滤器拦 401）。storage 服务 `StoragePathUtil.resolveLenient` 补 `{tenantId}/` 前缀从 S3 流式返回。

### 5.3 实时监控页

`monitor/index.vue`：
- `reload()` 并行拉 detail + metrics + logs；running 态每 3s 轮询（`startPolling`），终态自动停（`isTerminalStatus`）。
- **日志**：后端 `ORDER BY logged_at DESC` 返回最新 200 条，前端 `slice().reverse()` 渲染（最新在底部，配合 `scrollToBottom`）。
- **指标图表**：`buildChartOption` 动态收集 `points[].metrics` 的所有 key 生成 series（不硬编码 loss/lr/accuracy）；无数据显示"暂无指标数据"（不回退假数据）。x 轴有 step 用 step，没有用时间。
- **训练产物卡片**：`v-if="status==='completed' && checkpoints.length>0"` 显示下载按钮。

---

## 6. 横切机制

### 6.1 多租户（RLS）

PostgreSQL 行级安全：所有业务表 `ENABLE ROW LEVEL SECURITY + FORCE + POLICY`（`tenant_id = current_setting('app.tenant_id', true)::bigint`）。`UserContext` ThreadLocal 携带 tenantId；`common-mybatis` 的 `TenantContextInterceptor` 在每条 SQL 前 `SET LOCAL app.tenant_id`。MQ 消费线程无 HTTP 上下文，由消息体 tenantId 显式设置 `UserContext`。`task_metric` 是唯一不启用 RLS 的表（显式按 task_id 过滤）。

### 6.2 事件发布（Outbox 模式）

`aisys-common-mq` `EventPublisher`（`EventPublisher.java:39`）在业务事务内：序列化消息 → 写 `event_outbox` 表（pending）。`OutboxPublisher` 异步投递到 RabbitMQ。从而保证"更新 DB + 发 MQ"一致性，不依赖分布式事务。

### 6.3 S3 / SeaweedFS

`aisys-common-s3` `S3StorageService` 包装 AWS SDK v3 presigner。预签名 URL 分下载（拉镜像 tar、数据集）和上传（写 best.pt）。SeaweedFS 是 S3 兼容后端，数据卷 `weeddata`。

### 6.4 网关路由

`aisys-gateway/.../config/RouteConfig.java`：`/api/v1/training/**`→training:8004、`/api/v1/resources/**`+`/api/v1/agent/**`+`/ws/v1/agent/**`→resource:8006、`/api/v1/files/**`→storage:8007 等。

### 6.5 服务发现与配置

Nacos 注册发现。配置部分在 `application.yml`，基础设施（DB/RabbitMQ）经 compose env。LLM 配置经 `deploy/.env`（见 §3.5.1）。

---

## 7. 运维与调试

### 7.1 重建命令

```bash
cd /home/www/projects/aisys/deploy
docker compose up -d --build <service>           # 重建某服务（改了 Java/Go 代码）
docker compose up -d evaluation                  # 仅重载 env（改了 .env，不编译）
docker compose logs <service> --tail 100
docker compose ps
docker compose exec -T -e PGPASSWORD=aisys postgresql psql -U aisys -d aisys -c "<sql>"
```

### 7.2 常见问题排查

| 现象 | 排查 | 根因/修复 |
|---|---|---|
| 任务 completed 但无 checkpoint | 查 `docker compose logs training | grep saveCompletedCheckpoint` 的 `extra` 是否空 | report 2 丢失；`enrichTrainingStatus` 已加 report 1 的 `result.weights` 兜底 |
| 日志断在第一句 | `docker compose logs agent | grep scanner` | tqdm `\r` 累积突破 buffer；已去 `-t`+`TQDM_DISABLE=1`+`cleanLogLine` 截断+取最后 `\r` 段 |
| 点下载 401 | 浏览器 Network 看 download 请求 | `window.open` 不带 auth；已改 XHR 拉 blob |
| 重建镜像后行为没变 | `docker exec agent docker images` 看是否有同名旧镜像 | agent 按 imageName 缓存；换新版本号 |
| 评测报告"未知模型" | 历史问题 | ModelVersionMapper 缺 JOIN；已加 JOIN 返回 modelName/taskType |
| 报告讲 mAP 但被测是时序模型 | system prompt CV 写死 | 已改 `chooseSystemPrompt` 按任务类型分支 |
| LLM 报告内容空/被截断 | max_tokens 太小 | 已改 8192 |
| 改 LLM 配置要重编译 | application.yml 在 JAR 里 | 已支持 .env，改 `.env` + `docker compose up -d evaluation` 不编译 |

### 7.3 权威文档

- `docs/model-container-adapter.md`：模型容器 JSONL 协议、环境变量、Dockerfile 布局、适配新库检查清单（第 7 节含"每 epoch emit metric 帧"契约）。
- `docs/training-weights-download.md`：训练权重 checkpoint 与下载链路。
- `CODEBUDDY.md`：给 AI 助手的项目指引，含服务地图与坑点速查。

---

## 8. 全链路时序（一图总结）

```
[用户] 创建评测任务（N 个模型 + benchmark）
  ↓
[evaluation] create → 拆 N 个 subtask → start → MQ task.command.eval (Outbox)
  ↓
[resource] scheduler 消费 → 选节点 → 预签名 S3 → WS run_task → agent
  ↓
[agent] docker load + docker run (无 -t, TQDM_DISABLE=1)
  ↓ 容器 stdout JSONL
[agent] bufio.Scanner 逐行 → cleanLogLine (剥ANSI/取最后\r/截2000) → handleJsonlLine
  ↓ 按帧 type 转发
[agent] WS Report → resource
  ↓
[resource] forwardStatus/Log/Metrics → MQ task.status/log/metrics.evaluation
  ↓
[evaluation] TaskStatusConsumer → handleSubtaskStatus → 更新 subtask → writeResultIfAbsent
  ↓ 全部子任务终态
[evaluation] aggregateParent → completed/completed_with_errors
  ↓ 异步触发
[evaluation] EvaluationReportServiceImpl.generateReportAsync
  ↓ buildContext (读 task/results/benchmark/subtasks + Feign 模型/数据集元数据 + collectMetricKeys)
  ↓ chooseSystemPrompt(modelTaskType) + buildUserPrompt → callLlm (Ollama/OpenAI, .env 配置)
  ↓ Markdown → HTML → 存 evaluation_report 表
[web] 评测报告页轮询 → 渲染 Markdown + 数据概览
```

训练链路与评测对称（单模型、产权重、有 checkpoint 下载），监控页实时显示日志滚动 + 指标曲线 + 下载按钮。
