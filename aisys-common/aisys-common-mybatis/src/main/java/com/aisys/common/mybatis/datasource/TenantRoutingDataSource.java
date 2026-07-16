package com.aisys.common.mybatis.datasource;

import com.aisys.common.core.context.UserContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * 租户路由数据源（DDD 4.1.3 超管连接路由）。
 * <ul>
 *   <li>普通租户请求（tenantId != null）→ <b>aisys_app</b> 连接池（受 RLS 约束）。</li>
 *   <li>平台超管（ROLE_PLATFORM_ADMIN，tenantId == null）→ <b>aisys_platform_admin</b> 连接池（BYPASSRLS）。</li>
 * </ul>
 * <p>无用户上下文时（如 Flyway 迁移、定时任务）默认走 app 池。
 */
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    public static final String KEY_APP = "app";
    public static final String KEY_ADMIN = "admin";

    @Override
    protected Object determineCurrentLookupKey() {
        return UserContext.isPlatformAdmin() ? KEY_ADMIN : KEY_APP;
    }
}
