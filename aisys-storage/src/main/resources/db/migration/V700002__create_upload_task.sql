-- 上传任务表（平台共享表，不启用 RLS；DDD 5.8 / 服务号 700）
-- 大文件分片上传：浏览器→存储后端(逐片)→后端写 S3 multipart part→complete 组装入池。
-- 状态机：UPLOADING(收片中) → PROCESSING(组装中) → COMPLETED(已入池) / FAILED。
-- 浏览器全程只与存储后端通信，不直接访问存储池 Endpoint。
CREATE TABLE upload_task (
    id              BIGSERIAL    PRIMARY KEY,
    tenant_id       BIGINT,
    pool_id         BIGINT       NOT NULL,
    relative_path   VARCHAR(512) NOT NULL,    -- 租户相对目标路径（与 browse 一致）
    file_name       VARCHAR(256),
    size_bytes      BIGINT       NOT NULL DEFAULT 0,
    chunk_size      BIGINT       NOT NULL,
    total_chunks    INT          NOT NULL,
    received_chunks INT          NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'UPLOADING',  -- UPLOADING/PROCESSING/COMPLETED/FAILED
    upload_id       VARCHAR(256),               -- S3 multipart uploadId
    bucket          VARCHAR(128),
    final_key       VARCHAR(512),               -- 组装完成后的全 key（含租户前缀）
    error_msg       VARCHAR(512),
    parts           JSONB,                       -- [{partNumber,etag},...] 已收 part（供 complete 组装）
    created_by      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_upload_task_tenant ON upload_task(tenant_id);
CREATE INDEX idx_upload_task_status ON upload_task(status);
COMMENT ON TABLE upload_task IS '上传任务（大文件分片：浏览器→后端→存储池，平台共享表）';
