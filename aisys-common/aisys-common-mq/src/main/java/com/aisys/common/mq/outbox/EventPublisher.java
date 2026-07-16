package com.aisys.common.mq.outbox;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.mq.message.BaseMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 事件发布器（Outbox 模式入口，DDD 4.3.1）。
 * <p>在业务事务内调用：仅向本地 event_outbox 表写入（pending），由 {@link OutboxPublisher} 异步投递到 RabbitMQ，
 * 从而保证"更新 DB + 发消息"的一致性（不依赖分布式事务）。
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final String producer;

    @Autowired
    public EventPublisher(OutboxMapper outboxMapper, ObjectMapper objectMapper,
                          @Value("${spring.application.name:unknown}") String producer) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.producer = producer;
    }

    public void publish(BaseMessage message, String topic, String aggregateType, String aggregateId) {
        publish(message, topic, aggregateId == null ? "" : aggregateId, aggregateType, aggregateId);
    }

    public void publish(BaseMessage message, String topic, String routingKey,
                        String aggregateType, String aggregateId) {
        // 注入链路上下文
        message.setTenantId(UserContext.getTenantId());
        message.setTraceId(UserContext.getTraceId());
        if (message.getIdempotencyKey() == null) {
            message.setIdempotencyKey(aggregateType + ":" + aggregateId + ":" + message.messageType());
        }
        try {
            String payload = objectMapper.writeValueAsString(message);
            OutboxRecord rec = new OutboxRecord();
            rec.setProducer(producer);
            rec.setAggregateType(aggregateType);
            rec.setAggregateId(aggregateId);
            rec.setTopic(topic);
            rec.setRoutingKey(routingKey == null ? "" : routingKey);
            rec.setPayload(payload);
            rec.setTenantId(message.getTenantId());
            outboxMapper.insert(rec);
            log.debug("Outbox 事件入队 producer={} topic={} type={}", producer, topic, message.messageType());
        } catch (JacksonException e) {
            throw new IllegalStateException("序列化消息失败: " + message.messageType(), e);
        }
    }
}
