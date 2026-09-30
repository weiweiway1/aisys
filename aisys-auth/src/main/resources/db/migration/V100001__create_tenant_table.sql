-- 租户表（平台注册表，不启用 RLS；storage_pool_id 的 FK 由 V700001 补建，见 DDD 11.1）
CREATE TABLE tenant (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    code            VARCHAR(64)  NOT NULL UNIQUE,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    storage_pool_id BIGINT,
    max_quota_bytes BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tenant_status ON tenant(status);
COMMENT ON TABLE tenant IS '租户（平台注册表，不启用 RLS）';
