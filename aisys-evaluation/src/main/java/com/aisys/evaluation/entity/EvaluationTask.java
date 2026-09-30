package com.aisys.evaluation.entity;

import java.time.Instant;

/**
 * 评测任务（父）。状态由子任务聚合：
 * 全部成功→completed；部分失败→completed_with_errors；全部失败→failed。
 * JSONB 列以原始 JSON 字符串承载，由 Service 层用 ObjectMapper 转换。
 */
public class EvaluationTask {

    private Long id;
    private Long tenantId;
    private Long projectId;
    private Long benchmarkId;
    private String name;
    /** model_version_ids JSONB → 原始 JSON 字符串（如 "[10,20]"） */
    private String modelVersionIds;
    private String status;
    private Integer progress;
    /** config JSONB → 原始 JSON 字符串 */
    private String config;
    private Instant startedAt;
    private Instant completedAt;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getBenchmarkId() { return benchmarkId; }
    public void setBenchmarkId(Long benchmarkId) { this.benchmarkId = benchmarkId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getModelVersionIds() { return modelVersionIds; }
    public void setModelVersionIds(String modelVersionIds) { this.modelVersionIds = modelVersionIds; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
