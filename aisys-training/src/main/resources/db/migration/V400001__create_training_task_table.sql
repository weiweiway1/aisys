-- 训练任务表（服务号 400，租户隔离，启用 RLS + FORCE）
-- assigned_node_id 不在此建 FK（V600003 由 Resource 服务补建）
CREATE TABLE training_task (
    id                 BIGSERIAL    PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL,
    project_id         BIGINT,
    name               VARCHAR(128) NOT NULL,
    model_version_id   BIGINT,
    dataset_version_id BIGINT,
    image              VARCHAR(256) NOT NULL,
    command            TEXT,
    resource_spec      JSONB,
    hyperparameters    JSONB,
    status             VARCHAR(16)  NOT NULL DEFAULT 'pending',
    priority           INT          NOT NULL DEFAULT 0,
    assigned_node_id   BIGINT,
    progress           INT          NOT NULL DEFAULT 0,
    error_message      TEXT,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_by         BIGINT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_training_task_status ON training_task(status);
CREATE INDEX idx_training_task_node  ON training_task(assigned_node_id);
CREATE INDEX idx_training_task_proj  ON training_task(project_id);
CREATE INDEX idx_training_task_tenant ON training_task(tenant_id);
COMMENT ON TABLE training_task IS '训练任务（租户隔离，RLS FORCE；assigned_node_id 跨服务引用不建 FK）';

-- 租户隔离（RLS）
ALTER TABLE training_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE training_task FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON training_task
    USING      (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE training_task ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;
