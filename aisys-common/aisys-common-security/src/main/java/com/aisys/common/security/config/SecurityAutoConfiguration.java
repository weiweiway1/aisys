package com.aisys.common.security.config;

import com.aisys.common.security.web.HeaderAuthFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Servlet 安全自动装配（服务侧）。JwtUtil/JwtProperties 由 {@link JwtAutoConfiguration} 无条件装配。
 * <p>默认策略：无状态、关闭 CSRF、放行 actuator/error；其余请求经 HeaderAuthFilter 注入身份后放行，
 * 细粒度鉴权交由 {@code @PreAuthorize}（RBAC）与 PostgreSQL RLS（租户隔离）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HeaderAuthFilter headerAuthFilter() {
        return new HeaderAuthFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, HeaderAuthFilter headerAuthFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/actuator/**", "/error", "/favicon.ico").permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(headerAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
