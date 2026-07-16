package com.aisys.monitor.config;

import com.aisys.monitor.mapper.AuditLogMetricsMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务指标装配（DDD 5.10）。
 * <p>注入 MeterRegistry，注册 1-2 个 gauge（本服务不依赖 resource 表，故以 audit_log 聚合指标代替节点在线数）。
 * 通过 @Scheduled 周期刷新 gauge 数值（避免每次抓取都打 DB），由 /actuator/prometheus 暴露：
 * <ul>
 *   <li>monitor.audit.log.total —— 审计日志总条数（gauge）。</li>
 *   <li>monitor.audit.log.last24h.total —— 最近 24 小时审计日志条数（gauge）。</li>
 * </ul>
 * 平台超管身份在此查询线程中不存在，直接走 admin 池（BYPASSRLS），故无需 SET LOCAL。
 */
@Configuration
@ConditionalOnClass(MeterRegistry.class)
public class MonitorMetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MonitorMetricsConfig.class);
    private static final long REFRESH_INTERVAL_MS = 60_000L;

    private final MeterRegistry meterRegistry;
    private final AuditLogMetricsMapper metricsMapper;
    private final AtomicLong auditLogTotal = new AtomicLong(0L);
    private final AtomicLong auditLogLast24hTotal = new AtomicLong(0L);

    public MonitorMetricsConfig(MeterRegistry meterRegistry, AuditLogMetricsMapper metricsMapper) {
        this.meterRegistry = meterRegistry;
        this.metricsMapper = metricsMapper;
    }

    @PostConstruct
    public void init() {
        meterRegistry.gauge("monitor.audit.log.total", auditLogTotal);
        meterRegistry.gauge("monitor.audit.log.last24h.total", auditLogLast24hTotal);
        refresh();
        log.info("[Monitor] 业务指标已注册：monitor.audit.log.total / monitor.audit.log.last24h.total");
    }

    /** 周期刷新 gauge 数值。异常吞掉避免影响主流程（下次周期重试）。 */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = REFRESH_INTERVAL_MS)
    public void refresh() {
        try {
            auditLogTotal.set(metricsMapper.countAll());
            auditLogLast24hTotal.set(metricsMapper.countSince(java.time.Instant.now().minusSeconds(86400)));
        } catch (Exception e) {
            log.warn("[Monitor] 刷新审计指标失败: {}", e.getMessage());
        }
    }
}
