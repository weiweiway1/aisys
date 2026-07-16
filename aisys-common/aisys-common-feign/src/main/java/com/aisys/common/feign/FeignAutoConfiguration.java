package com.aisys.common.feign;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Feign 拦截器装配。仅当 classpath 存在 Feign 时生效（按需引入 common-feign 的服务）。
 */
@AutoConfiguration
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class FeignAutoConfiguration {

    @Bean
    public TenantFeignInterceptor tenantFeignInterceptor() {
        return new TenantFeignInterceptor();
    }
}
