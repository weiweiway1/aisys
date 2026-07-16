package com.aisys.notification;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 通知告警服务（DDD 5.9）。
 * <p>消费 notification.event / storage.event 生成站内通知；WebSocket 站内实时推送；
 * 提供通知列表/已读/未读数与通知规则管理 API。
 */
@SpringBootApplication(scanBasePackages = {"com.aisys.notification", "com.aisys.common"})
@MapperScan(basePackages = {
        "com.aisys.notification.mapper",
        "com.aisys.common.log.mapper",
        "com.aisys.common.mq.outbox"
})
@EnableDiscoveryClient
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
