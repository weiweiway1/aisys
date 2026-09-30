-- 训练任务指标表（按窗口降采样后写入；不启用 RLS，Service 显式按 task_id 过滤）
CREATE TABLE task_metric (
    id      BIGSERIAL    PRIMARY KEY,
    task_id BIGINT       NOT NULL,
    tenant_id BIGINT,
    ts      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    step    BIGINT,
    metrics JSONB
);
CREATE INDEX idx_task_metric_task_ts ON task_metric(task_id, ts);
COMMENT ON TABLE task_metric IS '训练任务指标（不启用 RLS，Service 显式按 task_id 过滤）';
