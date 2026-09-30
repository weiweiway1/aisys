package com.aisys.evaluation.entity;

import java.time.Instant;

/**
 * 评测子任务（每个 model_version 一个，DDD 5.6.4）。
 */
public class EvaluationSubtask {

    private Long id;
    private Long parentTaskId;
    private Long tenantId;
    private Long modelVersionId;
    private String status;
    private Long assignedNodeId;
    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(Long parentTaskId) { this.parentTaskId = parentTaskId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getModelVersionId() { return modelVersionId; }
    public void setModelVersionId(Long modelVersionId) { this.modelVersionId = modelVersionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAssignedNodeId() { return assignedNodeId; }
    public void setAssignedNodeId(Long assignedNodeId) { this.assignedNodeId = assignedNodeId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
