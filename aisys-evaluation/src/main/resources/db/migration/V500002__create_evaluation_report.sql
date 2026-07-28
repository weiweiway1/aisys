-- ============================================================
-- 评测报告表（服务号 500，版本 500002）
-- 存储 LLM 生成的评测分析报告，支持 Word/Markdown 导出
-- ============================================================

CREATE TABLE IF NOT EXISTS evaluation_report (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT NOT NULL,
    evaluation_task_id  BIGINT NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'pending',   -- pending / generating / completed / failed
    content_md          TEXT,                                      -- Markdown 格式报告内容
    content_html        TEXT,                                      -- HTML 格式（用于 Word 转换）
    llm_model           VARCHAR(128),                              -- 使用的 LLM 模型名称
    prompt_summary      TEXT,                                      -- 发送给 LLM 的提示词摘要
    error_message       TEXT,                                       -- 生成失败时的错误信息
    generated_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_eval_report_task     ON evaluation_report(evaluation_task_id);
CREATE INDEX IF NOT EXISTS idx_eval_report_tenant    ON evaluation_report(tenant_id);
CREATE INDEX IF NOT EXISTS idx_eval_report_status    ON evaluation_report(status);
-- 唯一约束：每个任务+租户最多一条报告（幂等保护）
-- 注意：rerun 场景会先 DELETE 再 INSERT，不受此约束影响
CREATE UNIQUE INDEX IF NOT EXISTS uk_eval_report_task_tenant
    ON evaluation_report(evaluation_task_id, tenant_id);
-- 每个任务最多保留一份有效报告（幂等：同一 task_id 只有一条 completed 记录）

-- RLS
ALTER TABLE evaluation_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE evaluation_report FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evaluation_report
    USING (tenant_id = current_setting('app.tenant_id', true)::bigint)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::bigint);
ALTER TABLE evaluation_report ALTER COLUMN tenant_id SET DEFAULT current_setting('app.tenant_id', true)::bigint;

-- updated_at 触发器（复用已有函数）
DROP TRIGGER IF EXISTS trg_evaluation_report_updated_at ON evaluation_report;
CREATE TRIGGER trg_evaluation_report_updated_at BEFORE UPDATE ON evaluation_report
    FOR EACH ROW EXECUTE FUNCTION trg_eval_set_updated_at();
