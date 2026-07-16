package com.aisys.common.log.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 审计/调度异步装配：开启 @Async 与 @Scheduled，提供审计日志专用线程池。
 * 集中扫描 common.log.mapper，使引入 common-log 的服务自动注册 AuditLogMapper（无需各服务声明）。
 */
@AutoConfiguration
@EnableAsync
@EnableScheduling
@MapperScan(basePackages = "com.aisys.common.log.mapper")
public class AuditLogAutoConfiguration {

    @Bean(name = "auditLogExecutor")
    public Executor auditLogExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("audit-log-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        exec.initialize();
        return exec;
    }
}
