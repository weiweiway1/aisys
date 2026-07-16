package com.aisys.evaluation.config;

import com.aisys.common.core.constant.CommonConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评测服务 MQ 声明：消费 task.status 交换机的 EVALUATION 状态消息。
 * <p>exchange 由 common-mq 的 RabbitMqConfig 声明（@AutoConfiguration 注入 6 个 topic exchange）。
 * 消费方需自行声明 Queue + Binding（DDD 4.3.1）。
 */
@Configuration
public class EvaluationRabbitConfig {

    public static final String QUEUE_EVAL_STATUS = "q.evaluation.task.status";
    public static final String ROUTING_EVAL_STATUS = "task.status.evaluation";

    /** 消费队列（durable）。死信由全局策略处理，此处简化。 */
    @Bean
    public Queue evaluationStatusQueue() {
        return QueueBuilder.durable(QUEUE_EVAL_STATUS).build();
    }

    @Bean
    public Binding evaluationStatusBinding(TopicExchange taskStatusExchange) {
        return BindingBuilder.bind(evaluationStatusQueue())
                .to(taskStatusExchange)
                .with(ROUTING_EVAL_STATUS);
    }

    // taskStatusExchange bean 由 common-mq RabbitMqConfig 提供（bean name = taskStatusExchange）
    // 通过形参注入即可，无需在此重复声明。
    // 引用名常量便于上层发布/消费：
    // 生产 task.command：exchange = CommonConstants.EXCHANGE_TASK_COMMAND，routing = ROUTING_EVAL_COMMAND
    // 消费 task.status ：queue = QUEUE_EVAL_STATUS，binding routing = ROUTING_EVAL_STATUS
    public static final String ROUTING_EVAL_COMMAND = "task.command.eval";
}
