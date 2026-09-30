package com.aisys.common.mybatis.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 双连接池数据源装配（DDD 4.1.3）。
 * <p>两个 HikariCP 池分别以 {@code aisys_app}（RLS 约束）与 {@code aisys_platform_admin}（BYPASSRLS）角色连接同一库，
 * 由 {@link TenantRoutingDataSource} 按当前用户身份路由。
 * <br>直接以 @Value 读取连接参数构建 HikariDataSource（不依赖 DataSourceProperties，兼容 SB4 autoconfigure 拆分）。
 */
@Configuration
public class DataSourceConfig {

    @Bean(name = "appDataSource", destroyMethod = "close")
    public HikariDataSource appDataSource(
            @Value("${spring.datasource.app.url}") String url,
            @Value("${spring.datasource.app.username:aisys_app}") String username,
            @Value("${spring.datasource.app.password:}") String password,
            @Value("${spring.datasource.app.maximum-pool-size:10}") int maxPool,
            @Value("${spring.datasource.app.connection-timeout:20000}") long connTimeout) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("aisys-app-pool");
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(maxPool);
        ds.setConnectionTimeout(connTimeout);
        return ds;
    }

    @Bean(name = "adminDataSource", destroyMethod = "close")
    public HikariDataSource adminDataSource(
            @Value("${spring.datasource.admin.url}") String url,
            @Value("${spring.datasource.admin.username:aisys_platform_admin}") String username,
            @Value("${spring.datasource.admin.password:}") String password,
            @Value("${spring.datasource.admin.maximum-pool-size:5}") int maxPool,
            @Value("${spring.datasource.admin.connection-timeout:20000}") long connTimeout) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("aisys-admin-pool");
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(maxPool);
        ds.setConnectionTimeout(connTimeout);
        return ds;
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "dataSource")
    public DataSource dataSource(@Qualifier("appDataSource") DataSource app,
                                 @Qualifier("adminDataSource") DataSource admin) {
        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(TenantRoutingDataSource.KEY_APP, app);
        targets.put(TenantRoutingDataSource.KEY_ADMIN, admin);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(app);
        routing.afterPropertiesSet();
        return routing;
    }
}
