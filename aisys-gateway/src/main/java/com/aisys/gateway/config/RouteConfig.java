package com.aisys.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关路由（Java DSL，DDD 5.1.2）。显式定义比 YAML 绑定更可靠（SC 2025.1 gateway-server-webflux）。
 * 各服务 compose 内网 host:port，端口约定见 [[aisys-project-status]]。
 */
@Configuration
public class RouteConfig {

    @Value("${AUTH_HOST:auth}") private String authHost;
    @Value("${AUTH_PORT:8001}") private int authPort;
    @Value("${MODEL_HOST:model}") private String modelHost;
    @Value("${MODEL_PORT:8002}") private int modelPort;
    @Value("${DATASET_HOST:dataset}") private String datasetHost;
    @Value("${DATASET_PORT:8003}") private int datasetPort;
    @Value("${TRAINING_HOST:training}") private String trainingHost;
    @Value("${TRAINING_PORT:8004}") private int trainingPort;
    @Value("${EVALUATION_HOST:evaluation}") private String evalHost;
    @Value("${EVALUATION_PORT:8005}") private int evalPort;
    @Value("${RESOURCE_HOST:resource}") private String resourceHost;
    @Value("${RESOURCE_PORT:8006}") private int resourcePort;
    @Value("${STORAGE_HOST:storage}") private String storageHost;
    @Value("${STORAGE_PORT:8007}") private int storagePort;
    @Value("${NOTIFICATION_HOST:notification}") private String notifHost;
    @Value("${NOTIFICATION_PORT:8008}") private int notifPort;
    @Value("${MONITOR_HOST:monitor}") private String monitorHost;
    @Value("${MONITOR_PORT:8009}") private int monitorPort;

    @Bean
    public RouteLocator aisysRoutes(RouteLocatorBuilder builder) {
        RouteLocatorBuilder.Builder b = builder.routes();
        b.route("auth-service", r -> r
                .path("/api/v1/auth/**", "/api/v1/users/**", "/api/v1/tenants/**", "/api/v1/roles/**")
                .uri("http://" + authHost + ":" + authPort));
        b.route("model-service", r -> r
                .path("/api/v1/models/**", "/api/v1/model-tags/**")
                .uri("http://" + modelHost + ":" + modelPort));
        b.route("dataset-service", r -> r
                .path("/api/v1/datasets/**")
                .uri("http://" + datasetHost + ":" + datasetPort));
        b.route("training-service", r -> r
                .path("/api/v1/training/**", "/ws/v1/training/**")
                .uri("http://" + trainingHost + ":" + trainingPort));
        b.route("evaluation-service", r -> r
                .path("/api/v1/benchmarks/**", "/api/v1/evaluation/**")
                .uri("http://" + evalHost + ":" + evalPort));
        b.route("resource-service", r -> r
                .path("/api/v1/resources/**", "/api/v1/agent/**", "/ws/v1/agent/**", "/ws/v1/resources/**")
                .uri("http://" + resourceHost + ":" + resourcePort));
        b.route("storage-service", r -> r
                .path("/api/v1/storage/**", "/api/v1/files/**")
                .uri("http://" + storageHost + ":" + storagePort));
        b.route("notification-service", r -> r
                .path("/api/v1/notifications/**", "/api/v1/notification-rules/**", "/ws/v1/notifications/**")
                .uri("http://" + notifHost + ":" + notifPort));
        b.route("monitor-service", r -> r
                .path("/api/v1/audit-logs/**")
                .uri("http://" + monitorHost + ":" + monitorPort));
        return b.build();
    }
}
