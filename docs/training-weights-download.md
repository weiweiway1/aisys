# 训练权重下载链路

训练任务完成后，前端"下载权重"按钮可下载最佳权重（`best.pt`）。本文档说明端到端实现与关键代码位置。

## 一、端到端数据流

```
[模型容器 run.py]
  训练完成 → 写 /output/best.pt → emit JSONL result 帧（result.weights="/output/best.pt"）
       │
[Agent (Go)]  executor.go:76 runModelContainer
  ├─ runDockerAndStream 解析 stdout，捕获 result 帧
  ├─ reportTerminal (executor.go:342) 发 result 上报（带 data.result.weights）   ← report 1
  ├─ findBestWeight (executor.go:211) 扫描 /output 找权重文件
  └─ uploadFile (executor.go:497) PUT 到预签名 S3 URL → 发 status 上报（带 outputPath） ← report 2
       │
[SeaweedFS/S3]  key = {tenantId}/training/{taskId}/output/best.pt   ← 规范 key，由 resource 预签名决定
       │
[Resource (Java)]  ForwardServiceImpl
  ├─ AgentWebSocketHandler:91 收 result/status 上报 → forwardResult → forwardStatus
  ├─ enrichTrainingStatus (ForwardServiceImpl.java:119) 从 data.outputPath 或 data.result.result.weights
  │   提取 storagePath，写入 TaskStatusMessage.extra
  └─ EventPublisher 经 Outbox 发 MQ（路由键 task.status.training）
       │
[Training (Java)]
  ├─ TrainingMqConfig:36 绑定 task.status.training → TaskEventConsumer.onStatus:41
  ├─ handleStatusEvent (TrainingTaskServiceImpl.java:307) 更新任务状态为 completed
  └─ saveCompletedCheckpoint (:521) 读 extra.storagePath → INSERT checkpoint 行（幂等去重）
       │
[前端]
  ├─ GET /api/v1/training/tasks/{id} 返回 task.checkpoints[]
  ├─ monitor/index.vue:349 渲染"训练产物"卡片（v-if status==='completed' && checkpoints.length>0）
  ├─ :383 "下载权重"按钮 → downloadFile({path: cp.storagePath})
  └─ storage.ts:155 XHR 带 Authorization 头拉 blob → createObjectURL 触发下载
       │
[Storage (Java)]
  ├─ FileController:60 GET /api/v1/files/download?path=
  ├─ StoragePathUtil.resolveLenient 补 {tenantId}/ 前缀
  └─ S3 getStream 流式返回，带 Content-Disposition: attachment
```

## 二、关键约定

- **规范 S3 key**：`{tenantId}/training/{taskId}/output/best.pt`。由 resource 在调度时预签名决定（`SchedulingServiceImpl.java:236`），对 TRAINING 任务 `dispatchId == taskId`，故 key 与任务 id 一一对应。
- **checkpoint.storage_path** 存的是相对路径 `training/{taskId}/output/best.pt`，下载时由 storage 服务补租户前缀。
- **双上报设计**：
  - report 1（`result` 帧）：容器退出时立即发，带 `data.result.weights`。任务靠它标记 completed。
  - report 2（`status` 帧）：上传 best.pt 成功后发，带 `outputPath`。
  - `enrichTrainingStatus` 两个都兜底：先取 `outputPath`，没有则从 `data.result.result.weights` 推规范 key（注意双层嵌套：agent 把容器整个 result 帧包进 `data.result`）。这样即使 report 2 丢失也能写 checkpoint。

## 三、踩过的坑与对应修复

| 现象 | 根因 | 修复 |
|---|---|---|
| 任务 completed 但 checkpoint 表空 | report 2（带 outputPath）依赖上传成功且 WS 不断；丢失则无 checkpoint | `enrichTrainingStatus` 改为 report 1 的 `result.weights` 也能写 checkpoint（`ForwardServiceImpl.java:136-149`） |
| WS `close 1009`（消息过大）断连，日志/状态帧丢失 | ultralytics 进度条是超长字符串，agent 原样转发成单条 WS 消息超限 | `cleanLogLine` 按 rune 截断到 2000 字符（`executor.go:719`） |
| Agent 上传永久挂起 | `uploadFile` 用 `http.DefaultClient` 无超时 | 改 `&http.Client{Timeout: 10*time.Minute}`（`executor.go:497`） |
| 容器训练后联网下字体卡几分钟 | ultralytics 画图缺字形，下 `Arial.ttf`/`Arial.Unicode.ttf` | 模型容器 run.py 设 `plots=False`（平台只收字符串，不需要图） |
| 点下载返回 401 缺少认证令牌 | `downloadFile` 用 `window.open`，浏览器原生导航不带 Authorization 头 | 改 XHR 带 token 拉 blob 再 `createObjectURL`（`storage.ts:155`） |
| Agent 硬编码 `best.pt`，换算法库写 `best.pth` 就抓不到 | `executor.go` 写死文件名 | `findBestWeight` 递归扫描 `/output` 按评分挑权重（`executor.go:211`） |

## 四、各组件代码位置速查

| 组件 | 文件 | 关键行 |
|---|---|---|
| 容器入口脚本 | 模型镜像内 `run.py` | 复制 best.pt 到 /output、emit result 帧 |
| Agent 容器执行 | `aisys-agent/executor.go` | runModelContainer:76, findBestWeight:211, uploadFile:497, cleanLogLine:719 |
| Agent 消息定义 | `aisys-agent/messages.go` | Command.OutputUploadUrl:45, Report:51 |
| Resource 调度预签名 | `aisys-resource/.../SchedulingServiceImpl.java` | presignUpload:106, 下发 URL:236 |
| Resource 转发/写 extra | `aisys-resource/.../mq/ForwardServiceImpl.java` | enrichTrainingStatus:119 |
| Resource MQ 消息 | `aisys-resource/.../mq/ResourceMessages.java` | TaskStatusMessage.extra:30 |
| Training MQ 绑定 | `aisys-training/.../config/TrainingMqConfig.java` | :36 |
| Training 消费写库 | `aisys-training/.../service/impl/TrainingTaskServiceImpl.java` | handleStatusEvent:307, saveCompletedCheckpoint:521 |
| Training 控制器 | `aisys-training/.../controller/TrainingTaskController.java` | GET /{id}:46 |
| 前端监控页 | `aisys-web/src/views/training/monitor/index.vue` | 训练产物卡片:349, 下载按钮:383 |
| 前端下载封装 | `aisys-web/src/api/storage.ts` | downloadFile:155 |
| Storage 下载端点 | `aisys-storage/.../controller/FileController.java` | :60 |
| Storage 路径解析 | `aisys-storage/.../service/StoragePathUtil.java` | resolveLenient |

## 五、验证步骤

1. 跑一个训练任务（模型版本镜像需带 `plots=False` 的 run.py）。
2. 任务完成后查 checkpoint：
   ```bash
   docker compose exec -T -e PGPASSWORD=aisys postgresql psql -U aisys -d aisys -c \
     "SELECT id, task_id, storage_path FROM checkpoint ORDER BY id DESC LIMIT 5;"
   ```
   应有 `storage_path = training/{taskId}/output/best.pt` 的行。
3. 前端进训练监控页，"训练产物"卡片出现"下载权重"按钮，点击下载 `best.pt`。
4. 若下载 404：Agent 上传环节失败（查 `docker compose logs agent | grep 训练产物`）。
5. 若按钮不出现：checkpoint 未写入（查 `docker compose logs training | grep saveCompletedCheckpoint` 的 `extra` 是否为空）。
