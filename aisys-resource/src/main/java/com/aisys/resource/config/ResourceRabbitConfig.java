package com.aisys.resource.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resource 服务 RabbitMQ 队列/绑定声明（DDD 4.3.1）。
 * <p>消费：task.command → 队列 resource.task.command（routing key task.command.#）。
 * 生产（task.status/log/metrics、notification.event）只依赖 common-mq 声明的 exchange，无需在此声明队列。
 * taskCommandExchange bean 由 common-mq 的 RabbitMqConfig 提供，这里仅注入引用做绑定。
 */
@Configuration
public class ResourceRabbitConfig {

    public static final String QUEUE_TASK_COMMAND = "resource.task.command";

    /** 消费队列（持久化，绑定 task.command exchange） */
    @Bean
    public Queue taskCommandQueue() {
        return QueueBuilder.durable(QUEUE_TASK_COMMAND).build();
    }

    @Bean
    public Binding taskCommandBinding(TopicExchange taskCommandExchange) {
        return BindingBuilder.bind(taskCommandQueue())
                .to(taskCommandExchange)
                .with("task.command.#");
    }
}
