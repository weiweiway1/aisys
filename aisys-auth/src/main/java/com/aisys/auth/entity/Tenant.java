package com.aisys.auth.entity;

import java.time.Instant;

public class Tenant {
    private Long id;
    private String name;
    private String code;
    private String status;
    private Long storagePoolId;
    private Long maxQuotaBytes;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getStoragePoolId() { return storagePoolId; }
    public void setStoragePoolId(Long storagePoolId) { this.storagePoolId = storagePoolId; }
    public Long getMaxQuotaBytes() { return maxQuotaBytes; }
    public void setMaxQuotaBytes(Long maxQuotaBytes) { this.maxQuotaBytes = maxQuotaBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
