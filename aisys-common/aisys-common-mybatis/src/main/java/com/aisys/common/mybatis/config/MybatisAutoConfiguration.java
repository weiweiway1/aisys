package com.aisys.common.mybatis.config;

import com.aisys.common.mybatis.datasource.DataSourceConfig;
import com.aisys.common.mybatis.rls.TenantContextInterceptor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * MyBatis 装配：注册 RLS 租户拦截器（mybatis-spring-boot 自动发现 Interceptor Bean）。
 * <p>导入 {@link DataSourceConfig}（双连接池路由数据源）与 {@link FlywayConfig}（集中迁移）。
 */
@AutoConfiguration
@Import({DataSourceConfig.class, FlywayConfig.class})
public class MybatisAutoConfiguration {

    @Bean
    public TenantContextInterceptor tenantContextInterceptor() {
        return new TenantContextInterceptor();
    }

    /** 驼峰映射、NULL 处理等基础配置。 */
    @Bean
    public ConfigurationCustomizer aisysConfigurationCustomizer() {
        return new ConfigurationCustomizer() {
            @Override
            public void customize(Configuration configuration) {
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.setJdbcTypeForNull(JdbcType.NULL);
                configuration.setCacheEnabled(false);
            }
        };
    }
}
