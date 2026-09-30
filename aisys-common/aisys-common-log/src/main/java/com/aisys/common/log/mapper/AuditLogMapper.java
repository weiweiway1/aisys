package com.aisys.common.log.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 审计日志写入 Mapper（共享 audit_log 表，表归 monitor 拥有，各服务经 common-log 切面写入）。
 */
@Mapper
public interface AuditLogMapper {

    void insert(@Param("tenantId") Long tenantId,
                @Param("actorType") String actorType,
                @Param("userId") Long userId,
                @Param("username") String username,
                @Param("action") String action,
                @Param("resource") String resource,
                @Param("resourceId") String resourceId,
                @Param("detail") String detail,
                @Param("ip") String ip,
                @Param("userAgent") String userAgent);
}
