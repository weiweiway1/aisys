package com.aisys.dataset.entity;

import java.time.Instant;

/** 数据集版本（DDD 5.4）。 */
public class DatasetVersion {
    private Long id;
    private Long datasetId;
    private Long tenantId;
    private String version;
    private String storagePath;
    private Long fileSize;
    private String checksum;
    private Long rowCount;
    /** MyBatis 以 String 读写 JSONB；column_info 为 JSON 文本。 */
    private String columnInfo;
    private String status;     // creating/ready/failed
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    // —— 以下为 JOIN 父表 dataset 填充的瞬态字段（非 dataset_version 表列，不参与 insert/update）——
    private String datasetName;
    private String datasetDescription;
    private String taskType;     // 对应 dataset.task_type（image_classification/object_detection/time_series/...）
    private String format;       // 对应 dataset.format（jsonl/csv/json/parquet）
    private Long sampleCount;    // 对应 dataset.sample_count

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }
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
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public String getColumnInfo() { return columnInfo; }
    public void setColumnInfo(String columnInfo) { this.columnInfo = columnInfo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }
    public String getDatasetDescription() { return datasetDescription; }
    public void setDatasetDescription(String datasetDescription) { this.datasetDescription = datasetDescription; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public Long getSampleCount() { return sampleCount; }
    public void setSampleCount(Long sampleCount) { this.sampleCount = sampleCount; }
}
