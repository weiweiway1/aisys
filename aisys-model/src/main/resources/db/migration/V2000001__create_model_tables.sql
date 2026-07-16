-- ============================================================
-- aisys-model (服务号 200) 迁移：模型 / 模型版本 / 模型标签
-- 表由 aisys_app 拥有并启用 RLS；迁移由 common-mybatis FlywayConfig 在 app 连接池执行。
-- ============================================================

-- 扩展：pg_trgm（中文子串 ILIKE 检索）。IF NOT EXISTS 避免重复创建。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------- model ----------
CREATE TABLE IF NOT EXISTS model (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL,
    project_id  BIGINT,
    name        VARCHAR(128) NOT NULL,
    type        VARCHAR(32),
    framework   VARCHAR(32),
    description TEXT,
    tags        JSONB,
    status      VARCHAR(16) NOT NULL DEFAULT 'draft',
    visibility  VARCHAR(16) NOT NULL DEFAULT 'private',
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_model_tenant_project ON model (tenant_id, project_id);
CREATE INDEX IF NOT EXISTS idx_model_status         ON model (status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_model_tenant_name ON model (tenant_id, name);

ALTER TABLE model ENABLE ROW LEVEL SECURITY;
ALTER TABLE model FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON model
    USING    (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE model ALTER COLUMN tenant_id
    SET DEFAULT current_setting('app.tenant_id', true)::bigint;

-- ---------- model_version ----------
CREATE TABLE IF NOT EXISTS model_version (
    id           BIGSERIAL PRIMARY KEY,
    model_id     BIGINT      NOT NULL REFERENCES model(id) ON DELETE CASCADE,
    tenant_id    BIGINT      NOT NULL,
    version      VARCHAR(64) NOT NULL,
    storage_path VARCHAR(512),
    file_size    BIGINT,
    checksum     VARCHAR(128),
    status       VARCHAR(16) NOT NULL DEFAULT 'creating',
    config       JSONB,
    created_by   BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_model_version_model    ON model_version (model_id);
CREATE INDEX IF NOT EXISTS idx_model_version_checksum ON model_version (checksum);
CREATE UNIQUE INDEX IF NOT EXISTS uq_model_version_model_version ON model_version (model_id, version);

ALTER TABLE model_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_version FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON model_version
    USING    (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE model_version ALTER COLUMN tenant_id
    SET DEFAULT current_setting('app.tenant_id', true)::bigint;

-- ---------- model_tag (树形，parent_id 自引用) ----------
CREATE TABLE IF NOT EXISTS model_tag (
    id         BIGSERIAL PRIMARY KEY,
    tenant_id  BIGINT      NOT NULL,
    parent_id  BIGINT      REFERENCES model_tag(id) ON DELETE CASCADE,
    name       VARCHAR(64) NOT NULL,
    path       VARCHAR(512),
    sort       INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_model_tag_parent ON model_tag (parent_id);
CREATE INDEX IF NOT EXISTS idx_model_tag_tenant ON model_tag (tenant_id);

ALTER TABLE model_tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE model_tag FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON model_tag
    USING    (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE model_tag ALTER COLUMN tenant_id
    SET DEFAULT current_setting('app.tenant_id', true)::bigint;
