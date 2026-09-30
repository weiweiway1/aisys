package com.aisys.storage.entity;

import java.time.OffsetDateTime;

/**
 * 存储池实体（平台共享表 storage_pool，不启用 RLS；DDD 5.8）。
 * <p>普通 class + getter/setter（不使用 Lombok）。
 */
public class StoragePool {

    private Long id;
    private String name;
    private String type;
    private String endpoint;
    private String bucket;
    private String accessKey;
    private String secretKey;
    private Long quotaBytes;
    private Long usedBytes;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public Long getQuotaBytes() { return quotaBytes; }
    public void setQuotaBytes(Long quotaBytes) { this.quotaBytes = quotaBytes; }

    public Long getUsedBytes() { return usedBytes; }
    public void setUsedBytes(Long usedBytes) { this.usedBytes = usedBytes; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
