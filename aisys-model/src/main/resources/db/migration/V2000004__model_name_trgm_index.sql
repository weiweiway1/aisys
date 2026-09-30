-- ============================================================
-- aisys-model V2000004：model.name 的 pg_trgm GIN 索引
-- 支持中文子串 ILIKE 检索（DDD 5.3，列表 keyword 用 ILIKE name/description）。
-- ============================================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_model_name_trgm
    ON model USING GIN (name gin_trgm_ops);
