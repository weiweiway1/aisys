package com.aisys.monitor.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

/**
 * 审计日志指标查询 Mapper（仅供 Micrometer 指标周期刷新使用）。
 * <p>查询在 @Scheduled 线程执行（无 UserContext），audit_log 未启用 RLS，故不依赖租户上下文。
 */
@Mapper
public interface AuditLogMetricsMapper {

    /** 审计日志总条数。 */
    long countAll();

    /** 自指定时间以来的审计日志条数。 */
    long countSince(@Param("since") Instant since);
}
