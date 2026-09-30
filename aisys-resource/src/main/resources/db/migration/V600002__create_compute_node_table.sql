-- V600002 计算节点表（平台共享表，不启用 RLS —— 调度器需全局可见所有节点）
CREATE TABLE IF NOT EXISTS compute_node (
    id                BIGSERIAL PRIMARY KEY,
    node_group_id     BIGINT REFERENCES node_group(id) ON DELETE SET NULL,
    agent_id          VARCHAR(64) NOT NULL,
    node_name         VARCHAR(128) NOT NULL,
    ip_address        VARCHAR(64),
    status            VARCHAR(16) NOT NULL DEFAULT 'offline',   -- online/offline/maintenance
    agent_version     VARCHAR(32),
    os_info           VARCHAR(256),
    cpu_info          JSONB,                                     -- {model, cores, threads}
    gpu_info          JSONB,                                     -- [{index, model, memoryMb, ...}]
    total_memory      BIGINT,                                    -- bytes
    total_disk        BIGINT,                                    -- bytes
    labels            JSONB DEFAULT '{}'::jsonb,                 -- {region, gpu_type, ...}
    last_heartbeat_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_compute_node_agent_id UNIQUE (agent_id)
);

CREATE INDEX IF NOT EXISTS idx_compute_node_status ON compute_node(status);

COMMENT ON TABLE  compute_node IS '计算节点（Agent 注册登记，平台共享表，不启用 RLS）';
COMMENT ON COLUMN compute_node.agent_id IS 'Agent 唯一标识（注册时由平台分配/约定）';
COMMENT ON COLUMN compute_node.status IS 'online/offline/maintenance';
COMMENT ON COLUMN compute_node.gpu_info IS 'GPU 信息数组：[{index,model,memoryMb,driverVersion,cudaVersion}]';
