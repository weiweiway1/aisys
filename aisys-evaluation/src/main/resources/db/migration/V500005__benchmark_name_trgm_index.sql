-- V500005：benchmark.name 模糊检索（pg_trgm GIN 索引）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

DROP INDEX IF EXISTS idx_benchmark_name_trgm;
CREATE INDEX idx_benchmark_name_trgm ON benchmark USING GIN (name gin_trgm_ops);
