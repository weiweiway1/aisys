package com.aisys.training.entity;

import java.time.Instant;

/** checkpoint 实体（checkpoint，租户隔离）。 */
public class Checkpoint {
    private Long id;
    private Long taskId;
    private Long tenantId;
    private Long step;
    private String storagePath;
    private Double loss;
    private String metrics;
    private Boolean isActive;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getStep() { return step; }
    public void setStep(Long step) { this.step = step; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public Double getLoss() { return loss; }
    public void setLoss(Double loss) { this.loss = loss; }
    public String getMetrics() { return metrics; }
    public void setMetrics(String metrics) { this.metrics = metrics; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
