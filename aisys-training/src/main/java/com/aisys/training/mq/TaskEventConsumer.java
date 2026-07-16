package com.aisys.training.mq;

import com.aisys.training.config.TrainingMqConfig;
import com.aisys.training.service.impl.TrainingTaskServiceImpl;
import com.aisys.common.core.context.UserContext;
import com.aisys.common.mq.message.BaseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 训练任务事件消费者（DDD 5.5.1）。
 * <p>消费 task.status / task.log / task.metrics 三类事件，分别委托给 {@link TrainingTaskServiceImpl}
 * 的 handle* 方法（均在 @Transactional 内执行）。
 * <p>OutboxPublisher 投递的消息体为 UTF-8 JSON，content-type=application/json。
 * 为避免依赖特定 Jackson 版本的 MessageConverter API，这里接收原始 {@link Message}，
 * 用平台级 {@code tools.jackson.databind.ObjectMapper} 手动反序列化（Jackson 3）。
 */
@Component
public class TaskEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskEventConsumer.class);

    private final TrainingTaskServiceImpl taskService;
    private final ObjectMapper objectMapper;

    public TaskEventConsumer(TrainingTaskServiceImpl taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = TrainingMqConfig.QUEUE_STATUS)
    public void onStatus(Message message,
                         @Header(name = AmqpHeaders.RECEIVED_ROUTING_KEY, required = false) String routingKey) {
        TaskStatusEvent event = parse(message, TaskStatusEvent.class);
        if (event == null) return;
        log.debug("消费 task.status taskId={} status={} key={}", event.getTaskId(), event.getStatus(), routingKey);
        restoreContext(event);
        try {
            taskService.handleStatusEvent(event);
        } catch (Exception e) {
            log.error("处理 task.status 失败 taskId={}: {}", event.getTaskId(), e.getMessage(), e);
            throw e; // 触发重试/DLQ
        } finally {
            UserContext.clear();
        }
    }

    @RabbitListener(queues = TrainingMqConfig.QUEUE_LOG)
    public void onLog(Message message) {
        TaskLogEvent event = parse(message, TaskLogEvent.class);
        if (event == null) return;
        int n = event.getEntries() == null ? 0 : event.getEntries().size();
        log.debug("消费 task.log taskId={} entries={}", event.getTaskId(), n);
        restoreContext(event);
        try {
            taskService.handleLogEvent(event);
        } catch (Exception e) {
            log.error("处理 task.log 失败 taskId={}: {}", event.getTaskId(), e.getMessage(), e);
            throw e;
        } finally {
            UserContext.clear();
        }
    }

    @RabbitListener(queues = TrainingMqConfig.QUEUE_METRICS)
    public void onMetrics(Message message) {
        TaskMetricsEvent event = parse(message, TaskMetricsEvent.class);
        if (event == null) return;
        log.debug("消费 task.metrics taskId={} step={}", event.getTaskId(), event.getStep());
        restoreContext(event);
        try {
            taskService.handleMetricsEvent(event);
        } catch (Exception e) {
            log.error("处理 task.metrics 失败 taskId={}: {}", event.getTaskId(), e.getMessage(), e);
            throw e;
        } finally {
            UserContext.clear();
        }
    }

    /**
     * MQ 消费线程无 HTTP 请求上下文，HeaderAuthFilter 不会注入 UserContext；而 RLS 需要租户上下文才能
     * 正确执行 SET LOCAL app.tenant_id。这里从消息体携带的 tenantId/traceId 重建一个最小 UserContext，
     * 让下游 Service 的 @Transactional 内 RLS 生效（event 由生产端 EventPublisher 注入 tenantId）。
     */
    private void restoreContext(BaseMessage event) {
        if (event.getTenantId() == null) return;
        UserContext.set(new UserContext.CurrentUser(
                null, event.getTenantId(), List.of(), null, event.getTraceId(), false));
    }

    private <T> T parse(Message message, Class<T> type) {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, type);
        } catch (tools.jackson.core.JacksonException e) {
            log.error("反序列化消息失败 type={}: {}", type.getSimpleName(), e.getMessage());
            return null; // 丢弃坏消息（ nack 由 ack-mode=auto 决定；此处不抛出避免死循环）
        }
    }
}
