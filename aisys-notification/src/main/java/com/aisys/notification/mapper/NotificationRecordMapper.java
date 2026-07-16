package com.aisys.notification.mapper;

import com.aisys.notification.entity.NotificationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 通知记录 Mapper（DDD 5.9）。notification_record 不启用 RLS，这里显式按 user_id/tenant_id 过滤。
 */
@Mapper
public interface NotificationRecordMapper {

    int insert(NotificationRecord record);

    NotificationRecord selectById(@Param("id") Long id);

    long count(@Param("userId") Long userId,
               @Param("tenantId") Long tenantId,
               @Param("isRead") Boolean isRead,
               @Param("type") String type);

    List<NotificationRecord> page(@Param("userId") Long userId,
                                  @Param("tenantId") Long tenantId,
                                  @Param("isRead") Boolean isRead,
                                  @Param("type") String type,
                                  @Param("offset") int offset,
                                  @Param("size") int size);

    long countUnread(@Param("userId") Long userId);

    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    int markAllRead(@Param("userId") Long userId);
}
