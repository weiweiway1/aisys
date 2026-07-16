package com.aisys.training;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 训练任务服务（服务号 400，端口 8004）。
 * <p>负责训练任务生命周期管理、模板管理、checkpoint 管理；生产 task.command 事件供 Resource 消费调度，
 * 消费 task.status / task.log / task.metrics 事件更新任务状态与遥测数据（DDD 5.5）。
 */
@SpringBootApplication(scanBasePackages = {"com.aisys.training", "com.aisys.common"})
@MapperScan(basePackages = {
        "com.aisys.training.mapper",
        "com.aisys.common.log.mapper",
        "com.aisys.common.mq.outbox"
})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.aisys.training.client"})
public class TrainingApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrainingApplication.class, args);
    }
}
