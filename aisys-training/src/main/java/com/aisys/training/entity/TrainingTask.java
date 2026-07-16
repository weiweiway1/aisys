package com.aisys.training.entity;

import java.time.Instant;

/** 训练任务实体（training_task，租户隔离）。 */
public class TrainingTask {
    private Long id;
    private Long tenantId;
    private Long projectId;
    private String name;
    private Long modelVersionId;
    private Long datasetVersionId;
    private String image;
    private String command;
    private String resourceSpec;   // JSONB → String（Mapper 用 StringType 透传）
    private String hyperparameters;
    private String containerSpec;  // JSONB：{imageName,imageTarRelPath,datasetRelPath,datasetFormat,taskMode}
    private String status;
    private Integer priority;
    private Long assignedNodeId;
    private Integer progress;
    private String errorMessage;
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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getModelVersionId() { return modelVersionId; }
    public void setModelVersionId(Long modelVersionId) { this.modelVersionId = modelVersionId; }
    public Long getDatasetVersionId() { return datasetVersionId; }
    public void setDatasetVersionId(Long datasetVersionId) { this.datasetVersionId = datasetVersionId; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getResourceSpec() { return resourceSpec; }
    public void setResourceSpec(String resourceSpec) { this.resourceSpec = resourceSpec; }
    public String getHyperparameters() { return hyperparameters; }
    public void setHyperparameters(String hyperparameters) { this.hyperparameters = hyperparameters; }
    public String getContainerSpec() { return containerSpec; }
    public void setContainerSpec(String containerSpec) { this.containerSpec = containerSpec; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Long getAssignedNodeId() { return assignedNodeId; }
    public void setAssignedNodeId(Long assignedNodeId) { this.assignedNodeId = assignedNodeId; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
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
