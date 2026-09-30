package com.aisys.notification.service;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.notification.constant.NotificationErrorCode;
import com.aisys.notification.dto.NotificationDtos;
import com.aisys.notification.entity.NotificationRecord;
import com.aisys.notification.mapper.NotificationRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 通知记录服务（DDD 5.9.1）。所有按 user_id 的查询/操作都以当前登录用户为准；
 * 平台超管可指定 tenantId 查看租户范围。表未启用 RLS，这里显式过滤。
 */
@Service
public class NotificationRecordService {

    private final NotificationRecordMapper recordMapper;

    public NotificationRecordService(NotificationRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
    }

    @Transactional(readOnly = true)
    public PageResult<NotificationDtos.Response> list(NotificationDtos.Query query) {
        Long userId = UserContext.getUserId();
        Long tenantId = UserContext.isPlatformAdmin() ? null : UserContext.getTenantId();
        int page = query.pageOrDefault();
        int size = query.sizeOrDefault();
        int offset = Math.max(0, (page - 1) * size);

        long total = recordMapper.count(userId, tenantId, query.isRead(), query.type());
        List<NotificationRecord> records = recordMapper.page(userId, tenantId, query.isRead(),
                query.type(), offset, size);
        return PageResult.of(NotificationDtos.toResponses(records), total, page, size);
    }

    @Transactional
    public void markRead(Long id) {
        Long userId = UserContext.getUserId();
        NotificationRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }
        // 仅本人可标记自己的通知已读（平台超管放宽：可标记本租户内任意）
        if (userId != null && record.getUserId() != null && !userId.equals(record.getUserId())
                && !UserContext.isPlatformAdmin()) {
            throw new BusinessException(NotificationErrorCode.NO_PERMISSION);
        }
        // 作用于本人通知：用当前用户 ID 作为过滤条件（保证只能改自己名下记录）
        Long filterUserId = UserContext.isPlatformAdmin() ? record.getUserId() : userId;
        recordMapper.markRead(id, filterUserId);
    }

    @Transactional
    public long markAllRead() {
        Long userId = UserContext.getUserId();
        return recordMapper.markAllRead(userId);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount() {
        Long userId = UserContext.getUserId();
        return recordMapper.countUnread(userId);
    }

    /**
     * 持久化通知（MQ 消费链路调用）。dispatcher 调用，无当前请求用户上下文，直接以传入实体落库。
     */
    @Transactional
    public NotificationRecord persist(NotificationRecord record) {
        recordMapper.insert(record);
        return record;
    }
}
