package com.aisys.common.mybatis.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway 装配（显式定义，避免多 DataSource 下 Spring Boot FlywayAutoConfiguration 回退）。
 * <p>使用 app 连接池（aisys_app）执行迁移；表由 aisys_app 拥有，受 RLS FORCE 约束。
 * 迁移脚本位于各服务 classpath:db/migration，版本号全局唯一（V{服务号}{序号}），out-of-order + 共享 flyway_schema_history。
 */
@Configuration
@ConditionalOnClass(Flyway.class)
@ConditionalOnProperty(name = "aisys.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean(initMethod = "migrate")
    public Flyway flyway(@Qualifier("appDataSource") DataSource appDataSource) {
        log.info("[Flyway] 使用 app 连接池（aisys_app）执行数据库迁移");
        // 共享单库 flyway_schema_history：各服务只迁移自己的版本号脚本（全局唯一），
        // 关闭 validateOnMigrate，避免"检测到本地未解析的已应用迁移"（其它服务的版本）。
        return Flyway.configure()
                .dataSource(appDataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .outOfOrder(true)
                .validateOnMigrate(false)
                .validateMigrationNaming(false)
                .load();
    }
}
