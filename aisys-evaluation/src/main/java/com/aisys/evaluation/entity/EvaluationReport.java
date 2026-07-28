package com.aisys.evaluation.entity;

import java.time.Instant;

/**
 * 评测报告（LLM 生成）实体。
 * 每个评测任务最多保留一份有效报告；重新生成时覆盖旧记录。
 */
public class EvaluationReport {

    private Long id;
    private Long tenantId;
    private Long evaluationTaskId;
    private String status;          // pending / generating / completed / failed
    private String contentMd;       // Markdown 格式
    private String contentHtml;     // HTML 格式
    private String llmModel;        // 使用的大模型名称
    private String promptSummary;   // 提示词摘要
    private String errorMessage;    // 错误信息
    private Instant generatedAt;
    private Instant createdAt;
    private Instant updatedAt;

    // ---- getters & setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getEvaluationTaskId() { return evaluationTaskId; }
    public void setEvaluationTaskId(Long evaluationTaskId) { this.evaluationTaskId = evaluationTaskId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getContentMd() { return contentMd; }
    public void setContentMd(String contentMd) { this.contentMd = contentMd; }
    public String getContentHtml() { return contentHtml; }
    public void setContentHtml(String contentHtml) { this.contentHtml = contentHtml; }
    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }
    public String getPromptSummary() { return promptSummary; }
    public void setPromptSummary(String promptSummary) { this.promptSummary = promptSummary; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
