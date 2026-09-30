-- V700003 为 upload_task 增加复合索引，优化租户作用域 + 时间倒序的列表查询（非超管看自己任务的主路径）。
-- 幂等：索引可能已存在。
CREATE INDEX IF NOT EXISTS idx_upload_task_tenant_created
    ON upload_task (tenant_id, created_at DESC);
