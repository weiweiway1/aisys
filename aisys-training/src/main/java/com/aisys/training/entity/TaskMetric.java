package com.aisys.training.entity;

import java.time.Instant;

/** 任务指标实体（task_metric，不启用 RLS）。 */
public class TaskMetric {
    private Long id;
    private Long taskId;
    private Long tenantId;
    private Instant ts;
    private Long step;
    private String metrics;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public Long getStep() { return step; }
    public void setStep(Long step) { this.step = step; }
    public String getMetrics() { return metrics; }
    public void setMetrics(String metrics) { this.metrics = metrics; }
}
