package com.aisys.common.core.context;

import java.util.List;

/**
 * 当前请求用户上下文（DDD 4.1.3）。由 SecurityContextFilter（网关注入请求头 → 服务侧 HeaderAuthFilter）填充。
 * <ul>
 *   <li>普通租户用户：tenantId != null，走 RLS 受约束连接池 aisys_app。</li>
 *   <li>平台超管：roles 含 ROLE_PLATFORM_ADMIN 且 tenantId == null，走 BYPASSRLS 连接池 aisys_platform_admin。</li>
 * </ul>
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {}

    public record CurrentUser(Long userId, Long tenantId, List<String> roles,
                              String username, String traceId, boolean platformAdmin) {

        public boolean isPlatformAdmin() {
            return platformAdmin;
        }

        public boolean isTenantScoped() {
            return tenantId != null;
        }

        public boolean hasRole(String role) {
            return roles != null && roles.contains(role);
        }
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Long getTenantId() {
        CurrentUser u = get();
        return u == null ? null : u.tenantId();
    }

    public static Long getUserId() {
        CurrentUser u = get();
        return u == null ? null : u.userId();
    }

    public static String getUsername() {
        CurrentUser u = get();
        return u == null ? null : u.username();
    }

    public static String getTraceId() {
        CurrentUser u = get();
        return u == null ? null : u.traceId();
    }

    public static boolean isPlatformAdmin() {
        CurrentUser u = get();
        return u != null && u.isPlatformAdmin();
    }
}
