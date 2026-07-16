-- checkpoint 表（租户隔离，启用 RLS + FORCE；task_id 引用本服务 training_task 建表内 FK）
CREATE TABLE checkpoint (
    id           BIGSERIAL      PRIMARY KEY,
    task_id      BIGINT         NOT NULL REFERENCES training_task(id) ON DELETE CASCADE,
    tenant_id    BIGINT         NOT NULL,
    step         BIGINT,
    storage_path VARCHAR(512),
    loss         DOUBLE PRECISION,
    metrics      JSONB,
    is_active    BOOLEAN        NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_checkpoint_task ON checkpoint(task_id);
CREATE INDEX idx_checkpoint_tenant ON checkpoint(tenant_id);
COMMENT ON TABLE checkpoint IS '训练 checkpoint（租户隔离，RLS FORCE）';

ALTER TABLE checkpoint ENABLE ROW LEVEL SECURITY;
ALTER TABLE checkpoint FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON checkpoint
    USING      (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE checkpoint ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;
