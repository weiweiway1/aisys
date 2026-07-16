package com.aisys.evaluation.entity;

import java.time.Instant;

/**
 * 评测结果聚合（按 model_version + benchmark）。overall_scores / category_scores 以 JSON 字符串存储。
 */
public class EvaluationResult {

    private Long id;
    private Long evaluationTaskId;
    private Long tenantId;
    private Long modelVersionId;
    private Long benchmarkId;
    private String overallScores;
    private String categoryScores;
    private Integer sampleCount;
    private String detailPath;
    private Instant completedAt;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEvaluationTaskId() { return evaluationTaskId; }
    public void setEvaluationTaskId(Long evaluationTaskId) { this.evaluationTaskId = evaluationTaskId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getModelVersionId() { return modelVersionId; }
    public void setModelVersionId(Long modelVersionId) { this.modelVersionId = modelVersionId; }
    public Long getBenchmarkId() { return benchmarkId; }
    public void setBenchmarkId(Long benchmarkId) { this.benchmarkId = benchmarkId; }
    public String getOverallScores() { return overallScores; }
    public void setOverallScores(String overallScores) { this.overallScores = overallScores; }
    public String getCategoryScores() { return categoryScores; }
    public void setCategoryScores(String categoryScores) { this.categoryScores = categoryScores; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public String getDetailPath() { return detailPath; }
    public void setDetailPath(String detailPath) { this.detailPath = detailPath; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
