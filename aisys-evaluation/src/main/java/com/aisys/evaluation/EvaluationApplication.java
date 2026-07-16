package com.aisys.evaluation;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 评测服务（DDD 5.6）。负责 benchmark 管理、评测任务编排（多模型拆分）、
 * 通过 task.command 下发执行指令给 resource、消费 task.status 聚合评测结果。
 */
@SpringBootApplication(scanBasePackages = {"com.aisys.evaluation", "com.aisys.common"})
@MapperScan(basePackages = {"com.aisys.evaluation.mapper", "com.aisys.common.log.mapper", "com.aisys.common.mq.outbox"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.aisys.evaluation.client", "com.aisys.common.feign"})
public class EvaluationApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvaluationApplication.class, args);
    }
}
