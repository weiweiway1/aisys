-- 训练模板表（租户隔离，启用 RLS + FORCE）
CREATE TABLE training_template (
    id                        BIGSERIAL    PRIMARY KEY,
    tenant_id                 BIGINT       NOT NULL,
    name                      VARCHAR(128) NOT NULL,
    description               TEXT,
    image                     VARCHAR(256) NOT NULL,
    command                   TEXT,
    default_resource_spec     JSONB,
    default_hyperparameters   JSONB,
    created_by                BIGINT,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_training_template_tenant ON training_template(tenant_id);
COMMENT ON TABLE training_template IS '训练模板（租户隔离，RLS FORCE）';

ALTER TABLE training_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE training_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON training_template
    USING      (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE training_template ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;
