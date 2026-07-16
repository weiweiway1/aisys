package com.aisys.common.redis.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultScriptExecutor;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的分布式锁（DDD 5.7.4 / 9）。
 * <p>SET NX EX 获取 + Lua（校验 token）释放；持锁期间看门狗按租约 1/3 周期续期，
 * 防止长事务超时失锁。用于节点调度锁 {@code lock:node:{nodeId}} 等。
 */
public class DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

    private static final RedisScript<Long> UNLOCK = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final RedisScript<Long> RENEW = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(), r -> {
                Thread t = new Thread(r, "redis-lock-watchdog");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentMap<String, ScheduledFuture<?>> renewals = new ConcurrentHashMap<>();

    public DistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试获取锁。
     * @param key 锁 key（如 lock:node:1）
     * @param lease 租约时长
     * @return 持有令牌（释放用）；null 表示获取失败
     */
    public String tryLock(String key, Duration lease) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redis.opsForValue().setIfAbsent(key, token, lease);
        if (Boolean.TRUE.equals(ok)) {
            scheduleRenewal(key, token, lease);
            return token;
        }
        return null;
    }

    /** 阻塞获取：在 timeout 内重试。 */
    public String lock(String key, Duration lease, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String token = tryLock(key, lease);
            if (token != null) return token;
            Thread.sleep(50);
        }
        return null;
    }

    public boolean unlock(String key, String token) {
        if (token == null) return false;
        ScheduledFuture<?> f = renewals.remove(token);
        if (f != null) f.cancel(false);
        Long ret = new DefaultScriptExecutor<>(redis).execute(UNLOCK, List.of(key), token);
        return ret != null && ret > 0;
    }

    private void scheduleRenewal(String key, String token, Duration lease) {
        long periodMs = Math.max(lease.toMillis() / 3, 500L);
        ScheduledFuture<?> f = watchdog.scheduleAtFixedRate(() -> {
            try {
                Long ret = new DefaultScriptExecutor<>(redis).execute(RENEW,
                        List.of(key), token, String.valueOf(lease.toMillis()));
                if (ret == null || ret == 0) {
                    // 锁已丢失或被释放，停止续期
                    ScheduledFuture<?> s = renewals.remove(token);
                    if (s != null) s.cancel(false);
                }
            } catch (Exception e) {
                log.warn("续期锁 {} 失败: {}", key, e.getMessage());
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
        renewals.put(token, f);
    }
}
