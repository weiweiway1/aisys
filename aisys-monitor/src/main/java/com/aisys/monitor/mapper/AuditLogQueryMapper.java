package com.aisys.monitor.mapper;

import com.aisys.monitor.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 审计日志只读查询 Mapper（本服务专属，类名刻意区别于 common-log 的 AuditLogMapper 写入接口，避免 Bean 名冲突）。
 * <p>仅提供 list/count 查询；audit_log 表写入由各服务 common-log 切面完成。
 */
@Mapper
public interface AuditLogQueryMapper {

    List<AuditLog> selectPage(@Param("tenantId") Long tenantId,
                              @Param("actorType") String actorType,
                              @Param("resource") String resource,
                              @Param("action") String action,
                              @Param("startTime") Instant startTime,
                              @Param("endTime") Instant endTime,
                              @Param("offset") int offset,
                              @Param("size") int size);

    long selectCount(@Param("tenantId") Long tenantId,
                     @Param("actorType") String actorType,
                     @Param("resource") String resource,
                     @Param("action") String action,
                     @Param("startTime") Instant startTime,
                     @Param("endTime") Instant endTime);
}
