package com.aisys.common.feign;

import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.core.context.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * Feign 拦截器（DDD common-feign）：向下游服务透传租户、用户、角色、链路追踪头。
 * <p>下游服务的 {@code HeaderAuthFilter} 据此重建 UserContext，实现跨服务租户/链路一致性。
 */
public class TenantFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        UserContext.CurrentUser u = UserContext.get();
        if (u != null) {
            if (u.userId() != null) template.header(CommonConstants.HEADER_USER_ID, String.valueOf(u.userId()));
            if (u.tenantId() != null) template.header(CommonConstants.HEADER_TENANT_ID, String.valueOf(u.tenantId()));
            if (u.username() != null) template.header(CommonConstants.HEADER_USERNAME, u.username());
            if (u.roles() != null && !u.roles().isEmpty()) {
                template.header(CommonConstants.HEADER_USER_ROLES, String.join(",", u.roles()));
            }
        }
        String traceId = MDC.get(CommonConstants.TRACE_ID_MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = u == null ? null : u.traceId();
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        template.header(CommonConstants.HEADER_TRACE_ID, traceId);
    }
}
