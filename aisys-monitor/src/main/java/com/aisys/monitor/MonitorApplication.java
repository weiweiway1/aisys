package com.aisys.monitor;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 监控/审计服务（DDD 5.10）。
 * <p>轻量服务：拥有 audit_log（分区表，各服务经 common-log 写入，本服务只读）与 event_outbox 表。
 * 不消费 MQ、不承担 outbox 投递、无 WebSocket。暴露 /api/v1/audit-logs 查询与 /actuator/prometheus 指标。
 */
@SpringBootApplication(scanBasePackages = {"com.aisys.monitor", "com.aisys.common"})
@MapperScan(basePackages = {"com.aisys.monitor.mapper", "com.aisys.common.log.mapper"})
@EnableDiscoveryClient
public class MonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(MonitorApplication.class, args);
    }
}
