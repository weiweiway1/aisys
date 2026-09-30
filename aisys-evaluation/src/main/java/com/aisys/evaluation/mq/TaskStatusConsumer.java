package com.aisys.evaluation.mq;

import com.aisys.evaluation.service.EvaluationTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * task.status 消费者：处理 resource 回写的评测状态。
 * <p>仅处理 taskType=EVALUATION 的消息（其余类型忽略）。状态处理委托 {@link EvaluationTaskService}，
 * 后者负责子任务状态更新、父任务聚合、结果写入（全部在 @Transactional 内）。
 */
@Component
public class TaskStatusConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskStatusConsumer.class);

    private final EvaluationTaskService evaluationTaskService;

    public TaskStatusConsumer(EvaluationTaskService evaluationTaskService) {
        this.evaluationTaskService = evaluationTaskService;
    }

    @RabbitListener(queues = "${eval.mq.status-queue:q.evaluation.task.status}")
    public void onMessage(TaskStatusMessage message) {
        if (message == null) {
            log.warn("[TaskStatusConsumer] 收到空消息，忽略");
            return;
        }
        if (message.getTaskType() == null
                || !com.aisys.evaluation.constant.EvaluationConstants.TASK_TYPE_EVALUATION.equalsIgnoreCase(message.getTaskType())) {
            // 非评测类型消息，忽略（at-least-once，幂等忽略）
            log.debug("[TaskStatusConsumer] 非评测状态消息 taskType={}，忽略", message.getTaskType());
            return;
        }
        log.info("[TaskStatusConsumer] 收到评测状态 subtaskId={} status={} taskId={}",
                message.getSubtaskId(), message.getStatus(), message.getTaskId());
        try {
            evaluationTaskService.handleSubtaskStatus(message);
        } catch (Exception e) {
            // 抛出会让 RabbitMQ nack 重投；此处仅记录，避免脏消息无限重试阻塞队列
            // （at-least-once 语义下，消费方应保证幂等；此处吞掉异常以保护队列吞吐）
            log.error("[TaskStatusConsumer] 处理评测状态失败 subtaskId={}: {}",
                    message.getSubtaskId(), e.getMessage(), e);
        }
    }
}
