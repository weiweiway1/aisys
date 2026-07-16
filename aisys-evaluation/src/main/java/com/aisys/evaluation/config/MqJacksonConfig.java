package com.aisys.evaluation.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 消息转换器：JSON。消费 @RabbitListener 据此将 payload 反序列化为 {@code TaskStatusMessage}。
 * 使用默认的 Jackson2JsonMessageConverter（基于 classmapper 自动推断目标类型）。
 */
@Configuration
public class MqJacksonConfig {

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
