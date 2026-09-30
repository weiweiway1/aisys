package com.aisys.common.mq.message;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.util.UUID;

/**
 * 消息基类（DDD 4.3.2）。所有跨服务消息继承本类，序列化为 JSON 投递。
 */
public abstract class BaseMessage implements Serializable {

    private String messageId = UUID.randomUUID().toString().replace("-", "");
    private String idempotencyKey;
    private Long tenantId;
    private Long timestamp = System.currentTimeMillis();
    private String traceId;
    private Integer retryCount = 0;

    /**
     * 消息类型（由子类返回，便于消费方 dispatch）。
     * <p>显式以 {@code messageType} 为 JSON 字段名序列化（注解由子类覆盖继承）。
     * 否则像 NodeOfflineMessage / ModelLifecycleMessage 这样仅靠本方法暴露类型、
     * 没有 {@code type} 字段/getter 的消息，序列化结果不含任何类型字段，
     * 消费侧（notification IncomingEvent）读到的 messageType 为 null，事件被静默丢弃。
     */
    @JsonProperty("messageType")
    public abstract String messageType();

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
}
