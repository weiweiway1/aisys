-- 数据集元数据表（租户隔离，RLS + FORCE，DDD 5.4）
CREATE TABLE dataset (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    project_id  BIGINT,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(32)  NOT NULL DEFAULT 'text',   -- text/image/audio/video/multimodal
    format      VARCHAR(16)  NOT NULL DEFAULT 'jsonl',  -- jsonl/csv/json/parquet
    description TEXT,
    license     VARCHAR(64),
    status      VARCHAR(16)  NOT NULL DEFAULT 'active', -- active/archived/deleted
    created_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_dataset_tenant   ON dataset(tenant_id);
CREATE INDEX idx_dataset_project  ON dataset(project_id);
CREATE INDEX idx_dataset_status   ON dataset(status);

-- 租户隔离（RLS）
ALTER TABLE dataset ENABLE ROW LEVEL SECURITY;
ALTER TABLE dataset FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dataset
    USING      (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE dataset ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;
