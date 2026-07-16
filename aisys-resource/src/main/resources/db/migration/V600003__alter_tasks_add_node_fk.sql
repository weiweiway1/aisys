-- V600003 后向外键：training_task / evaluation_task 的 assigned_node_id → compute_node(id)
-- 引用方（training V400 / evaluation V500）服务号更小，本服务在自己的迁移里补 FK（DDD 4.1.3 后向引用约定）。
-- 全程幂等：列/约束可能已存在、引用表可能尚未创建（多服务并行迁移）。

-- 1) training_task.assigned_node_id
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'training_task' AND column_name = 'assigned_node_id'
    ) THEN
        ALTER TABLE training_task ADD COLUMN assigned_node_id BIGINT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_training_node'
    ) THEN
        ALTER TABLE training_task
            ADD CONSTRAINT fk_training_node
            FOREIGN KEY (assigned_node_id) REFERENCES compute_node(id)
            ON DELETE SET NULL;
    END IF;
EXCEPTION WHEN undefined_table THEN
    RAISE NOTICE 'training_task 表尚未创建，跳过 fk_training_node（将由后续迁移补建）';
END $$;

-- 2) evaluation_task.assigned_node_id（同样后向引用，保持一致）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'evaluation_task' AND column_name = 'assigned_node_id'
    ) THEN
        ALTER TABLE evaluation_task ADD COLUMN assigned_node_id BIGINT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_evaluation_node'
    ) THEN
        ALTER TABLE evaluation_task
            ADD CONSTRAINT fk_evaluation_node
            FOREIGN KEY (assigned_node_id) REFERENCES compute_node(id)
            ON DELETE SET NULL;
    END IF;
EXCEPTION WHEN undefined_table THEN
    RAISE NOTICE 'evaluation_task 表尚未创建，跳过 fk_evaluation_node（将由后续迁移补建）';
END $$;
