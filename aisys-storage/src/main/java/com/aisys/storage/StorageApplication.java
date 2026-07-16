package com.aisys.storage;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 存储池服务（DDD 5.8 / 服务号 700 / 端口 8007）。
 * <p>职责：存储池（SeaweedFS bucket）管理、文件预签名 URL、秒传、内部上传协议（供 model/dataset 调用）。
 * <p>核心约束：所有文件路径服务端强制拼租户前缀 {@code {tenantId}/...}，禁止路径穿越；平台超管可绕过。
 */
@SpringBootApplication(scanBasePackages = {"com.aisys.storage", "com.aisys.common"})
@MapperScan(basePackages = {
        "com.aisys.storage.mapper",
        "com.aisys.common.log.mapper",
        "com.aisys.common.mq.outbox"
})
@EnableDiscoveryClient
public class StorageApplication {
    public static void main(String[] args) {
        SpringApplication.run(StorageApplication.class, args);
    }
}
