package com.aisys.notification.entity;

import java.time.Instant;

/**
 * 通知记录实体（DDD 5.9）。tenant_id 可空 → 不启用 RLS，Mapper 显式按 user_id/tenant_id 过滤。
 */
public class NotificationRecord {

    private Long id;
    private Long tenantId;
    private Long userId;
    private String type;
    private String title;
    private String content;
    private String level;
    private Boolean isRead;
    private String refType;
    private String refId;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public Boolean getIsRead() { return isRead; }
    public void setIsRead(Boolean isRead) { this.isRead = isRead; }

    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }

    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
