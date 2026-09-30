-- V600004 资源租约表（平台共享表，不启用 RLS —— 调度聚合需全局可见所有租户的持有量，
-- 否则在租户上下文下只看得到当前租户的 allocation、会高估空闲导致跨租户超卖 GPU。DDD 5.7.4）
CREATE TABLE IF NOT EXISTS resource_allocation (
    id            BIGSERIAL PRIMARY KEY,
    node_id       BIGINT NOT NULL REFERENCES compute_node(id) ON DELETE CASCADE,
    task_type     VARCHAR(16) NOT NULL,                 -- TRAINING / EVALUATION
    task_id       BIGINT NOT NULL,
    tenant_id     BIGINT NOT NULL,                       -- 仅作归属/计费，不用于隔离
    gpu_count     INTEGER NOT NULL DEFAULT 0,
    cpu           INTEGER NOT NULL DEFAULT 0,
    memory_bytes  BIGINT NOT NULL DEFAULT 0,
    gpu_devices   JSONB,                                 -- 具体分配的 GPU 索引，如 [0,1,2,3]
    status        VARCHAR(16) NOT NULL DEFAULT 'held',   -- held / released
    acquired_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ra_node ON resource_allocation(node_id, status);
CREATE INDEX IF NOT EXISTS idx_ra_task ON resource_allocation(task_type, task_id);

COMMENT ON TABLE  resource_allocation IS '资源租约（平台共享表，不启用 RLS）。held=占用，released=已释放';
COMMENT ON COLUMN resource_allocation.task_type IS 'TRAINING / EVALUATION';
COMMENT ON COLUMN resource_allocation.gpu_devices IS '具体分配的 GPU 索引数组，如 [0,1,2,3]';
