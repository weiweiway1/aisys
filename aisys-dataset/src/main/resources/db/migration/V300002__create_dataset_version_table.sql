-- 数据集版本表（租户隔离，RLS + FORCE，DDD 5.4）
CREATE TABLE dataset_version (
    id          BIGSERIAL PRIMARY KEY,
    dataset_id  BIGINT      NOT NULL REFERENCES dataset(id) ON DELETE CASCADE,
    tenant_id   BIGINT      NOT NULL,
    version     VARCHAR(64) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,   -- S3 对象 key（如 datasets/<tenant>/<dataset>/<version>.jsonl）
    file_size   BIGINT      NOT NULL DEFAULT 0,
    checksum    VARCHAR(128),
    row_count   BIGINT      NOT NULL DEFAULT 0,
    column_info JSONB,                     -- 列定义：[{name,type,...}]
    status      VARCHAR(16)  NOT NULL DEFAULT 'creating', -- creating/ready/failed
    created_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (dataset_id, version)
);
CREATE INDEX idx_dataset_version_dataset ON dataset_version(dataset_id);
CREATE INDEX idx_dataset_version_status  ON dataset_version(status);

-- 租户隔离（RLS）
ALTER TABLE dataset_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE dataset_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON dataset_version
    USING      (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE dataset_version ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;
