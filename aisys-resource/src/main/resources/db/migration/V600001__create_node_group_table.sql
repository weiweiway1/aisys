-- V600001 节点分组表（平台共享，不启用 RLS）
CREATE TABLE IF NOT EXISTS node_group (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE  node_group IS '节点分组（平台共享表，不启用 RLS）';
COMMENT ON COLUMN node_group.name IS '分组名称';
