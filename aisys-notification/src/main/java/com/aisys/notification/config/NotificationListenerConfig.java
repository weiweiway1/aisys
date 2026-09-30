package com.aisys.notification.config;

import com.aisys.notification.mq.NotificationEventConsumer;
import com.aisys.notification.service.NotificationDispatcher;
import com.aisys.notification.service.NotificationRuleService;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 消费侧装配（DDD 5.9）。声明 Jackson 3 消息转换器 + 通用 ListenerContainerFactory，
 * 供 @RabbitListener 监听 notification.event / storage.event 队列时反序列化 JSON 载荷。
 * <p>Spring Boot 4.1 / Spring AMQP 4.x 已迁移到 Jackson 3（tools.jackson），使用 {@link JacksonJsonMessageConverter}
 * （Jackson2JsonMessageConverter 在 SB4.1 已弃用）。
 */
@Configuration
public class NotificationListenerConfig {

    @Bean
    public MessageConverter jacksonMessageConverter() {
        // 无参构造：内部 JsonMapper + trusted packages = *（所有包）
        return new JacksonJsonMessageConverter();
    }

    @Bean(name = "notificationListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory notificationListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jacksonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonMessageConverter);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(4);
        factory.setPrefetchCount(10);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    /**
     * 消费 Bean：监听 notification.event / storage.event 两个队列。
     * 单独声明为 Bean 以便显式注入 dispatcher/ruleService（避免 @RabbitListener 方法分散在多个类）。
     */
    @Bean
    public NotificationEventConsumer notificationEventConsumer(NotificationDispatcher dispatcher,
                                                               NotificationRuleService ruleService) {
        return new NotificationEventConsumer(dispatcher, ruleService);
    }
}
