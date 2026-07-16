package com.aisys.notification.mq;

import com.aisys.notification.constant.NotificationConstants;
import com.aisys.notification.entity.NotificationRule;
import com.aisys.notification.service.NotificationDispatcher;
import com.aisys.notification.service.NotificationRuleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知事件消费者（DDD 5.9）。
 * <p>监听 notification.event / storage.event 队列：解析事件 → 按 messageType 匹配 notification_rule →
 * 解析目标用户 → 经 {@link NotificationDispatcher} 按 channels 分发。
 * <p>不抛异常（吞掉）以保证 MQ 消息 ACK；分发失败仅记日志，避免毒丸无限重投（消费侧幂等由通知记录落库保证）。
 */
public class NotificationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventConsumer.class);

    private final NotificationDispatcher dispatcher;
    private final NotificationRuleService ruleService;

    public NotificationEventConsumer(NotificationDispatcher dispatcher, NotificationRuleService ruleService) {
        this.dispatcher = dispatcher;
        this.ruleService = ruleService;
    }

    @RabbitListener(queues = NotificationConstants.QUEUE_NOTIFICATION_EVENT,
            containerFactory = "notificationListenerContainerFactory")
    public void onNotificationEvent(IncomingEvent event) {
        handle("notification", event);
    }

    @RabbitListener(queues = NotificationConstants.QUEUE_STORAGE_EVENT,
            containerFactory = "notificationListenerContainerFactory")
    public void onStorageEvent(IncomingEvent event) {
        handle("storage", event);
    }

    private void handle(String source, IncomingEvent event) {
        if (event == null) {
            log.warn("[Consumer] 收到空事件 source={}", source);
            return;
        }
        String eventType = event.getMessageType();
        if (eventType == null || eventType.isBlank()) {
            log.warn("[Consumer] 事件缺少 messageType，丢弃 source={} event={}", source, event);
            return;
        }
        log.info("[Consumer] 收到事件 source={} type={} tenantId={} userId={}",
                source, eventType, event.getTenantId(), event.getUserId());

        try {
            List<NotificationRule> rules = ruleService.findEnabledRules(eventType, event.getTenantId());
            if (rules.isEmpty()) {
                log.debug("[Consumer] 无匹配规则 type={}", eventType);
                return;
            }
            for (NotificationRule rule : rules) {
                List<Long> targetUsers = resolveTargetUsers(rule, event);
                if (targetUsers.isEmpty()) {
                    log.debug("[Consumer] 规则 {} 无目标用户 type={}", rule.getId(), eventType);
                    continue;
                }
                String title = firstNonBlank(event.getTitle(), defaultTitle(eventType));
                String content = firstNonBlank(event.getContent(), defaultContent(eventType));
                String level = firstNonBlank(event.getLevel(), defaultLevel(eventType));
                for (Long userId : targetUsers) {
                    dispatcher.dispatch(rule, event.getTenantId(), userId, eventType, title, content, level,
                            event.getRefType(), event.getRefId());
                }
            }
        } catch (Exception e) {
            log.error("[Consumer] 处理事件失败 source={} type={}: {}", source, eventType, e.getMessage(), e);
        }
    }

    /**
     * 解析目标用户。
     * <ul>
     *   <li>USER → target_ids（即 userId 列表）；为空时回退到事件携带的 userId</li>
     *   <li>TENANT → 当前无法跨服务查租户管理员列表（避免引入对 auth 的强依赖），回退到事件 userId</li>
     *   <li>ROLE → 同 TENANT，回退到事件 userId</li>
     * </ul>
     * 注：NODE_OFFLINE→通知租户管理员 的默认规则，在缺少跨服务用户查询时，回退到事件发起者，
     * 生产环境可通过 Feign 查询补全（见 NotificationUserResolver，当前留扩展点）。
     */
    private List<Long> resolveTargetUsers(NotificationRule rule, IncomingEvent event) {
        List<Long> ids = rule.getParsedTargetIds();
        String targetType = rule.getTargetType();

        if (NotificationConstants.TARGET_USER.equals(targetType) && ids != null && !ids.isEmpty()) {
            return ids;
        }
        // 无显式 USER 列表或为 TENANT/ROLE：回退到事件携带 userId / recipientUserId（保证至少一人收到）
        List<Long> fallback = new ArrayList<>();
        Long uid = event.getUserId() != null ? event.getUserId() : event.getRecipientUserId();
        if (uid != null) {
            fallback.add(uid);
        }
        return fallback;
    }

    private String defaultTitle(String eventType) {
        return switch (eventType) {
            case NotificationConstants.TYPE_TASK_COMPLETED -> "任务已完成";
            case NotificationConstants.TYPE_NODE_OFFLINE -> "节点离线告警";
            default -> "系统通知";
        };
    }

    private String defaultContent(String eventType) {
        return switch (eventType) {
            case NotificationConstants.TYPE_TASK_COMPLETED -> "您的任务已完成，请查看结果。";
            case NotificationConstants.TYPE_NODE_OFFLINE -> "检测到节点离线，请及时处理。";
            default -> "您有一条新的通知。";
        };
    }

    private String defaultLevel(String eventType) {
        return switch (eventType) {
            case NotificationConstants.TYPE_TASK_COMPLETED -> NotificationConstants.LEVEL_SUCCESS;
            case NotificationConstants.TYPE_NODE_OFFLINE -> NotificationConstants.LEVEL_ERROR;
            default -> NotificationConstants.LEVEL_INFO;
        };
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
