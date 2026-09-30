package com.aisys.dataset.entity;

import java.time.Instant;

/** 数据集元数据（DDD 5.4）。 */
public class Dataset {
    private Long id;
    private Long tenantId;
    private Long projectId;
    private String name;
    private String type;       // text/image/audio/video/multimodal
    private String format;     // jsonl/csv/json/parquet
    private String taskType;   // image_classification/object_detection/time_series/text
    private Long storagePoolId;
    private Long sampleCount;
    private String tags;       // JSON 数组字符串
    private String description;
    private String license;
    private String status;     // active/archived/deleted
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
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public Long getStoragePoolId() { return storagePoolId; }
    public void setStoragePoolId(Long storagePoolId) { this.storagePoolId = storagePoolId; }
    public Long getSampleCount() { return sampleCount; }
    public void setSampleCount(Long sampleCount) { this.sampleCount = sampleCount; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLicense() { return license; }
    public void setLicense(String license) { this.license = license; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
