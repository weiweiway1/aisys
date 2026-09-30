package com.aisys.notification.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.notification.dto.NotificationDtos;
import com.aisys.notification.service.NotificationRecordService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知记录接口（DDD 5.9.1）。
 * <ul>
 *   <li>GET    /api/v1/notifications          分页查询当前用户通知（可按 isRead/type 过滤）</li>
 *   <li>PUT    /api/v1/notifications/{id}/read 单条标记已读</li>
 *   <li>PUT    /api/v1/notifications/read-all  全部标记已读</li>
 *   <li>GET    /api/v1/notifications/unread-count 未读数</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationRecordService recordService;

    public NotificationController(NotificationRecordService recordService) {
        this.recordService = recordService;
    }

    @GetMapping
    public ApiResponse<PageResult<NotificationDtos.Response>> list(
            @RequestParam(required = false) Boolean isRead,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        NotificationDtos.Query query = new NotificationDtos.Query(isRead, type, page, size);
        return ApiResponse.success(recordService.list(query));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        recordService.markRead(id);
        return ApiResponse.success();
    }

    @PutMapping("/read-all")
    public ApiResponse<NotificationDtos.MarkAllResult> markAllRead() {
        long updated = recordService.markAllRead();
        return ApiResponse.success(new NotificationDtos.MarkAllResult(updated));
    }

    @GetMapping("/unread-count")
    public ApiResponse<NotificationDtos.UnreadCount> unreadCount() {
        return ApiResponse.success(new NotificationDtos.UnreadCount(recordService.getUnreadCount()));
    }
}
