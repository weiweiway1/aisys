-- 用户表（表名用 aisys_user 避开 Postgres 保留字 user / pg_user）
-- tenant_id 可空（平台超管无租户）→ 不启用 RLS，业务层显式过滤（DDD 4.1.3 表分类）
CREATE TABLE aisys_user (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT REFERENCES tenant(id),
    username      VARCHAR(64)  NOT NULL UNIQUE,
    email         VARCHAR(128),
    phone         VARCHAR(32),
    password_hash VARCHAR(128) NOT NULL,
    nickname      VARCHAR(64),
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_user_tenant ON aisys_user(tenant_id);
CREATE INDEX idx_user_status ON aisys_user(status);
COMMENT ON TABLE aisys_user IS '用户（tenant_id 可空=平台超管；不启用 RLS，显式过滤）';
