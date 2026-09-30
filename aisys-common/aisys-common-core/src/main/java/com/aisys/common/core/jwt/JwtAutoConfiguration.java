package com.aisys.common.core.jwt;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JWT 装配（无条件，servlet 与 reactive 均生效）。
 * 网关（reactive）与各业务服务（servlet）均经此获得 JwtUtil/JwtProperties。
 */
@AutoConfiguration
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtAutoConfiguration {

    @Bean
    public JwtUtil jwtUtil(JwtProperties props) {
        return new JwtUtil(props);
    }
}
