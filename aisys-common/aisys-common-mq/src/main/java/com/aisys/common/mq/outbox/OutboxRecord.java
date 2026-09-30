package com.aisys.common.mq.outbox;

import java.time.Instant;

/**
 * Outbox 记录（DDD 4.3.1，表 event_outbox 由 monitor 建表，各生产服务写入）。
 * 业务事务内 INSERT（status=pending），由本服务 @Scheduled 轮询投递到 MQ 后置 status=sent。
 */
public class OutboxRecord {

    private Long id;
    private String producer;
    private String aggregateType;
    private String aggregateId;
    private String topic;
    private String routingKey;
    private String payload;      // JSON
    private Long tenantId;
    private String status;       // pending / sent
    private Instant createdAt;
    private Instant sentAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProducer() { return producer; }
    public void setProducer(String producer) { this.producer = producer; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
