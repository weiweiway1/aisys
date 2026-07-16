-- ============================================================
-- aisys-evaluation 评测服务建表（服务号 500）
-- DDD 5.6：benchmark / evaluation_task / evaluation_subtask / evaluation_result
-- ============================================================

-- benchmark：评测基准集
CREATE TABLE IF NOT EXISTS benchmark (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL,
    name                VARCHAR(128) NOT NULL,
    category            VARCHAR(32),
    description         TEXT,
    dataset_version_ids JSONB,
    metrics_config      JSONB,
    eval_config         JSONB,
    prompt_template     TEXT,
    status              VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- evaluation_task：评测任务（父，聚合多个模型版本）
CREATE TABLE IF NOT EXISTS evaluation_task (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         BIGINT NOT NULL,
    project_id        BIGINT,
    benchmark_id      BIGINT,
    name              VARCHAR(128),
    model_version_ids JSONB,
    status            VARCHAR(16) NOT NULL DEFAULT 'pending',
    progress          INTEGER NOT NULL DEFAULT 0,
    config            JSONB,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    created_by        BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- evaluation_subtask：子任务（每个 model_version 一个，DDD 5.6.4）
CREATE TABLE IF NOT EXISTS evaluation_subtask (
    id                BIGSERIAL PRIMARY KEY,
    parent_task_id    BIGINT NOT NULL REFERENCES evaluation_task(id) ON DELETE CASCADE,
    tenant_id         BIGINT NOT NULL,
    model_version_id  BIGINT NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'pending',
    assigned_node_id  BIGINT,
    error_message     TEXT,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- evaluation_result：评测结果聚合（按 model_version + benchmark）
CREATE TABLE IF NOT EXISTS evaluation_result (
    id                BIGSERIAL PRIMARY KEY,
    evaluation_task_id BIGINT NOT NULL,
    tenant_id         BIGINT NOT NULL,
    model_version_id  BIGINT NOT NULL,
    benchmark_id      BIGINT NOT NULL,
    overall_scores    JSONB,
    category_scores   JSONB,
    sample_count      INTEGER,
    detail_path       VARCHAR(512),
    completed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_eval_task_tenant    ON evaluation_task(tenant_id);
CREATE INDEX IF NOT EXISTS idx_eval_task_project   ON evaluation_task(project_id);
CREATE INDEX IF NOT EXISTS idx_eval_task_benchmark ON evaluation_task(benchmark_id);
CREATE INDEX IF NOT EXISTS idx_eval_task_status    ON evaluation_task(status);

CREATE INDEX IF NOT EXISTS idx_eval_subtask_parent ON evaluation_subtask(parent_task_id);
CREATE INDEX IF NOT EXISTS idx_eval_subtask_tenant ON evaluation_subtask(tenant_id);
CREATE INDEX IF NOT EXISTS idx_eval_subtask_model  ON evaluation_subtask(model_version_id);

CREATE INDEX IF NOT EXISTS idx_eval_result_task      ON evaluation_result(evaluation_task_id);
CREATE INDEX IF NOT EXISTS idx_eval_result_model     ON evaluation_result(model_version_id);
CREATE INDEX IF NOT EXISTS idx_eval_result_benchmark ON evaluation_result(benchmark_id);
CREATE INDEX IF NOT EXISTS idx_eval_result_tenant    ON evaluation_result(tenant_id);

-- RLS：benchmark / evaluation_task / evaluation_subtask / evaluation_result 均 tenant 隔离
ALTER TABLE benchmark          ENABLE ROW LEVEL SECURITY;
ALTER TABLE benchmark          FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON benchmark
    USING (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE benchmark ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;

ALTER TABLE evaluation_task    ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluation_task    FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evaluation_task
    USING (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE evaluation_task ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;

ALTER TABLE evaluation_subtask ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluation_subtask FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evaluation_subtask
    USING (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE evaluation_subtask ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;

ALTER TABLE evaluation_result  ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluation_result  FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evaluation_result
    USING (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE evaluation_result ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;

-- updated_at 触发器
CREATE OR REPLACE FUNCTION trg_eval_set_updated_at() RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = NOW(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_benchmark_updated_at          ON benchmark;
CREATE TRIGGER trg_benchmark_updated_at          BEFORE UPDATE ON benchmark
    FOR EACH ROW EXECUTE FUNCTION trg_eval_set_updated_at();

DROP TRIGGER IF EXISTS trg_evaluation_task_updated_at    ON evaluation_task;
CREATE TRIGGER trg_evaluation_task_updated_at    BEFORE UPDATE ON evaluation_task
    FOR EACH ROW EXECUTE FUNCTION trg_eval_set_updated_at();

DROP TRIGGER IF EXISTS trg_evaluation_subtask_updated_at ON evaluation_subtask;
CREATE TRIGGER trg_evaluation_subtask_updated_at BEFORE UPDATE ON evaluation_subtask
    FOR EACH ROW EXECUTE FUNCTION trg_eval_set_updated_at();
