-- V2000005 模型 type 列规范化 + 注释。
-- model.type 与 dataset.taskType 对齐：image_classification / time_series / object_detection。
-- 注意：model 表启用 RLS，本迁移在 app-user 下执行，UPDATE 受租户上下文影响可能 0 行；
-- 历史脏值由超管手动归一化（DTO 层 @Pattern 已强制词汇，DB CHECK 可选）。
DO $$
BEGIN
    UPDATE model SET type = 'object_detection' WHERE type ILIKE 'IMAGE';
    UPDATE model SET type = 'image_classification'
      WHERE type IS NULL OR type NOT IN ('image_classification','time_series','object_detection');
END $$;
COMMENT ON COLUMN model.type IS 'ML 任务类型，与 dataset.taskType 对齐：image_classification / time_series / object_detection';
