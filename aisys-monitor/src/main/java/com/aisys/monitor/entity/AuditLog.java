package com.aisys.monitor.entity;

import java.time.Instant;

/**
 * 审计日志实体（对应 audit_log 表，分区表）。
 * <p>本服务只读查询；写入由各服务 common-log 切面经共享 AuditLogMapper 完成。
 * detail 为 JSONB，读取时以 text 形式映射为 String（原始 JSON 文本）。
 */
public class AuditLog {

    private Long id;
    private Long tenantId;
    private String actorType;     // USER / AGENT / SYSTEM
    private Long userId;
    private String username;
    private String action;
    private String resource;
    private String resourceId;
    private String detail;        // JSONB -> text
    private String ipAddress;     // INET -> text
    private String userAgent;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getActorType() { return actorType; }
    public void setActorType(String actorType) { this.actorType = actorType; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }

    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
