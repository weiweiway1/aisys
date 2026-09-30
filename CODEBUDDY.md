# CODEBUDDY.md

This file provides guidance to CodeBuddy Code when working with code in this repository.

## Project overview

aisys is a multi-tenant AI model evaluation/training platform. A Spring Cloud microservice backend (Java 25, Spring Boot 4.1, Spring Cloud 2025.1.2, Spring Cloud Alibaba/Nacos) orchestrates black-box testing and training of AI models; a Go agent runs each model as a Docker container; a Vue 3 web is the frontend. All services run as containers via `deploy/docker-compose.yml`.

## Common commands

### Docker (primary dev loop — run from `deploy/`)
```bash
cd deploy
docker compose up -d                              # start everything (infra + all services)
docker compose up -d --build <service>           # rebuild + start one service (e.g. training, agent, web, resource)
docker compose up -d --build agent resource web  # rebuild several at once
docker compose logs <service> --tail 100          # tail logs
docker compose ps                                # status
docker compose exec -T -e PGPASSWORD=aisys postgresql psql -U aisys -d aisys -c "<sql>"   # query DB
```
Host ports: gateway 8080, web 8081, postgres 5432, rabbitmq 15672 (mgmt UI), nacos 8848, seaweedfs 9333/8888, grafana 3000, prometheus 9090.

### Backend (Java/Maven)
Each service is a Maven module under the parent `pom.xml`. Build artifacts inside docker (the service Dockerfiles use a `maven:3.9-eclipse-temurin-25` build stage) — local `mvn` is usually unnecessary; rebuild via `docker compose up -d --build <service>`.
```bash
mvn -pl aisys-training -am clean package -DskipTests   # build one module + dependencies (if local mvn available)
mvn -pl aisys-training test                              # run a module's tests
```

### Frontend (`aisys-web/`, Vue 3 + Vite + pnpm)
```bash
cd aisys-web
pnpm install
pnpm dev           # dev server on :8848, proxies /api and /ws to http://localhost:8080 (gateway)
pnpm build         # production build (output dist/)
pnpm typecheck     # vue-tsc type check
pnpm lint          # eslint + prettier + stylelint
```

### Agent (`aisys-agent/`, Go 1.24)
Built inside docker (`docker/agent/Dockerfile` does `go build -o /agent .`). No local Go needed. Module: `github.com/gorilla/websocket` only.

## Service map

