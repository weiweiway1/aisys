package com.aisys.monitor.service;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.monitor.constant.MonitorErrorCode;
import com.aisys.monitor.dto.AuditLogDtos;
import com.aisys.monitor.entity.AuditLog;
import com.aisys.monitor.mapper.AuditLogQueryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 审计日志查询服务（DDD 5.10.2）。
 * <p>audit_log 为平台共享表（tenant_id 可空，不启用 RLS），本服务显式过滤租户作用域：
 * 平台超管可查全部；其余角色（理论上接口已限 PLATFORM_ADMIN）仅查本租户。
 * 查询位于 @Transactional(readOnly = true) 内，保证路由数据源连接稳定。
 */
@Service
public class AuditLogService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditLogQueryMapper auditLogMapper;

    public AuditLogService(AuditLogQueryMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 分页查询审计日志。
     */
    @Transactional(readOnly = true)
    public PageResult<AuditLogDtos.Response> list(AuditLogDtos.Query query) {
        int page = normalizePage(query.page());
        int size = normalizeSize(query.size());
        validateTimeRange(query.startTime(), query.endTime());

        Long tenantId = UserContext.isPlatformAdmin() ? null : UserContext.getTenantId();

        long total = auditLogMapper.selectCount(tenantId, query.actorType(), query.resource(),
                query.action(), query.startTime(), query.endTime());
        if (total == 0) {
            return PageResult.empty(page, size);
        }

        int offset = (page - 1) * size;
        List<AuditLog> rows = auditLogMapper.selectPage(tenantId, query.actorType(), query.resource(),
                query.action(), query.startTime(), query.endTime(), offset, size);
        List<AuditLogDtos.Response> items = rows.stream().map(this::toResponse).toList();
        return PageResult.of(items, total, page, size);
    }

    private AuditLogDtos.Response toResponse(AuditLog a) {
        return new AuditLogDtos.Response(
                a.getId(),
                a.getTenantId(),
                a.getActorType(),
                a.getUserId(),
                a.getUsername(),
                a.getAction(),
                a.getResource(),
                a.getResourceId(),
                a.getDetail(),
                a.getIpAddress(),
                a.getUserAgent(),
                a.getCreatedAt()
        );
    }

    private int normalizePage(Integer page) {
        int p = page == null ? 1 : page;
        if (p < 1) {
            throw new BusinessException(MonitorErrorCode.INVALID_PAGE_PARAM);
        }
        return p;
    }

    private int normalizeSize(Integer size) {
        int s = size == null ? 20 : size;
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw new BusinessException(MonitorErrorCode.INVALID_PAGE_PARAM);
        }
        return s;
    }

    private void validateTimeRange(Instant startTime, Instant endTime) {
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new BusinessException(MonitorErrorCode.INVALID_TIME_RANGE);
        }
    }
}
