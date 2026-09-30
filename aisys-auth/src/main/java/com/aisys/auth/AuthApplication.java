package com.aisys.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.aisys.auth", "com.aisys.common"})
@MapperScan(basePackages = {"com.aisys.auth.mapper", "com.aisys.common.log.mapper"})
@EnableDiscoveryClient
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
