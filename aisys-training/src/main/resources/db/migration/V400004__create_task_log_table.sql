-- 训练任务日志表（跨租户/平台共享：Agent 高频写入，Service 显式按 task_id 过滤，不启用 RLS）
CREATE TABLE task_log (
    id        BIGSERIAL    PRIMARY KEY,
    task_id   BIGINT       NOT NULL,
    tenant_id BIGINT,
    level     VARCHAR(16),
    step      BIGINT,
    message   TEXT,
    logged_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_task_log_task_time ON task_log(task_id, logged_at);
COMMENT ON TABLE task_log IS '训练任务日志（不启用 RLS，Service 显式按 task_id 过滤）';
