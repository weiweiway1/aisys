package com.aisys.notification.dto;

import java.time.Instant;
import java.util.List;

/**
 * 通知记录 DTO（DDD 5.9.1）。
 */
public final class NotificationDtos {

    private NotificationDtos() {}

    /** 列表查询参数 */
    public record Query(Boolean isRead, String type, Integer page, Integer size) {
        public int pageOrDefault() { return page == null || page < 1 ? 1 : page; }
        public int sizeOrDefault() { return size == null || size < 1 ? 20 : Math.min(size, 100); }
    }

    /** 通知响应 */
    public record Response(Long id, Long tenantId, Long userId, String type, String title,
                           String content, String level, Boolean isRead,
                           String refType, String refId, Instant createdAt) {}

    /** 未读数 */
    public record UnreadCount(long count) {}

    /** 批量标记已读结果 */
    public record MarkAllResult(long updated) {}

    public static List<Response> toResponses(List<com.aisys.notification.entity.NotificationRecord> records) {
        if (records == null) return List.of();
        return records.stream().map(NotificationDtos::toResponse).toList();
    }

    public static Response toResponse(com.aisys.notification.entity.NotificationRecord r) {
        return new Response(r.getId(), r.getTenantId(), r.getUserId(), r.getType(), r.getTitle(),
                r.getContent(), r.getLevel(), r.getIsRead(), r.getRefType(), r.getRefId(),
                r.getCreatedAt());
    }
}
