package com.aisys.notification.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.notification.dto.NotificationRuleDtos;
import com.aisys.notification.service.NotificationRuleService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知规则接口（DDD 5.9.1）。
 * <ul>
 *   <li>GET    /api/v1/notification-rules       分页查询</li>
 *   <li>GET    /api/v1/notification-rules/{id}  详情</li>
 *   <li>POST   /api/v1/notification-rules       创建（审计 create_rule）</li>
 *   <li>PUT    /api/v1/notification-rules/{id}  更新（审计 update_rule）</li>
 *   <li>DELETE /api/v1/notification-rules/{id}  删除（审计 delete_rule）</li>
 * </ul>
 * 仅 TENANT_ADMIN / PLATFORM_ADMIN 可管理规则。
 */
@RestController
@RequestMapping("/api/v1/notification-rules")
public class NotificationRuleController {

    private final NotificationRuleService ruleService;

    public NotificationRuleController(NotificationRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    public ApiResponse<PageResult<NotificationRuleDtos.Response>> list(
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        NotificationRuleDtos.Query query = new NotificationRuleDtos.Query(eventType, page, size);
        return ApiResponse.success(ruleService.list(query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    public ApiResponse<NotificationRuleDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.success(ruleService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "create_rule", resource = "NOTIFICATION_RULE",
            description = "创建通知规则", resourceId = "#result.data.id")
    public ApiResponse<NotificationRuleDtos.Response> create(@Valid @RequestBody NotificationRuleDtos.Create req) {
        return ApiResponse.success(ruleService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "update_rule", resource = "NOTIFICATION_RULE",
            description = "更新通知规则", resourceId = "#id")
    public ApiResponse<NotificationRuleDtos.Response> update(@PathVariable Long id,
                                                             @Valid @RequestBody NotificationRuleDtos.Update req) {
        return ApiResponse.success(ruleService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "delete_rule", resource = "NOTIFICATION_RULE",
            description = "删除通知规则", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ruleService.delete(id);
        return ApiResponse.success();
    }
}
