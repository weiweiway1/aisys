package com.aisys.notification.mapper;

import com.aisys.notification.entity.NotificationRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 通知规则 Mapper（DDD 5.9）。notification_rule 不启用 RLS，这里显式按 tenant_id 过滤。
 */
@Mapper
public interface NotificationRuleMapper {

    int insert(NotificationRule rule);

    NotificationRule selectById(@Param("id") Long id);

    long count(@Param("tenantId") Long tenantId, @Param("eventType") String eventType);

    List<NotificationRule> page(@Param("tenantId") Long tenantId,
                                @Param("eventType") String eventType,
                                @Param("offset") int offset,
                                @Param("size") int size);

    /**
     * 按事件类型查启用的规则（MQ 消费时用）。
     * 租户隔离：只返回平台级规则（tenant_id IS NULL）或本租户规则（tenant_id = #{tenantId}），
     * 避免租户 A 的规则对租户 B 的事件越权触发。
     */
    List<NotificationRule> selectEnabledByEventType(@Param("eventType") String eventType,
                                                    @Param("tenantId") Long tenantId);

    int update(NotificationRule rule);

    int deleteById(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** 幂等初始化：判断指定 event_type 是否已存在规则 */
    long countByEventType(@Param("eventType") String eventType);
}
