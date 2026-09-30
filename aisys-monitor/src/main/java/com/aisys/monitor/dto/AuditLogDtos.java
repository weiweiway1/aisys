package com.aisys.monitor.dto;

import java.time.Instant;

/**
 * 审计日志相关 DTO。
 * <p>Response 为返回前端的记录视图；detail 以原始 JSON 字符串透传（前端自行解析）。
 */
public final class AuditLogDtos {

    private AuditLogDtos() {}

    /** 审计日志查询条件（绑定 GET 查询参数）。 */
    public record Query(
            String actorType,
            String resource,
            String action,
            Instant startTime,
            Instant endTime,
            Integer page,
            Integer size
    ) {}

    /** 审计日志响应视图。 */
    public record Response(
            Long id,
            Long tenantId,
            String actorType,
            Long userId,
            String username,
            String action,
            String resource,
            String resourceId,
            String detail,
            String ipAddress,
            String userAgent,
            Instant createdAt
    ) {}
}
