package com.aisys.training.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置（DDD 5.5.4）。
 * <p>训练任务/日志的实时推送可经 WebSocket 推给前端。当前阶段未注册具体 handler（前端默认走 REST 轮询），
 * 预留扩展点：后续注册 {@code /ws/training/{taskId}} 端点，由 task.log/task.metrics 消费结果转发至对应会话。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 预留：registry.addHandler(...).addPathPatterns("/ws/training/**").setAllowedOriginPatterns("*");
    }
}
