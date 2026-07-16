package com.aisys.storage.entity;

import java.time.OffsetDateTime;

/** 上传任务（大文件分片上传：浏览器→后端→存储池；平台共享表）。 */
public class UploadTask {
    private Long id;
    private Long tenantId;
    private Long poolId;
    private String relativePath;
    private String fileName;
    private Long sizeBytes;
    private Long chunkSize;
    private Integer totalChunks;
    private Integer receivedChunks;
    private String status;       // UPLOADING / PROCESSING / COMPLETED / FAILED
    private String uploadId;     // S3 multipart uploadId
    private String bucket;
    private String finalKey;     // 组装完成后的全 key（含租户前缀）
    private String errorMsg;
    private String parts;        // JSON: [{partNumber,etag},...]
    private Long createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getPoolId() { return poolId; }
    public void setPoolId(Long poolId) { this.poolId = poolId; }
    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Long getChunkSize() { return chunkSize; }
    public void setChunkSize(Long chunkSize) { this.chunkSize = chunkSize; }
    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
    public Integer getReceivedChunks() { return receivedChunks; }
    public void setReceivedChunks(Integer receivedChunks) { this.receivedChunks = receivedChunks; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getFinalKey() { return finalKey; }
    public void setFinalKey(String finalKey) { this.finalKey = finalKey; }
    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }
    public String getParts() { return parts; }
    public void setParts(String parts) { this.parts = parts; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
