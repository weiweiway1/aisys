package com.aisys.notification.entity;

import java.time.Instant;
import java.util.List;

/**
 * 通知规则实体（DDD 5.9）。channels/target_ids 在 DB 中为 JSONB，由 Service 用 Jackson 序列化为字符串读写。
 * 不启用 RLS，业务层显式按 tenant_id 过滤。
 */
public class NotificationRule {

    private Long id;
    private Long tenantId;
    private String eventType;
    private String targetType;
    /** JSONB 原文（字符串）—— 由 Mapper 透传，由 Service 反序列化为 List<Long> */
    private String targetIds;
    /** JSONB 原文（字符串）—— 由 Mapper 透传，由 Service 反序列化为 List<String> */
    private String channels;
    private String webhookUrl;
    private Boolean enabled;
    private Instant createdAt;

    /** 临时字段（不持久化）：解码后的目标 ID 列表，供业务逻辑使用 */
    private transient List<Long> parsedTargetIds;
    /** 临时字段（不持久化）：解码后的渠道列表 */
    private transient List<String> parsedChannels;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public String getTargetIds() { return targetIds; }
    public void setTargetIds(String targetIds) { this.targetIds = targetIds; }

    public String getChannels() { return channels; }
    public void setChannels(String channels) { this.channels = channels; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public List<Long> getParsedTargetIds() { return parsedTargetIds; }
    public void setParsedTargetIds(List<Long> parsedTargetIds) { this.parsedTargetIds = parsedTargetIds; }

    public List<String> getParsedChannels() { return parsedChannels; }
    public void setParsedChannels(List<String> parsedChannels) { this.parsedChannels = parsedChannels; }
}
