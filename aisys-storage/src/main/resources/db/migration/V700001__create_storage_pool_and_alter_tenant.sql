-- 存储池表（平台共享表，不启用 RLS；DDD 5.8 / 服务号 700）
-- storage_pool 由平台维护，所有租户共享，租户路径隔离由 Service 层显式拼前缀实现。
CREATE TABLE storage_pool (
    id           BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(128) NOT NULL UNIQUE,
    type         VARCHAR(32)  NOT NULL DEFAULT 'seaweedfs',
    endpoint     VARCHAR(256),
    bucket       VARCHAR(128),
    access_key   VARCHAR(128),
    secret_key   VARCHAR(256),
    quota_bytes  BIGINT,
    used_bytes   BIGINT       NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_storage_pool_status ON storage_pool(status);
COMMENT ON TABLE storage_pool IS '存储池（平台共享表，不启用 RLS；SeaweedFS/S3 后端）';

-- 租户表（auth V100001 已建）补充 storage_pool_id 外键（DDD 11.1）
ALTER TABLE tenant
    ADD CONSTRAINT fk_tenant_storage_pool
        FOREIGN KEY (storage_pool_id) REFERENCES storage_pool(id) ON DELETE SET NULL;
