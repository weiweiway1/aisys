package com.aisys.model.entity;

import java.time.Instant;

/**
 * 模型版本实体（表 model_version，RLS 租户隔离）。
 */
public class ModelVersion {

    private Long id;
    private Long modelId;
    private Long tenantId;
    private String version;
    private String storagePath;
    private Long fileSize;
    private String checksum;
    private String status;
    private String config;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    // —— 以下为 JOIN 父表 model 填充的瞬态字段（非 model_version 表列，不参与 insert/update）——
    private String modelName;
    private String modelDescription;
    private String taskType;      // 对应 model.type（image_classification/object_detection/time_series/...）
    private String framework;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getModelDescription() { return modelDescription; }
    public void setModelDescription(String modelDescription) { this.modelDescription = modelDescription; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getFramework() { return framework; }
    public void setFramework(String framework) { this.framework = framework; }
}
