-- 通知记录表（DDD 5.9）
-- tenant_id 可空（平台级通知/未登录态场景）→ 不启用 RLS，业务层显式按 user_id/tenant_id 过滤。
CREATE TABLE notification_record (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT,
    user_id     BIGINT,
    type        VARCHAR(32)  NOT NULL,            -- 通知类型，如 TASK_COMPLETED / NODE_OFFLINE / SYSTEM
    title       VARCHAR(256) NOT NULL,
    content     TEXT,
    level       VARCHAR(16)  NOT NULL DEFAULT 'info', -- info / warning / error / success
    is_read     BOOLEAN      NOT NULL DEFAULT false,
    ref_type    VARCHAR(32),                       -- 关联资源类型，如 TASK / NODE / DATASET
    ref_id      VARCHAR(64),                       -- 关联资源 ID
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_notification_user_read ON notification_record(user_id, is_read);
CREATE INDEX idx_notification_tenant    ON notification_record(tenant_id);
CREATE INDEX idx_notification_created   ON notification_record(created_at DESC);
COMMENT ON TABLE notification_record IS '站内通知记录（tenant_id 可空，不启用 RLS，显式按 user_id/tenant_id 过滤）';
COMMENT ON COLUMN notification_record.level IS '通知级别：info/warning/error/success';

-- 通知规则表（DDD 5.9）
-- 租户可配置：按 event_type 匹配 → 按 channels 分发。不启用 RLS，业务层显式过滤。
CREATE TABLE notification_rule (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT,
    event_type   VARCHAR(64)  NOT NULL,           -- 事件类型，如 TASK_COMPLETED / NODE_OFFLINE
    target_type  VARCHAR(16)  NOT NULL,           -- USER / ROLE / TENANT
    target_ids   JSONB,                            -- 目标 ID 列表（按 target_type 解释）
    channels     JSONB        NOT NULL,            -- 分发渠道：["IN_APP","EMAIL","WEBHOOK"]
    webhook_url  VARCHAR(512),                     -- WEBHOOK 渠道的回调地址
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_rule_tenant_event ON notification_rule(tenant_id, event_type);
CREATE INDEX idx_rule_enabled      ON notification_rule(enabled);
COMMENT ON TABLE notification_rule IS '通知规则（按 event_type 匹配后按 channels 分发；不启用 RLS，显式过滤）';
