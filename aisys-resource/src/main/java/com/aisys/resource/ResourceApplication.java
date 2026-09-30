package com.aisys.resource;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * aisys-resource 启动类（计算资源管理 + Agent 接入，服务号 600，端口 8006）。
 * <ul>
 *   <li>消费 task.command（TRAINING/EVALUATION）→ 资源调度 → 经 WebSocket 向 Agent 下发 run_task。</li>
 *   <li>生产 task.status / task.log / task.metrics（转发 Agent 上报）/ notification.event（NODE_OFFLINE）。</li>
 *   <li>节点调度分布式锁、状态心跳由 common-redis 提供。</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"com.aisys.resource", "com.aisys.common"})
@MapperScan(basePackages = {"com.aisys.resource.mapper", "com.aisys.common.mq.outbox"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.aisys.resource.client"})
@EnableScheduling
public class ResourceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ResourceApplication.class, args);
    }
}
