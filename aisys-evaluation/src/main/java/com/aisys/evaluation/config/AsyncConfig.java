package com.aisys.evaluation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置。
 * <p>主要用于评测报告的 LLM 异步生成（不阻塞评测完成的主流程）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 报告生成专用线程池：核心2，最大4，队列100。
     * LLM 调用耗时较长（秒级~分钟级），不宜占用太多线程。
     */
    @Bean("evaluationReportExecutor")
    Executor evaluationReportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("report-gen-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
