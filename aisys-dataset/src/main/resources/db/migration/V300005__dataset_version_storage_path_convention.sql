-- V300005 数据集版本 storage_path 约定变更：
-- 旧约定 storage_path = datasets/{tenantId}/{datasetId}/{version}.ext（租户段嵌入路径）
-- 新约定 storage_path = datasets/{datasetId}/{version}.ext（relPath，租户前缀由 S3 访问时统一拼接）
-- 原因：大文件分片上传经存储模块 UploadTask 会再拼一次 {tenantId}/ 前缀，导致 complete 读不到对象（卡 creating）。
-- 此迁移把旧路径里紧跟 'datasets/' 的 {tenantId}/ 段去掉，与代码新约定对齐。
-- 仅匹配确实以 'datasets/{tenantId}/' 开头的旧行，新格式行不受影响。
UPDATE dataset_version
SET storage_path = regexp_replace(storage_path, '^datasets/' || tenant_id::text || '/', 'datasets/'),
    updated_at = NOW()
WHERE storage_path LIKE 'datasets/' || tenant_id::text || '/%';
