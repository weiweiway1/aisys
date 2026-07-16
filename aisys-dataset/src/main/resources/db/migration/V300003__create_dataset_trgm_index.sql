-- 数据集名称模糊检索：pg_trgm GIN 索引（DDD 5.4）
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_dataset_name_trgm ON dataset USING GIN (name gin_trgm_ops);
