package com.aisys.common.redis.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 装配：提供 JSON 序列化的 RedisTemplate 与原生 StringRedisTemplate。
 * GenericJackson2JsonRedisSerializer 默认内嵌类型信息（@class），无需开启 default typing。
 */
@AutoConfiguration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
        GenericJackson2JsonRedisSerializer json = new GenericJackson2JsonRedisSerializer();
        StringRedisSerializer string = new StringRedisSerializer();
        RedisTemplate<String, Object> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(string);
        t.setHashKeySerializer(string);
        t.setValueSerializer(json);
        t.setHashValueSerializer(json);
        t.afterPropertiesSet();
        return t;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }
}
