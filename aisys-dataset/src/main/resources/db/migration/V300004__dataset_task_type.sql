-- 数据集 ML 任务类型模型（DDD 5.4）：task_type / storage_pool_id / sample_count / tags
-- task_type: image_classification | object_detection | time_series | text（驱动 FormatConverter 与统计）
ALTER TABLE dataset ADD COLUMN task_type       VARCHAR(32);   -- ML 任务类型
ALTER TABLE dataset ADD COLUMN storage_pool_id BIGINT;        -- 关联存储池（可选）
ALTER TABLE dataset ADD COLUMN sample_count    BIGINT;        -- 样本数（版本就绪后回写）
ALTER TABLE dataset ADD COLUMN tags            JSONB;         -- 自由标签数组
COMMENT ON COLUMN dataset.task_type IS 'ML 任务类型：image_classification/object_detection/time_series/text';
