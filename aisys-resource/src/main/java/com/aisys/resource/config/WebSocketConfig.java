package com.aisys.resource.config;

import com.aisys.resource.websocket.AgentWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

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

    /**
     * WS 文本消息缓冲上限提到 64KB（默认 8KB 过小，Agent 转发的 log/result 帧一旦含较长 message
     * 或 metrics map，整条 WS 消息超 8KB 会被服务端 close 1009，后续状态帧丢失、checkpoint 不写）。
     */
    @Bean
    public ServletServerContainerFactoryBean servletServerContainerFactoryBean() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        factory.setMaxTextMessageBufferSize(64 * 1024);
        factory.setMaxBinaryMessageBufferSize(64 * 1024);
        return factory;
    }
}
