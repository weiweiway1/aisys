package com.aisys.monitor.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.monitor.dto.AuditLogDtos;
import com.aisys.monitor.service.AuditLogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 审计日志查询接口（DDD 5.10.2）。
 * <p>仅平台超管可访问。支持按 actorType / resource / action / 时间范围 过滤分页查询。
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @AuditLog(action = "QUERY", resource = "AUDIT_LOG", description = "查询审计日志")
    public ApiResponse<PageResult<AuditLogDtos.Response>> list(
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant startTime,
            @RequestParam(required = false) Instant endTime,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        AuditLogDtos.Query query = new AuditLogDtos.Query(actorType, resource, action,
                startTime, endTime, page, size);
        return ApiResponse.success(auditLogService.list(query));
    }
}
