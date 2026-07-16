package com.aisys.common.mq.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Outbox 投递器（DDD 4.3.1）。每个生产服务以 @Scheduled 轮询自己 producer 名下 pending 记录，
 * 投递到 RabbitMQ 后置 sent。消费方按 idempotencyKey 去重（at-least-once）。
 * <p>每条记录在独立的 REQUIRES_NEW 事务内 send + markSent：单条失败只影响自身（保持 pending 下轮重试），
 * 不会回滚整批、不会因「毒丸行」永久阻塞后续记录、也不会重复投递本批已成功的记录。
 */
@Component
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(name = "aisys.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;
    private final String producer;
    private final TransactionTemplate requiresNewTx;

    @Autowired
    public OutboxPublisher(OutboxMapper outboxMapper, RabbitTemplate rabbitTemplate,
                           @Value("${spring.application.name:unknown}") String producer,
                           PlatformTransactionManager transactionManager) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.producer = producer;
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = t;
    }

    @Scheduled(fixedDelayString = "${aisys.outbox.poll-interval-ms:3000}")
    public void poll() {
        List<OutboxRecord> records;
        try {
            records = outboxMapper.selectPendingForUpdate(producer, 100);
        } catch (Exception e) {
            log.warn("Outbox 轮询查询失败: {}", e.getMessage());
            return;
        }
        if (records == null || records.isEmpty()) return;
        for (OutboxRecord r : records) {
            try {
                // 每条记录独立事务：send + markSent 原子提交；失败仅回滚这一条，不影响其它记录
                requiresNewTx.executeWithoutResult(status -> {
                    rabbitTemplate.send(r.getTopic(), r.getRoutingKey(),
                            org.springframework.amqp.core.MessageBuilder
                                    .withBody(r.getPayload().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                    .setContentType("application/json").build());
                    outboxMapper.markSent(r.getId());
                });
            } catch (Exception e) {
                // 保持 pending，下轮重试（at-least-once）；不抛出，避免毒丸行阻塞后续记录
                log.warn("Outbox 投递失败 id={} topic={}: {}", r.getId(), r.getTopic(), e.getMessage());
            }
        }
    }
}
