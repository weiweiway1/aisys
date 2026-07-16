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
}
