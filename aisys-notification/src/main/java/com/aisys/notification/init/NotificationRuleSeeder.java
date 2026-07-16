package com.aisys.notification.init;

import com.aisys.notification.constant.NotificationConstants;
import com.aisys.notification.entity.NotificationRule;
import com.aisys.notification.mapper.NotificationRuleMapper;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认通知规则初始化（DDD 5.9）。幂等创建两条内置规则：
 * <ul>
 *   <li>TASK_COMPLETED → IN_APP，target=USER（消费时回退到事件携带的 userId=任务创建者）</li>
 *   <li>NODE_OFFLINE → IN_APP，target=TENANT（消费时回退到事件携带的 userId；生产环境可由 Feign 补全租户管理员）</li>
 * </ul>
 * 平台级规则（tenant_id = NULL），所有租户的事件均可命中。
 */
@Component
@Order(20)
public class NotificationRuleSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(NotificationRuleSeeder.class);

    private final NotificationRuleMapper ruleMapper;
    private final ObjectMapper objectMapper;

    public NotificationRuleSeeder(NotificationRuleMapper ruleMapper, ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        seedIfAbsent(NotificationConstants.TYPE_TASK_COMPLETED,
                NotificationConstants.TARGET_USER,
                List.of(),
                List.of(NotificationConstants.CHANNEL_IN_APP),
                null);
        seedIfAbsent(NotificationConstants.TYPE_NODE_OFFLINE,
                NotificationConstants.TARGET_TENANT,
                List.of(),
                List.of(NotificationConstants.CHANNEL_IN_APP),
                null);
        log.info("[NotificationRuleSeeder] 默认通知规则初始化完成");
    }

    private void seedIfAbsent(String eventType, String targetType, List<Long> targetIds,
                              List<String> channels, String webhookUrl) {
        try {
            long exists = ruleMapper.countByEventType(eventType);
            if (exists > 0) {
                log.debug("[Seeder] 规则已存在，跳过 type={}", eventType);
                return;
            }
            NotificationRule rule = new NotificationRule();
            rule.setTenantId(null); // 平台级
            rule.setEventType(eventType);
            rule.setTargetType(targetType);
            rule.setTargetIds(objectMapper.writeValueAsString(targetIds));
            rule.setChannels(objectMapper.writeValueAsString(channels));
            rule.setWebhookUrl(webhookUrl);
            rule.setEnabled(Boolean.TRUE);
            ruleMapper.insert(rule);
            log.info("[Seeder] 创建默认规则 id={} type={} targetType={}", rule.getId(), eventType, targetType);
        } catch (Exception e) {
            log.warn("[Seeder] 初始化规则失败 type={}: {}", eventType, e.getMessage());
        }
    }
}
