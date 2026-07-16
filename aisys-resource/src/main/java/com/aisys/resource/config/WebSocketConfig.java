package com.aisys.resource.config;

import com.aisys.resource.websocket.AgentWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 装配（DDD 5.7.1）。注册 Agent 长连接端点 /ws/v1/agent/connect。
 * <p>鉴权：首帧 agentToken（与 6.3 一致，token 不走 URL）。AgentWebSocketHandler 在 afterConnectionEstablished
 * 校验首帧，校验通过前不接收其它消息。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;

    public WebSocketConfig(AgentWebSocketHandler agentWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/v1/agent/connect")
                .setAllowedOriginPatterns("*");
    }
}
