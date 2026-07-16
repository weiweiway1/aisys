package com.aisys.notification.websocket;

import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.jwt.JwtUtil;
import com.aisys.notification.constant.NotificationConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 通知 WebSocket 处理器（DDD 5.9）。
 * <p>鉴权策略：握手放行（WebSocketConfig 注册端点时 setAllowedOriginPatterns("*")，握手期无 HTTP body 难以拿 token），
 * 首帧要求 JSON 携带 accessToken。解析校验成功后建立 userId ↔ session 映射；
 * 后续站内通知由 NotificationDispatcher 直接向 session 写消息。
 */
public class NotificationWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationWebSocketHandler.class);

    private final JwtUtil jwtUtil;
    private final NotificationSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public NotificationWebSocketHandler(JwtUtil jwtUtil, NotificationSessionRegistry registry,
                                        ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    // ---------- TextWebSocketHandler ----------

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        if (!session.getAttributes().containsKey("authed")) {
            // 首帧鉴权
            String payload = message.getPayload();
            String token = extractToken(payload);
            try {
                JwtUtil.ParsedToken parsed = jwtUtil.parse(token);
                if (!parsed.isAccessToken()) {
                    close(session, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                Long userId = parsed.userId();
                Long tenantId = parsed.tenantId();
                List<String> roles = parsed.roles();
                boolean platformAdmin = roles != null && roles.contains(CommonConstants.ROLE_PLATFORM_ADMIN);

                session.getAttributes().put("authed", Boolean.TRUE);
                session.getAttributes().put("userId", userId);
                session.getAttributes().put("tenantId", tenantId);

                // 注入 UserContext 与 SecurityContext（本 WS 线程内可用）
                UserContext.set(new UserContext.CurrentUser(userId, tenantId,
                        roles == null ? List.of() : roles, null, null, platformAdmin));
                var authorities = (roles == null ? List.<String>of() : roles)
                        .stream().map(SimpleGrantedAuthority::new).toList();
                org.springframework.security.core.context.SecurityContextHolder.getContext()
                        .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, authorities));

                registry.register(userId, session);
                log.info("[WS] 用户 {} 建立通知连接 sessionId={}", userId, session.getId());
                try {
                    session.sendMessage(new TextMessage("{\"type\":\"AUTHED\"}"));
                } catch (Exception e) {
                    log.warn("[WS] 发送 AUTHED 失败: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.warn("[WS] 鉴权失败: {}", e.getMessage());
                try {
                    session.sendMessage(new TextMessage("{\"type\":\"AUTH_ERROR\"}"));
                } catch (Exception ignored) {
                    // ignore
                }
                close(session, CloseStatus.POLICY_VIOLATION);
            } finally {
                UserContext.clear();
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
            }
            return;
        }
        // 已鉴权后的客户端消息：通知服务端只推送，忽略客户端上行（保留 ping/pong 心跳）
        log.debug("[WS] 收到客户端消息（忽略）sessionId={} len={}", session.getId(), message.getPayloadLength());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("[WS] 连接已建立，等待首帧鉴权 sessionId={}", session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[WS] 传输错误 sessionId={}: {}", session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.unregister(session);
        UserContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        log.info("[WS] 连接关闭 sessionId={} status={}", session.getId(), status);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /** 推送一条 JSON 通知给指定用户（由 dispatcher 调用） */
    public boolean sendToUser(Long userId, Object payload) {
        var sessions = registry.sessionsOf(userId);
        if (sessions.isEmpty()) {
            return false;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            log.warn("[WS] 序列化推送消息失败: {}", e.getMessage());
            return false;
        }
        TextMessage msg = new TextMessage(json);
        int sent = 0;
        for (WebSocketSession s : sessions) {
            if (!s.isOpen()) continue;
            try {
                synchronized (s) {
                    s.sendMessage(msg);
                }
                sent++;
            } catch (Exception e) {
                log.warn("[WS] 推送失败 userId={} session={}: {}", userId, s.getId(), e.getMessage());
            }
        }
        return sent > 0;
    }

    @SuppressWarnings("unchecked")
    private String extractToken(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            Map<String, Object> map = objectMapper.readValue(payload, Map.class);
            Object v = map.get(NotificationConstants.WS_AUTH_FIELD);
            return v == null ? null : String.valueOf(v).trim();
        } catch (JacksonException e) {
            // 首帧可能直接是裸 token 字符串
            String trimmed = payload.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // ignore
        }
    }
}
