package com.aisys.dataset;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 数据集服务（DDD 5.4）。
 * <p>负责数据集元数据、版本管理、预览（S3 流式读取前 N 行）、统计。
 * 通过 Feign 调用 aisys-storage 落盘版本数据；通过 common-s3 直读预览。
 */
@SpringBootApplication(scanBasePackages = {"com.aisys.dataset", "com.aisys.common"})
@MapperScan(basePackages = {"com.aisys.dataset.mapper", "com.aisys.common.log.mapper"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = {"com.aisys.dataset", "com.aisys.common"})
public class DatasetApplication {
    public static void main(String[] args) {
        SpringApplication.run(DatasetApplication.class, args);
    }
}
