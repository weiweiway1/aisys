package com.aisys.common.security.web;

import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.core.context.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 身份过滤器（服务侧，DDD 4.1.2）。
 * <p>网关在校验 JWT 后注入 X-User-Id / X-Tenant-Id / X-User-Roles / X-Username / X-Trace-Id，
 * 本过滤器将其读入 {@link UserContext}（ThreadLocal）与 Spring SecurityContext，
 * 使 {@code @PreAuthorize}（基于角色）与 RLS（基于 tenantId）均可用。
 */
public class HeaderAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String userIdHeader = request.getHeader(CommonConstants.HEADER_USER_ID);
            if (userIdHeader != null && !userIdHeader.isBlank()) {
                Long userId = parseLong(userIdHeader);
                Long tenantId = parseLong(request.getHeader(CommonConstants.HEADER_TENANT_ID));
                String username = request.getHeader(CommonConstants.HEADER_USERNAME);
                String rolesHeader = request.getHeader(CommonConstants.HEADER_USER_ROLES);
                List<String> roles = rolesHeader == null || rolesHeader.isBlank()
                        ? List.of()
                        : Arrays.stream(rolesHeader.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                boolean platformAdmin = roles.contains(CommonConstants.ROLE_PLATFORM_ADMIN);

                String traceId = request.getHeader(CommonConstants.HEADER_TRACE_ID);
                if (traceId == null || traceId.isBlank()) {
                    traceId = UUID.randomUUID().toString().replace("-", "");
                }
                MDC.put(CommonConstants.TRACE_ID_MDC_KEY, traceId);
                response.setHeader(CommonConstants.HEADER_TRACE_ID, traceId);

                UserContext.set(new UserContext.CurrentUser(userId, tenantId, roles, username, traceId, platformAdmin));

                var authorities = roles.stream().map(SimpleGrantedAuthority::new).toList();
                var auth = new UsernamePasswordAuthenticationToken(userId == null ? "anonymous" : userId,
                        null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                // 仍生成 traceId 便于日志关联
                String traceId = request.getHeader(CommonConstants.HEADER_TRACE_ID);
                if (traceId == null || traceId.isBlank()) {
                    traceId = UUID.randomUUID().toString().replace("-", "");
                }
                MDC.put(CommonConstants.TRACE_ID_MDC_KEY, traceId);
                response.setHeader(CommonConstants.HEADER_TRACE_ID, traceId);
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
            MDC.remove(CommonConstants.TRACE_ID_MDC_KEY);
        }
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
