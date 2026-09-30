package com.aisys.notification.service;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.notification.constant.NotificationConstants;
import com.aisys.notification.constant.NotificationErrorCode;
import com.aisys.notification.dto.NotificationRuleDtos;
import com.aisys.notification.entity.NotificationRule;
import com.aisys.notification.mapper.NotificationRuleMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 通知规则服务（DDD 5.9.1）。表未启用 RLS，这里显式按 tenant_id 过滤。
 * channels / target_ids 在 DB 为 JSONB，这里用 Jackson 在 List 与 JSON 字符串间转换。
 */
@Service
public class NotificationRuleService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRuleService.class);

    private static final Set<String> VALID_TARGET_TYPES = Set.of(
            NotificationConstants.TARGET_USER,
            NotificationConstants.TARGET_ROLE,
            NotificationConstants.TARGET_TENANT);
    private static final Set<String> VALID_CHANNELS = Set.of(
            NotificationConstants.CHANNEL_IN_APP,
            NotificationConstants.CHANNEL_EMAIL,
            NotificationConstants.CHANNEL_WEBHOOK);

    private final NotificationRuleMapper ruleMapper;
    private final ObjectMapper objectMapper;

    public NotificationRuleService(NotificationRuleMapper ruleMapper, ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationRuleDtos.Response> list(NotificationRuleDtos.Query query) {
        Long tenantId = currentTenantId();
        int page = query.pageOrDefault();
        int size = query.sizeOrDefault();
        int offset = Math.max(0, (page - 1) * size);
        long total = ruleMapper.count(tenantId, query.eventType());
        List<NotificationRule> rules = ruleMapper.page(tenantId, query.eventType(), offset, size);
        List<NotificationRuleDtos.Response> items = rules.stream()
                .map(this::decode)
                .map(NotificationRuleDtos::toResponse)
                .toList();
        return PageResult.of(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public NotificationRuleDtos.Response get(Long id) {
        NotificationRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(NotificationErrorCode.RULE_NOT_FOUND);
        }
        ensureTenantScope(rule);
        return NotificationRuleDtos.toResponse(decode(rule));
    }

    @Transactional
    public NotificationRuleDtos.Response create(NotificationRuleDtos.Create req) {
        validateTargetType(req.targetType());
        validateChannels(req.channels());
        if (NotificationConstants.CHANNEL_WEBHOOK.equals(firstWebhook(req.channels()))
                && (req.webhookUrl() == null || req.webhookUrl().isBlank())) {
            throw new BusinessException(NotificationErrorCode.RULE_CHANNELS_EMPTY,
                    "WEBHOOK 渠道必须配置 webhook_url");
        }

        NotificationRule rule = new NotificationRule();
        rule.setTenantId(currentTenantId());
        rule.setEventType(req.eventType());
        rule.setTargetType(req.targetType());
        rule.setTargetIds(encode(req.targetIds()));
        rule.setChannels(encode(req.channels()));
        rule.setWebhookUrl(req.webhookUrl());
        rule.setEnabled(req.enabled() == null ? Boolean.TRUE : req.enabled());
        ruleMapper.insert(rule);
        log.info("[Rule] 创建规则 id={} tenantId={} eventType={}", rule.getId(), rule.getTenantId(), rule.getEventType());
        return NotificationRuleDtos.toResponse(decode(rule));
    }

    @Transactional
    public NotificationRuleDtos.Response update(Long id, NotificationRuleDtos.Update req) {
        NotificationRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(NotificationErrorCode.RULE_NOT_FOUND);
        }
        ensureTenantScope(rule);

        if (req.eventType() != null) rule.setEventType(req.eventType());
        if (req.targetType() != null) {
            validateTargetType(req.targetType());
            rule.setTargetType(req.targetType());
        }
        if (req.targetIds() != null) rule.setTargetIds(encode(req.targetIds()));
        if (req.channels() != null) {
            validateChannels(req.channels());
            rule.setChannels(encode(req.channels()));
        }
        if (req.webhookUrl() != null) rule.setWebhookUrl(req.webhookUrl());
        if (req.enabled() != null) rule.setEnabled(req.enabled());
        ruleMapper.update(rule);
        return NotificationRuleDtos.toResponse(decode(rule));
    }

    @Transactional
    public void delete(Long id) {
        NotificationRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(NotificationErrorCode.RULE_NOT_FOUND);
        }
        ensureTenantScope(rule);
        ruleMapper.deleteById(id, currentTenantId());
    }

    /** MQ 消费链路：按 event_type 拉取启用的规则并解码 channels/target_ids（按租户隔离） */
    @Transactional(readOnly = true)
    public List<NotificationRule> findEnabledRules(String eventType, Long tenantId) {
        List<NotificationRule> rules = ruleMapper.selectEnabledByEventType(eventType, tenantId);
        rules.forEach(this::decode);
        return rules;
    }

    // ---------- 内部工具 ----------

    private void decodeQuiet(NotificationRule rule) {
        if (rule.getChannels() != null) {
            try {
                rule.setParsedChannels(objectMapper.readValue(rule.getChannels(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            } catch (JacksonException e) {
                log.warn("[Rule] channels 解码失败 id={}: {}", rule.getId(), e.getMessage());
            }
        }
        if (rule.getTargetIds() != null) {
            try {
                rule.setParsedTargetIds(objectMapper.readValue(rule.getTargetIds(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class)));
            } catch (JacksonException e) {
                log.warn("[Rule] target_ids 解码失败 id={}: {}", rule.getId(), e.getMessage());
            }
        }
    }

    private NotificationRule decode(NotificationRule rule) {
        decodeQuiet(rule);
        return rule;
    }

    private String encode(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BusinessException(NotificationErrorCode.BAD_REQUEST, "JSON 序列化失败: " + e.getOriginalMessage());
        }
    }

    private void validateTargetType(String targetType) {
        if (!VALID_TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(NotificationErrorCode.RULE_TARGET_INVALID);
        }
    }

    private void validateChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            throw new BusinessException(NotificationErrorCode.RULE_CHANNELS_EMPTY);
        }
        for (String ch : channels) {
            if (!VALID_CHANNELS.contains(ch.toUpperCase())) {
                throw new BusinessException(NotificationErrorCode.RULE_CHANNELS_EMPTY,
                        "未知渠道: " + ch);
            }
        }
    }

    private String firstWebhook(List<String> channels) {
        if (channels == null) return null;
        return channels.stream().filter(NotificationConstants.CHANNEL_WEBHOOK::equalsIgnoreCase).findFirst().orElse(null);
    }

    private Long currentTenantId() {
        // 平台超管无租户（可建平台级规则 tenant_id=null）；租户用户绑定本租户
        return UserContext.isPlatformAdmin() ? null : UserContext.getTenantId();
    }

    private void ensureTenantScope(NotificationRule rule) {
        if (UserContext.isPlatformAdmin()) return;
        Long current = UserContext.getTenantId();
        if (rule.getTenantId() != null && !rule.getTenantId().equals(current)) {
            throw new BusinessException(NotificationErrorCode.NO_PERMISSION);
        }
    }
}
