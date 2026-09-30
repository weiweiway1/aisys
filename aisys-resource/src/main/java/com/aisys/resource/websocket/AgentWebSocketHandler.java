package com.aisys.resource.websocket;

import com.aisys.resource.constant.ResourceConstants;
import com.aisys.resource.dto.WebSocketMessage;
import com.aisys.resource.mq.ForwardService;
import com.aisys.resource.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 长连接 WebSocket 处理器（DDD 5.7.1）。
 * <ul>
 *   <li>连接表：{@code ConcurrentHashMap<agentId, WebSocketSession>}，供调度器下发 run_task。</li>
 *   <li>首帧 agentToken 鉴权：连接建立后第一条文本消息必须是 {type:"auth", agentId, agentToken}，
 *       校验 Redis 中 agent:token:{agentId} == agentToken；通过前不处理其它消息。</li>
 *   <li>Agent 上报 status/log/metrics/result → 交 {@link ForwardService} 转发到对应 exchange。</li>
 *   <li>连接关闭 → 更新节点状态为 offline（heartbeat 仍可能在）。</li>
 * </ul>
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    /** agentId → WebSocketSession。调度器据此下发指令。 */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    /** sessionId → agentId（已通过鉴权） */
    private final Map<String, String> sessionAgent = new ConcurrentHashMap<>();
    /** commandId → 待回填的远程命令结果（shell_command 同步等待） */
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingCommands = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final ForwardService forwardService;
    private final AgentService agentService;

    public AgentWebSocketHandler(ObjectMapper objectMapper,
                                 StringRedisTemplate redis,
                                 ForwardService forwardService,
                                 AgentService agentService) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.forwardService = forwardService;
        this.agentService = agentService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 连接建立 sessionId={} remote={}", session.getId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        WebSocketMessage msg;
        try {
            msg = objectMapper.readValue(payload, WebSocketMessage.class);
        } catch (Exception e) {
            log.warn("无法解析 WebSocket 消息 sessionId={}: {}", session.getId(), e.getMessage());
            return;
        }

        // 未鉴权：只处理首帧 auth
        if (!sessionAgent.containsKey(session.getId())) {
            handleAuth(session, msg);
            return;
        }

        String agentId = sessionAgent.get(session.getId());
        try {
            switch (msg.type() == null ? "" : msg.type()) {
                case ResourceConstants.REPORT_STATUS -> forwardService.forwardStatus(agentId, msg);
                case ResourceConstants.REPORT_LOG    -> forwardService.forwardLog(agentId, msg);
                case ResourceConstants.REPORT_METRICS-> forwardService.forwardMetrics(agentId, msg);
                case ResourceConstants.REPORT_RESULT -> forwardService.forwardResult(agentId, msg);
                case ResourceConstants.REPORT_SHELL_RESULT -> completeShellResult(msg);
                case ResourceConstants.CMD_PING      -> send(session, pongMessage());
                default -> log.debug("Agent 上报未知消息类型 type={} agentId={}", msg.type(), agentId);
            }
        } catch (Exception e) {
            log.error("处理 Agent 上报失败 agentId={}: {}", agentId, e.getMessage(), e);
        }
    }

    private void handleAuth(WebSocketSession session, WebSocketMessage msg) {
        Map<String, Object> data = msg.data() == null ? Map.of() : msg.data();
        String agentId = msg.agentId() != null ? msg.agentId() : (String) data.get("agentId");
        String token = data.get("agentToken") != null ? String.valueOf(data.get("agentToken")) : null;
        if (agentId == null || token == null) {
            log.warn("WebSocket 首帧缺少 agentId/agentToken，关闭 sessionId={}", session.getId());
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        String stored = redis.opsForValue().get(ResourceConstants.REDIS_KEY_AGENT_TOKEN_PREFIX + agentId);
        if (stored == null || !stored.equals(token)) {
            log.warn("WebSocket agentToken 校验失败 agentId={}", agentId);
            close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        // 鉴权通过
        sessionAgent.put(session.getId(), agentId);
        sessions.put(agentId, session);
        // 上线节点状态
        agentService.markOnlineByAgentId(agentId);
        log.info("Agent WebSocket 鉴权通过 agentId={}", agentId);
        send(session, ackMessage("auth_ok"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String agentId = sessionAgent.remove(session.getId());
        if (agentId != null) {
            sessions.remove(agentId);
            log.info("Agent WebSocket 连接关闭 agentId={} status={}", agentId, status);
            // 连接断开不立刻置 offline（心跳/定时任务兜底），仅清理 session
        }
    }

    /** 调度器/Service 调用：向 Agent 下发 JSON 指令（同步发送）。 */
    public boolean sendCommand(String agentId, Object command) {
        WebSocketSession session = sessions.get(agentId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            String json = objectMapper.writeValueAsString(command);
            session.sendMessage(new TextMessage(json));
            return true;
        } catch (IOException e) {
            log.warn("下发 WebSocket 指令失败 agentId={}: {}", agentId, e.getMessage());
            return false;
        }
    }

    /**
     * 在 Agent 上执行 shell 命令并同步等待结果（带超时）。
     * Agent 未连接/离线或超时时返回带错误信息的结构化结果（不抛异常，便于前端展示）。
     */
    public Map<String, Object> sendShellCommandAndWait(String agentId, String command, int timeoutSec) {
        WebSocketSession session = sessions.get(agentId);
        if (session == null || !session.isOpen()) {
            return errorResult("Agent 未连接（节点离线）", false);
        }
        int timeout = Math.max(1, Math.min(timeoutSec, 300));
        String commandId = java.util.UUID.randomUUID().toString().replace("-", "");
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingCommands.put(commandId, future);
        Map<String, Object> data = new HashMap<>();
        data.put("commandId", commandId);
        data.put("command", command);
        data.put("timeout", timeout);
        WebSocketMessage shell = WebSocketMessage.of(ResourceConstants.CMD_SHELL, null, null, null, data);
        try {
            send(session, shell);
        } catch (Exception e) {
            pendingCommands.remove(commandId);
            return errorResult("下发命令失败: " + e.getMessage(), false);
        }
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            pendingCommands.remove(commandId);
            return errorResult("命令执行超时", true);
        } catch (Exception e) {
            pendingCommands.remove(commandId);
            return errorResult("等待结果失败: " + e.getMessage(), false);
        }
    }

    /** 回填 shell_result，唤醒等待中的命令请求。 */
    private void completeShellResult(WebSocketMessage msg) {
        Map<String, Object> data = msg.data() == null ? Map.of() : msg.data();
        Object cid = data.get("commandId");
        if (cid == null) return;
        CompletableFuture<Map<String, Object>> f = pendingCommands.remove(String.valueOf(cid));
        if (f != null) f.complete(data);
    }

    private static Map<String, Object> errorResult(String stderr, boolean timedOut) {
        Map<String, Object> r = new HashMap<>();
        r.put("exitCode", -1);
        r.put("stdout", "");
        r.put("stderr", stderr);
        r.put("timedOut", timedOut);
        return r;
    }

    /** 当前在线（已建链）的 agentId 列表。 */
    public List<String> connectedAgents() {
        return List.copyOf(sessions.keySet());
    }

    private void send(WebSocketSession session, WebSocketMessage msg) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (IOException e) {
            log.warn("发送 WebSocket 消息失败 sessionId={}: {}", session.getId(), e.getMessage());
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        try { session.close(status); } catch (IOException ignored) {}
    }

    private WebSocketMessage pongMessage() {
        Map<String, Object> data = new HashMap<>();
        data.put("serverTime", System.currentTimeMillis());
        return new WebSocketMessage("pong", null, null, null, null, null, null,
                System.currentTimeMillis(), null, data);
    }

    private WebSocketMessage ackMessage(String ack) {
        Map<String, Object> data = new HashMap<>();
        data.put("ack", ack);
        return new WebSocketMessage(ack, null, null, null, null, null, null,
                System.currentTimeMillis(), null, data);
    }
}
