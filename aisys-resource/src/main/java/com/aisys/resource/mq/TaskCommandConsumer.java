package com.aisys.resource.mq;

import com.aisys.resource.config.ResourceRabbitConfig;
import com.aisys.resource.dto.TaskDtos.TaskCommandMessage;
import com.aisys.resource.service.scheduler.SchedulingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * task.command 消费者（DDD 5.7.3）。
 * <p>收到 TRAINING/EVALUATION 任务命令 → 调度分配 → 下发 run_task。
 * 消费在 @Transactional 内（租户作用域 DB 访问需事务包裹；调度聚合走平台共享表）。
 */
@Component
public class TaskCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskCommandConsumer.class);

    private final SchedulingService schedulingService;
    private final ObjectMapper objectMapper;

    public TaskCommandConsumer(SchedulingService schedulingService, ObjectMapper objectMapper) {
        this.schedulingService = schedulingService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = ResourceRabbitConfig.QUEUE_TASK_COMMAND, concurrency = "2-4")
    @Transactional
    public void onTaskCommand(String payload) {
        TaskCommandMessage command;
        try {
            command = objectMapper.readValue(payload, TaskCommandMessage.class);
        } catch (Exception e) {
            log.error("无法解析 task.command 消息: {}", e.getMessage(), e);
            return;
        }
        if (command.taskType() == null || command.taskId() == null) {
            log.warn("task.command 缺少 taskType/taskId，丢弃");
            return;
        }
        log.info("消费 task.command taskType={} taskId={}", command.taskType(), command.taskId());
        Long nodeId = schedulingService.scheduleAndDispatch(command);
        if (nodeId == null) {
            // 无可用节点：交由调用方（training/evaluation）通过重试/失败机制处理
            log.warn("调度失败（无可用节点）taskType={} taskId={}", command.taskType(), command.taskId());
        }
    }
}