| Service | Module | Port | Responsibility |
|---|---|---|---|
| gateway | aisys-gateway | 8080 | Spring Cloud Gateway, routes `/api/v1/<domain>/**` to services |
| auth | aisys-auth | 8001 | Users/tenants/roles, JWT |
| model | aisys-model | 8002 | Model & model-version management |
| dataset | aisys-dataset | 8003 | Dataset version management |
| training | aisys-training | 8004 | Training task lifecycle, checkpoint, logs/metrics |
| evaluation | aisys-evaluation | 8005 | Evaluation/benchmark, leaderboard |
| resource | aisys-resource | 8006 | Compute node scheduling, **agent WebSocket endpoint**, S3 presign, status forwarding |
| storage | aisys-storage | 8007 | File browse/upload/**download** proxy to SeaweedFS S3 |
| notification | aisys-notification | 8008 | Notifications |
| monitor | aisys-monitor | 8009 | Audit log |
| agent | aisys-agent (Go) | — | Runs Docker containers for training/eval tasks; WS client of resource |
| web | aisys-web (Vue 3) | 8081 | Frontend |
| — | aisys-common | — | Shared modules: core/security/mybatis/redis/s3/mq/log/feign |

## Architecture: the big picture

### "Each model = a container" contract (read `docs/model-container-adapter.md`)
A model version is a Docker image tar stored in SeaweedFS. The agent downloads+`docker load`s it and runs it with standard mounts/env (`AISYS_DATASET_DIR=/data/dataset:ro`, `AISYS_OUTPUT_DIR=/output:rw`, `AISYS_TASK`, `AISYS_HYPERPARAMS`, …). The container's entry script (`run.py`) emits JSONL to stdout: `status` / `log` / `metric` / `result` frames. The agent parses each line and forwards over WS. **This contract is the integration boundary for any algorithm library** — adapt a new library by copying the reference `run.py` and replacing only `train()`/`evaluate()`.

### Training task lifecycle (end-to-end)
```
training (POST /tasks/{id}/start)
  → publishes task.command.train.{id} to RabbitMQ (Outbox pattern: DB+MQ consistency)
resource scheduler consumes, picks a node, presigns S3 PUT URL for training/{taskId}/output/best.pt
  → sends run_task over WebSocket to the agent (with outputUploadUrl)
agent: docker run -v dataset:/data/dataset:ro -v output:/output -e AISYS_* <image>
  → streams stdout JSONL line-by-line, parses, forwards each frame as a WS Report (status/log/metrics/result)
resource: AgentWebSocketHandler routes report by type → forwardStatus/forwardLog/forwardMetrics
  → publishes to task.status/task.log/task.metrics exchanges (routing key task.<type>.training)
training: TaskEventConsumer binds task.status.training → handleStatusEvent (updates task, writes checkpoint)
  → onLog writes task_log, onMetrics writes task_metric (JSONB, 15s downsample)
web: GET /api/v1/training/tasks/{id} (detail+checkpoints), /logs, /metrics; polls every 3s while running
```

### Checkpoint & weights download flow
The canonical S3 key is `{tenantId}/training/{taskId}/output/best.pt` (for TRAINING, `dispatchId == taskId`). Two reports carry the storage path:
- **report 1** (`result` frame, sent on container exit): carries `data.result.weights`; `ForwardServiceImpl.enrichTrainingStatus` extracts it (note double-nesting: `data.result.result.weights`) and writes `extra.storagePath` so training writes the checkpoint even if report 2 is lost.
- **report 2** (`status` frame, sent after `uploadFile`): carries `data.outputPath` directly.
Training's `saveCompletedCheckpoint` is idempotent (dedups by storagePath). Frontend download: `storage.ts downloadFile` uses XHR with `Authorization` header → blob → `createObjectURL` (not `window.open`, which drops auth → 401). Storage service `StoragePathUtil.resolveLenient` prepends `{tenantId}/`.

### Agent ↔ resource protocol
Agent is a WS client of `resource:8006/api/v1/agent/**` (routed by gateway). Auth: first frame carries `agentToken`. Agent receives `run_task`/`stop_task` commands; sends `status`/`log`/`metrics`/`result` reports. Report struct in `aisys-agent/messages.go` mirrors `aisys-resource/.../dto/WebSocketMessage.java` — keep field names (`taskType`, `taskId`, `data`, `metrics`) aligned when changing either side.

### Cross-cutting infrastructure
- **Multi-tenancy**: PostgreSQL row-level security (RLS); `UserContext` thread-local carries tenantId; MQ consumers set it from the message.
- **Event publishing**: `aisys-common-mq` `EventPublisher` uses the **Outbox pattern** — writes `event_outbox` row in the business transaction; `OutboxPublisher` async-delivers to RabbitMQ. This is how "update DB + send MQ" stays consistent without distributed tx.
- **S3**: `aisys-common-s3` `S3StorageService` wraps the AWS SDK v3 presigner (Java); SeaweedFS is the S3-compatible backend.
- **Gateway routing**: `aisys-gateway/.../config/RouteConfig.java` — `/api/v1/training/**`→training:8004, `/api/v1/resources/**`+`/api/v1/agent/**`+`/ws/v1/agent/**`→resource:8006, `/api/v1/files/**`→storage:8007, etc.
- **Service discovery**: Nacos. **Config**: some in `application.yml`, infra (DB/RabbitMQ) via compose env.

## Gotchas (learned the hard way)

- **Agent caches Docker images by `imageName`** (`executor.go`: `docker image inspect imageName`, skips download if present). When you change a model's `run.py`/Dockerfile and rebuild, **use a new version tag** (e.g. `yolo:v3.2`, not reuse `yolo:v3.1`) or the agent keeps running the old cached image. The platform model version's `imageName` must match the new tag.
- **Agent stdout scanning**: containers run **without** `-t` (no TTY) and with `TQDM_DISABLE=1`, so tqdm/ultralytics don't use `\r` in-place refresh (which `bufio.Scanner` would concatenate into one giant line → `ErrTooLong` → silent stdout loss). `cleanLogLine` truncates each forwarded line to 2000 runes and takes the last `\r` segment. WS text buffer is 64KB (`WebSocketConfig`). Don't re-add `-t` or remove truncation.
- **WS message size**: keep log `Report` payloads small — the `case "log"` in `handleJsonlLine` deliberately does NOT set `Data: obj` (only `Level`+`Message`), to avoid 8KB+ WS `close 1009`. Metric frames DO set `Data: obj` (small).
- **Maven builds in docker hit Docker Hub** — from China networks, base image metadata fetch (`maven:3.9-eclipse-temurin-25`) can time out. Retry, or configure a registry mirror in Docker Desktop settings → Docker Engine (`"registry-mirrors": ["https://docker.m.daocloud.io"]`).
- **pip in model containers**: use a Chinese PyPI mirror in model Dockerfiles (`-i https://pypi.tuna.tsinghua.edu.cn/simple`) — default PyPI times out from China.
- **`docs/model-container-adapter.md`** is the authoritative contract for model containers (JSONL protocol, env vars, Dockerfile layout). `docs/training-weights-download.md` documents the checkpoint/download chain.
