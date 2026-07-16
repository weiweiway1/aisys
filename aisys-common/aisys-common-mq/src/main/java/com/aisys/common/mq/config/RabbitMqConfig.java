package com.aisys.common.mq.config;

import com.aisys.common.core.constant.CommonConstants;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * RabbitMQ 标准交换机声明（DDD 4.3.1）。声明 6 个 topic 交换机；
 * 具体业务队列（含死信绑定）由各消费服务自行声明并绑定。
 * 集中扫描 common.mq.outbox，使引入 common-mq 的服务自动注册 OutboxMapper。
 */
@AutoConfiguration
@ConditionalOnClass(TopicExchange.class)
@MapperScan(basePackages = "com.aisys.common.mq.outbox")
public class RabbitMqConfig {

    private TopicExchange topic(String name) {
        return new TopicExchange(name, true, false);
    }

    @Bean public TopicExchange taskCommandExchange() { return topic(CommonConstants.EXCHANGE_TASK_COMMAND); }
    @Bean public TopicExchange taskStatusExchange() { return topic(CommonConstants.EXCHANGE_TASK_STATUS); }
    @Bean public TopicExchange taskLogExchange() { return topic(CommonConstants.EXCHANGE_TASK_LOG); }
    @Bean public TopicExchange taskMetricsExchange() { return topic(CommonConstants.EXCHANGE_TASK_METRICS); }
    @Bean public TopicExchange notificationEventExchange() { return topic(CommonConstants.EXCHANGE_NOTIFICATION_EVENT); }
    @Bean public TopicExchange storageEventExchange() { return topic(CommonConstants.EXCHANGE_STORAGE_EVENT); }
}
