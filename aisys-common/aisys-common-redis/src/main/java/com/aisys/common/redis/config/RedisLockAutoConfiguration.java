package com.aisys.common.redis.config;

import com.aisys.common.redis.lock.DistributedLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
public class RedisLockAutoConfiguration {

    @Bean
    public DistributedLock distributedLock(StringRedisTemplate redis) {
        return new DistributedLock(redis);
    }
}
