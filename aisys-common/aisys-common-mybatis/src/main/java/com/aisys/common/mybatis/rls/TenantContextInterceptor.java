package com.aisys.common.mybatis.rls;

import com.aisys.common.core.context.UserContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * PostgreSQL RLS 租户隔离钩子（DDD 4.1.3）。
 * <p>在每条 MyBatis 查询/更新前，若当前为租户用户（tenantId != null 且非超管），
 * 向当前事务绑定的连接执行 <code>SET LOCAL app.tenant_id = &lt;tenantId&gt;</code>，
 * 由数据库行级安全策略自动注入 <code>WHERE tenant_id = ?</code> 与 WITH CHECK。
 *
 * <p><b>契约</b>：租户作用域的 DB 访问必须位于 {@code @Transactional} 内（含 readOnly），
 * 以保证 SET LOCAL 在同一事务的多条语句间生效。平台超管走 BYPASSRLS 连接池，本拦截器跳过。
 *
 * <p>同一连接在同一事务内只 SET LOCAL 一次（去重），事务结束后清理缓存。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        org.apache.ibatis.cache.CacheKey.class, org.apache.ibatis.mapping.BoundSql.class})
})
public class TenantContextInterceptor implements Interceptor {

    /** key = 已执行过 SET LOCAL 的连接（同事务去重） */
    private static final ThreadLocal<IdentityHashMap<Connection, Boolean>> DONE =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private volatile boolean skipSystemTables = true;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Long tenantId = UserContext.getTenantId();
        boolean platformAdmin = UserContext.isPlatformAdmin();
        if (tenantId != null && !platformAdmin) {
            Executor executor = (Executor) invocation.getTarget();
            Connection connection = executor.getTransaction().getConnection();
            if (shouldApply(invocation)) {
                setLocalTenant(connection, tenantId);
            }
        }
        return invocation.proceed();
    }

    private boolean shouldApply(Invocation invocation) {
        if (!skipSystemTables) return true;
        Object msArg = invocation.getArgs()[0];
        if (msArg instanceof MappedStatement ms) {
            String id = ms.getId();
            // 跳过 common-log 审计表、outbox 等”写即忘“操作以免在审计插入时无事务报错；审计写入自带独立事务
            return !id.contains(".AuditLogMapper") && !id.contains(".OutboxMapper");
        }
        return true;
    }

    private void setLocalTenant(Connection connection, long tenantId) throws SQLException {
        IdentityHashMap<Connection, Boolean> set = DONE.get();
        if (Boolean.TRUE.equals(set.get(connection))) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            // tenantId 为 long，直接拼入无注入风险
            st.execute("SET LOCAL app.tenant_id = " + tenantId);
        }
        set.put(connection, Boolean.TRUE);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    DONE.remove();
                }
            });
        } else {
            // 无显式事务（autocommit）：SET LOCAL 仅对单语句生效，不能跨语句缓存
            set.remove(connection);
        }
    }

    @Override
    public Object plugin(Object target) {
        return target instanceof Executor ? Plugin.wrap(target, this) : target;
    }

    @Override
    public void setProperties(Properties properties) {
        // no-op
    }

    /** 仅用于测试重置 */
    static void clearThread() {
        DONE.remove();
    }

    @SuppressWarnings("unused")
    private static Map<DataSource, Boolean> unused() { return Map.of(); }
}
