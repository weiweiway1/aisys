package com.aisys.notification.config;

import com.aisys.notification.constant.NotificationConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Notification 消费侧队列/绑定声明（DDD 4.3.1 / 5.9）。
 * <p>Exchange 由 common-mq 的 {@code RabbitMqConfig} 声明（bean 名 notificationEventExchange / storageEventExchange，
 * 对应常量 notification.event / storage.event）。本服务声明自己的消费队列并绑定：
 * <ul>
 *   <li>q.notification.event ← notification.# （task.status / 平台业务事件触发通知）</li>
 *   <li>q.notification.storage ← storage.# （存储事件触发通知）</li>
 * </ul>
 */
@Configuration
public class NotificationMqConfig {

    @Bean
    public Queue notificationEventQueue() {
        return QueueBuilder.durable(NotificationConstants.QUEUE_NOTIFICATION_EVENT).build();
    }

    @Bean
    public Binding notificationEventBinding(TopicExchange notificationEventExchange) {
        return BindingBuilder.bind(notificationEventQueue())
                .to(notificationEventExchange)
                .with(NotificationConstants.ROUTING_NOTIFICATION_EVENT);
    }

    @Bean
    public Queue storageEventQueue() {
        return QueueBuilder.durable(NotificationConstants.QUEUE_STORAGE_EVENT).build();
    }

    @Bean
    public Binding storageEventBinding(TopicExchange storageEventExchange) {
        return BindingBuilder.bind(storageEventQueue())
                .to(storageEventExchange)
                .with(NotificationConstants.ROUTING_STORAGE_EVENT);
    }
}
