package com.aisys.training.config;

import com.aisys.common.core.constant.CommonConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 训练服务消费端队列声明（DDD 5.5.1）。
 * <p>common-mq 的 RabbitMqConfig 已声明 6 个 topic 交换机；本类声明 training 消费所需的队列并绑定到
 * 对应交换机，路由键采用 {@code task.#} 匹配该任务域下全部事件。
 * <ul>
 *   <li>task.status  → queue.training.task.status （路由键 task.#）</li>
 *   <li>task.log     → queue.training.task.log    （路由键 task.#）</li>
 *   <li>task.metrics → queue.training.task.metrics（路由键 task.#）</li>
 * </ul>
 */
@Configuration
public class TrainingMqConfig {

    public static final String QUEUE_STATUS = "queue.training.task.status";
    public static final String QUEUE_LOG = "queue.training.task.log";
    public static final String QUEUE_METRICS = "queue.training.task.metrics";

    private Queue durable(String name) {
        return QueueBuilder.durable(name).build();
    }

    // ---- status ----
    @Bean public Queue taskStatusQueue() { return durable(QUEUE_STATUS); }
    @Bean public Binding taskStatusBinding(Queue taskStatusQueue, TopicExchange taskStatusExchange) {
        return BindingBuilder.bind(taskStatusQueue).to(taskStatusExchange).with("task.status.training");
    }

    // ---- log ----
    @Bean public Queue taskLogQueue() { return durable(QUEUE_LOG); }
    @Bean public Binding taskLogBinding(Queue taskLogQueue, TopicExchange taskLogExchange) {
        return BindingBuilder.bind(taskLogQueue).to(taskLogExchange).with("task.log.training");
    }

    // ---- metrics ----
    @Bean public Queue taskMetricsQueue() { return durable(QUEUE_METRICS); }
    @Bean public Binding taskMetricsBinding(Queue taskMetricsQueue, TopicExchange taskMetricsExchange) {
        return BindingBuilder.bind(taskMetricsQueue).to(taskMetricsExchange).with("task.metrics.training");
    }

    /** 显式声明引用常量（避免 IDE 报未使用；保留语义）。 */
    @SuppressWarnings("unused")
    private static final String[] EXCHANGES = {
            CommonConstants.EXCHANGE_TASK_STATUS,
            CommonConstants.EXCHANGE_TASK_LOG,
            CommonConstants.EXCHANGE_TASK_METRICS,
            CommonConstants.EXCHANGE_TASK_COMMAND,
            CommonConstants.EXCHANGE_NOTIFICATION_EVENT
    };
}
