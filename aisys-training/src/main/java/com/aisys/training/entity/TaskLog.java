package com.aisys.training.entity;

import java.time.Instant;

/** 任务日志实体（task_log，不启用 RLS）。 */
public class TaskLog {
    private Long id;
    private Long taskId;
    private Long tenantId;
    private String level;
    private Long step;
    private String message;
    private Instant loggedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Long getStep() { return step; }
    public void setStep(Long step) { this.step = step; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getLoggedAt() { return loggedAt; }
    public void setLoggedAt(Instant loggedAt) { this.loggedAt = loggedAt; }
}
