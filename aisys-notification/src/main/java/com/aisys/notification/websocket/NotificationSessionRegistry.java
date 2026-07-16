package com.aisys.notification.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话注册表（DDD 5.9）。维护 userId → 当前活跃 WebSocket 会话集合，
 * 供站内通知推送时按用户精准下发。
 * <p>线程安全：userId 维度多会话（同用户多端登录）并存于 Set，推送时遍历全部下发。
 */
@Component
public class NotificationSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotificationSessionRegistry.class);

    /** userId → 会话集合（多端并发） */
    private final ConcurrentHashMap<Long, Set<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

    /** session → userId（断开时回查归属用户） */
    private final ConcurrentHashMap<String, Long> sessionUser = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        if (userId == null || session == null) return;
        userSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionUser.put(session.getId(), userId);
        log.debug("[WS-Registry] 注册 userId={} sessionId={} 当前在线会话数={}",
                userId, session.getId(), userSessions.get(userId).size());
    }

    public void unregister(WebSocketSession session) {
        if (session == null) return;
        Long userId = sessionUser.remove(session.getId());
        if (userId != null) {
            Set<WebSocketSession> sessions = userSessions.get(userId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    userSessions.remove(userId);
                }
            }
        }
        log.debug("[WS-Registry] 注销 sessionId={} userId={}", session.getId(), userId);
    }

    /** 获取某用户全部在线会话（可能为空集合，永不返回 null） */
    public Set<WebSocketSession> sessionsOf(Long userId) {
        if (userId == null) return Set.of();
        Set<WebSocketSession> s = userSessions.get(userId);
        return s == null ? Set.of() : s;
    }

    public int onlineCount() {
        return userSessions.size();
    }
}
