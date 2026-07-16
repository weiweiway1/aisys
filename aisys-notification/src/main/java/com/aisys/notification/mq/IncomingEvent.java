package com.aisys.notification.mq;

import java.util.Map;

/**
 * 消费侧通用事件载荷（DDD 5.9）。来自其它服务投递到 notification.event / storage.event 的事件，
 * 结构不固定，统一以 Map 承接，由消费者按需取字段。
 * <p>关键约定字段：
 * <ul>
 *   <li>type：事件类型（如 TASK_COMPLETED / NODE_OFFLINE / STORAGE_*），用于匹配 notification_rule.event_type</li>
 *   <li>tenantId：租户 ID</li>
 *   <li>userId：触发用户（可空）</li>
 *   <li>title / content / level：可选的预置标题/正文/级别</li>
 *   <li>refType / refId：关联资源</li>
 * </ul>
 */
public class IncomingEvent {

    /** 事件类型：从 payload 的 type 字段反序列化（生产侧 NotificationEvent.type） */
    private String type;
    private Long tenantId;
    private Long userId;
    /** 生产侧设置的接收用户（与 userId 互为别名，consumer 优先用 userId，空则用 recipientUserId） */
    private Long recipientUserId;
    private String title;
    private String content;
    private String level;
    private String refType;
    private String refId;
    /** 原始负载（用于 WEBHOOK 透传与扩展字段读取） */
    private Map<String, Object> payload;

    /** 事件类型（兼容 messageType 旧字段名，优先 type） */
    public String getMessageType() {
        return type;
    }
    public void setMessageType(String messageType) { this.type = messageType; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getRecipientUserId() { return recipientUserId; }
    public void setRecipientUserId(Long recipientUserId) { this.recipientUserId = recipientUserId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }

    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
