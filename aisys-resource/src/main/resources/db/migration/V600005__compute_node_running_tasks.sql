-- V600005 计算节点增加 running_tasks 列（心跳上报的运行中任务数，负载信号）。
-- 幂等：列可能已存在。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'compute_node' AND column_name = 'running_tasks'
    ) THEN
        ALTER TABLE compute_node ADD COLUMN running_tasks INTEGER NOT NULL DEFAULT 0;
    END IF;
END $$;

COMMENT ON COLUMN compute_node.running_tasks IS '当前运行中任务数（心跳上报，负载调度参考）';
