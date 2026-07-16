package com.aisys.model;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * aisys-model（模型管理服务，服务号 200，端口 8002，DDD 5.3）。
 * <p>依赖：common-s3（分片上传）/ common-feign（调 storage）/ common-mq（生命周期事件）。
 */
@SpringBootApplication(scanBasePackages = {"com.aisys.model", "com.aisys.common"})
@MapperScan(basePackages = {
        "com.aisys.model.mapper",
        "com.aisys.common.log.mapper",
        "com.aisys.common.mq.outbox"
})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.aisys.model.client"})
public class ModelApplication {

    public static void main(String[] args) {
        SpringApplication.run(ModelApplication.class, args);
    }
}
