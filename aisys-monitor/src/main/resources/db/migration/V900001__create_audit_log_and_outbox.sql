-- audit_log：平台共享审计表（由各服务 common-log 切面写入，本服务只读查询）。
-- tenant_id 可空（系统/AGENT 操作可能无租户），跨租户查询表，故不启用 RLS，由 Service 层显式过滤。
-- 按月 RANGE 分区（created_at），便于按时间归档/裁剪。
CREATE TABLE audit_log (
    id          BIGSERIAL,
    tenant_id   BIGINT,
    actor_type  VARCHAR(16),                      -- USER / AGENT / SYSTEM
    user_id     BIGINT,
    username    VARCHAR(64),
    action      VARCHAR(64),
    resource    VARCHAR(64),
    resource_id VARCHAR(64),
    detail      JSONB,
    ip_address  INET,
    user_agent  VARCHAR(512),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- DEFAULT 分区：兜底接收分区键范围外的数据，避免插入失败
CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;

-- 月分区：2026-06 ~ 2026-12（上半月起始边界到下月初）
CREATE TABLE audit_log_202606 PARTITION OF audit_log
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE audit_log_202607 PARTITION OF audit_log
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE audit_log_202608 PARTITION OF audit_log
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE audit_log_202609 PARTITION OF audit_log
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE audit_log_202610 PARTITION OF audit_log
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE audit_log_202611 PARTITION OF audit_log
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE audit_log_202612 PARTITION OF audit_log
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');

CREATE INDEX idx_audit_log_tenant_user ON audit_log (tenant_id, user_id);
CREATE INDEX idx_audit_log_resource    ON audit_log (resource, resource_id);
CREATE INDEX idx_audit_log_created     ON audit_log (created_at DESC);


-- event_outbox：跨服务共享的 Outbox 表（事务内写 pending，由各生产服务 OutboxPublisher 投递）。
-- tenant_id 可空（系统事件可能无租户），跨租户平台表，不启用 RLS。
CREATE TABLE event_outbox (
    id             BIGSERIAL PRIMARY KEY,
    producer       VARCHAR(32),
    aggregate_type VARCHAR(64),
    aggregate_id   VARCHAR(64),
    topic          VARCHAR(64),
    routing_key    VARCHAR(128),
    payload        JSONB,
    tenant_id      BIGINT,
    status         VARCHAR(16) NOT NULL DEFAULT 'pending',   -- pending / sent / failed
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at        TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending ON event_outbox (status, created_at);
