package com.aisys.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * 通知规则 DTO（DDD 5.9.1）。channels/target_ids 用 List 接收，由 Service 序列化为 JSONB 存库。
 */
public final class NotificationRuleDtos {

    private NotificationRuleDtos() {}

    /** 创建规则请求 */
    public record Create(
            @NotBlank(message = "event_type 不能为空") String eventType,
            @NotBlank(message = "target_type 不能为空") String targetType,
            /** 目标 ID 列表；USER/ROLE=userId/roleId 列表；TENANT=租户 ID 列表。可空（=全部匹配） */
            List<Long> targetIds,
            @NotEmpty(message = "channels 不能为空") List<String> channels,
            String webhookUrl,
            Boolean enabled) {}

    /** 更新规则请求（所有字段可选） */
    public record Update(
            String eventType,
            String targetType,
            List<Long> targetIds,
            List<String> channels,
            String webhookUrl,
            Boolean enabled) {}

    /** 列表查询参数 */
    public record Query(String eventType, Integer page, Integer size) {
        public int pageOrDefault() { return page == null || page < 1 ? 1 : page; }
        public int sizeOrDefault() { return size == null || size < 1 ? 20 : Math.min(size, 100); }
    }

    /** 规则响应 */
    public record Response(Long id, Long tenantId, String eventType, String targetType,
                           List<Long> targetIds, List<String> channels,
                           String webhookUrl, Boolean enabled, Instant createdAt) {
    }

    public static Response toResponse(com.aisys.notification.entity.NotificationRule r) {
        return new Response(r.getId(), r.getTenantId(), r.getEventType(), r.getTargetType(),
                r.getParsedTargetIds(), r.getParsedChannels(), r.getWebhookUrl(),
                r.getEnabled(), r.getCreatedAt());
    }
}
