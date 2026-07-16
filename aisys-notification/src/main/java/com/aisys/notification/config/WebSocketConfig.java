package com.aisys.notification.config;

import com.aisys.common.core.jwt.JwtUtil;
import com.aisys.notification.constant.NotificationConstants;
import com.aisys.notification.websocket.NotificationSessionRegistry;
import com.aisys.notification.websocket.NotificationWebSocketHandler;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配（DDD 5.9）。原生 WebSocket（非 STOMP）：注册 /ws/v1/notifications/stream 端点，
 * 由 {@link NotificationWebSocketHandler} 处理首帧 accessToken 鉴权与站内推送。
 * <p>跨域：允许所有来源（实际由网关统一控制 CORS）；WebSocket 不受 SecurityFilterChain 的 HTTP 路由约束。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final JwtUtil jwtUtil;
    private final NotificationSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public WebSocketConfig(JwtUtil jwtUtil, NotificationSessionRegistry registry, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    @Bean
    public NotificationWebSocketHandler notificationWebSocketHandler() {
        return new NotificationWebSocketHandler(jwtUtil, registry, objectMapper);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(notificationWebSocketHandler(), NotificationConstants.WS_PATH)
                .setAllowedOriginPatterns("*");
    }
}
