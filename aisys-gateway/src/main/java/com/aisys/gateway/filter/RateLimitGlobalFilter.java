package com.aisys.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 三级限流过滤器（DDD 5.1.3）：基于 Redis Lua 令牌桶，按"最严命中"拒绝。
 * 全局 1000 req/s、租户 200 req/s、用户 50 req/s，任一桶耗尽即 429 + Retry-After。
 */
@Component
public class RateLimitGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitGlobalFilter.class);

    private static final String LUA =
            "local key=KEYS[1] local cap=tonumber(ARGV[1]) local rate=tonumber(ARGV[2]) " +
            "local now=tonumber(ARGV[3]) local ttl=tonumber(ARGV[4]) " +
            "local vals=redis.call('HMGET',key,'ts','tok') local ts=tonumber(vals[1]) local tok=tonumber(vals[2]) " +
            "if ts==nil then ts=now tok=cap end local elapsed=math.max(0,(now-ts)/1000) " +
            "tok=math.min(cap,tok+elapsed*rate) local allow=0 if tok>=1 then tok=tok-1 allow=1 end " +
            "redis.call('HMSET',key,'ts',now,'tok',tok) redis.call('PEXPIRE',key,ttl) return allow";

    private final RedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);
    private final ReactiveStringRedisTemplate redis;

    private static final long GLOBAL_CAP = 1000, GLOBAL_RATE = 1000;
    private static final long TENANT_CAP = 200, TENANT_RATE = 200;
    private static final long USER_CAP = 50, USER_RATE = 50;
    private static final long TTL_MS = 60_000;

    public RateLimitGlobalFilter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getPath().value();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        String userId = req.getHeaders().getFirst("X-User-Id");
        String tenantId = req.getHeaders().getFirst("X-Tenant-Id");
        if (userId == null || userId.isBlank()) userId = "ip:" + clientIp(req);
        if (tenantId == null || tenantId.isBlank()) tenantId = "anon";

        long now = System.currentTimeMillis();
        Mono<Long> g = acquire("ratelimit:global", GLOBAL_CAP, GLOBAL_RATE, now);
        Mono<Long> t = acquire("ratelimit:tenant:" + tenantId, TENANT_CAP, TENANT_RATE, now);
        Mono<Long> u = acquire("ratelimit:user:" + userId, USER_CAP, USER_RATE, now);

        return Mono.zip(g, t, u).flatMap(tr -> {
            if (tr.getT1() == 0L || tr.getT2() == 0L || tr.getT3() == 0L) {
                return tooManyRequests(exchange);
            }
            return chain.filter(exchange);
        }).onErrorResume(e -> {
            // 失败关闭：Redis/脚本异常时不得放行（降级放行=移除全部限流，登录爆破等匿名攻击面失防）。
            log.warn("限流检查异常，失败关闭（429）: {}", e.getMessage());
            return tooManyRequests(exchange);
        });
    }

    private Mono<Long> acquire(String key, long cap, long rate, long now) {
        return redis.execute(script, List.of(key),
                String.valueOf(cap), String.valueOf(rate), String.valueOf(now), String.valueOf(TTL_MS))
                .next().switchIfEmpty(Mono.error(new IllegalStateException("rate-limit script returned empty")));
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add("Retry-After", "2");
        String body = "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private String clientIp(ServerHttpRequest req) {
        // 不信任客户端伪造的 X-Forwarded-For：网关即边缘节点，直接用 TCP 对端地址，
        // 否则攻击者轮换该头即可为每个请求获得新的按 IP 限流桶（绕过限流/登录爆破）。
        return req.getRemoteAddress() == null ? "unknown" : req.getRemoteAddress().getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return -100; // 在 JWT 过滤器（-200）之后
    }
}
