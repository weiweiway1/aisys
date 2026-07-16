package com.aisys.notification.service;

import com.aisys.common.core.context.UserContext;
import com.aisys.notification.constant.NotificationConstants;
import com.aisys.notification.entity.NotificationRecord;
import com.aisys.notification.entity.NotificationRule;
import com.aisys.notification.websocket.NotificationWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知分发器（DDD 5.9）。按规则的 channels 决定每条通知的投递方式：
 * <ul>
 *   <li>IN_APP：写入 notification_record（落库）+ 经 WebSocket 实时推送给在线用户</li>
 *   <li>EMAIL：日志模拟（暂不接邮件网关，记录到 info 日志）</li>
 *   <li>WEBHOOK：RestTemplate POST 到 rule.webhook_url</li>
 * </ul>
 * <p>所有渠道都先落库（保证可追溯），再异步尝试外部投递；外部投递失败仅记日志，不回滚。
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRecordService recordService;
    private final NotificationWebSocketHandler webSocketHandler;
    private final RestTemplate restTemplate;

    public NotificationDispatcher(NotificationRecordService recordService,
                                  NotificationWebSocketHandler webSocketHandler) {
        this.recordService = recordService;
        this.webSocketHandler = webSocketHandler;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 对单个目标用户分发一条通知。
     *
     * @param rule     命中的规则（决定 channels）
     * @param tenantId 租户（可空）
     * @param userId   目标用户 ID
     * @param type     通知类型
     * @param title    标题
     * @param content  正文
     * @param level    级别
     * @param refType  关联资源类型（可空）
     * @param refId    关联资源 ID（可空）
     */
    public void dispatch(NotificationRule rule, Long tenantId, Long userId,
                         String type, String title, String content, String level,
                         String refType, String refId) {
        List<String> channels = rule != null ? rule.getParsedChannels() : List.of(NotificationConstants.CHANNEL_IN_APP);

        boolean persist = channels.stream().anyMatch(ch ->
                NotificationConstants.CHANNEL_IN_APP.equalsIgnoreCase(ch)
                        || NotificationConstants.CHANNEL_EMAIL.equalsIgnoreCase(ch)
                        || NotificationConstants.CHANNEL_WEBHOOK.equalsIgnoreCase(ch));

        NotificationRecord saved = null;
        if (persist) {
            NotificationRecord rec = new NotificationRecord();
            rec.setTenantId(tenantId);
            rec.setUserId(userId);
            rec.setType(type);
            rec.setTitle(title);
            rec.setContent(content);
            rec.setLevel(level == null ? NotificationConstants.LEVEL_INFO : level);
            rec.setIsRead(false);
            rec.setRefType(refType);
            rec.setRefId(refId);
            saved = recordService.persist(rec);
        }

        for (String channel : channels) {
            try {
                switch (channel.toUpperCase()) {
                    case NotificationConstants.CHANNEL_IN_APP -> deliverInApp(saved, tenantId, userId, type, title, content, level);
                    case NotificationConstants.CHANNEL_EMAIL -> deliverEmail(userId, type, title, content);
                    case NotificationConstants.CHANNEL_WEBHOOK -> deliverWebhook(rule, tenantId, userId, type, title, content);
                    default -> log.warn("[Dispatcher] 未知渠道 {}，跳过", channel);
                }
            } catch (Exception e) {
                log.warn("[Dispatcher] 渠道 {} 投递失败 userId={}: {}", channel, userId, e.getMessage());
            }
        }
    }

    private void deliverInApp(NotificationRecord saved, Long tenantId, Long userId, String type,
                              String title, String content, String level) {
        if (userId == null) {
            log.debug("[Dispatcher] IN_APP 目标 userId 为空，仅落库");
            return;
        }
        Map<String, Object> push = new HashMap<>();
        push.put("type", "NOTIFICATION");
        // 用 HashMap（允许 null）而非 Map.of（null 值会 NPE）
        Map<String, Object> data = new HashMap<>();
        data.put("id", saved == null ? null : saved.getId());
        data.put("userId", userId);
        data.put("tenantId", tenantId);
        data.put("notificationType", type);
        data.put("title", title);
        data.put("content", content);
        data.put("level", level == null ? NotificationConstants.LEVEL_INFO : level);
        data.put("createdAt", saved == null ? System.currentTimeMillis() : saved.getCreatedAt());
        data.put("traceId", UserContext.getTraceId());
        push.put("data", data);
        boolean delivered = webSocketHandler.sendToUser(userId, push);
        log.info("[Dispatcher] IN_APP 推送 userId={} delivered={} recordId={}",
                userId, delivered, saved == null ? null : saved.getId());
    }

    private void deliverEmail(Long userId, String type, String title, String content) {
        // 暂无邮件网关，日志模拟
        log.info("[Dispatcher] EMAIL 模拟投递 userId={} type={} title={} content={}",
                userId, type, title, content);
    }

    private void deliverWebhook(NotificationRule rule, Long tenantId, Long userId,
                                String type, String title, String content) {
        String url = rule == null ? null : rule.getWebhookUrl();
        if (url == null || url.isBlank()) {
            log.debug("[Dispatcher] WEBHOOK 未配置 url，跳过 ruleId={}", rule == null ? null : rule.getId());
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "userId", userId == null ? "" : userId,
                "tenantId", tenantId == null ? "" : tenantId,
                "type", type,
                "title", title,
                "content", content == null ? "" : content,
                "timestamp", System.currentTimeMillis());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            var resp = restTemplate.postForEntity(url, entity, String.class);
            log.info("[Dispatcher] WEBHOOK 投递 url={} status={} ruleId={}",
                    url, resp.getStatusCode(), rule == null ? null : rule.getId());
        } catch (Exception e) {
            log.warn("[Dispatcher] WEBHOOK 调用失败 url={}: {}", url, e.getMessage());
        }
    }
}
