package com.aisys.gateway.filter;

import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.core.jwt.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 网关 JWT 认证过滤器（DDD 4.1.2 / 5.1）。
 * <p>白名单放行；其余请求校验 RS256 签名 + Redis 黑名单，注入 X-User-Id/X-Tenant-Id/X-User-Roles/X-Trace-Id。
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGlobalFilter.class);

    private static final Set<String> WHITELIST_PREFIX = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/refresh");
    private static final String BLACKLIST_KEY = "auth:token:blacklist:";

    private final JwtUtil jwtUtil;
    private final ReactiveStringRedisTemplate redis;

    public JwtAuthGlobalFilter(JwtUtil jwtUtil, ReactiveStringRedisTemplate redis) {
        this.jwtUtil = jwtUtil;
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 放行：actuator + 白名单
        if (path.startsWith("/actuator") || path.equals("/error")
                || WHITELIST_PREFIX.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "缺少认证令牌");
        }
        String token = authHeader.substring(7).trim();

        JwtUtil.ParsedToken parsed;
        try {
            parsed = jwtUtil.parse(token);
        } catch (Exception e) {
            return unauthorized(exchange, "令牌无效或已过期");
        }
        if (!parsed.isAccessToken()) {
            return unauthorized(exchange, "令牌类型错误");
        }

        final JwtUtil.ParsedToken pt = parsed;
        // 黑名单检查（登出/踢出）
        return redis.hasKey(BLACKLIST_KEY + pt.jti()).flatMap(blacklisted -> {
            if (Boolean.TRUE.equals(blacklisted)) {
                return unauthorized(exchange, "令牌已失效");
            }
            String traceId = request.getHeaders().getFirst(CommonConstants.HEADER_TRACE_ID);
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().replace("-", "");
            }
            // 安全：先剥离客户端可能伪造的身份头，再注入 JWT 派生的可信值。
            // request.mutate().header(...) 是“追加”而非“覆盖”；若不先移除，下游 HeaderAuthFilter
            // 读取的第一个值可能是攻击者通过 X-User-Roles: ROLE_PLATFORM_ADMIN 等注入的伪造身份
            // （下游服务 permitAll，完全依赖这些头鉴权 → 等同于认证绕过/越权）。
            ServerHttpRequest mutated = request.mutate()
                    .headers(h -> {
                        h.remove(CommonConstants.HEADER_USER_ID);
                        h.remove(CommonConstants.HEADER_TENANT_ID);
                        h.remove(CommonConstants.HEADER_USER_ROLES);
                        h.remove(CommonConstants.HEADER_USERNAME);
                        h.remove(CommonConstants.HEADER_AGENT_ID);
                    })
                    .header(CommonConstants.HEADER_USER_ID, String.valueOf(pt.userId()))
                    .header(CommonConstants.HEADER_TENANT_ID, pt.tenantId() == null ? "" : String.valueOf(pt.tenantId()))
                    .header(CommonConstants.HEADER_USER_ROLES, String.join(",", pt.roles()))
                    .header(CommonConstants.HEADER_USERNAME, pt.username() == null ? "" : pt.username())
                    .header(CommonConstants.HEADER_TRACE_ID, traceId)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }).onErrorResume(e -> {
            // 失败关闭：Redis/黑名单校验异常时不得放行（下游为 permitAll，放行即绕过认证）。
            // 令牌签名已校验通过，但无法确认其是否被吊销 → 拒绝请求。
            log.warn("认证处理异常，拒绝请求（失败关闭）: {}", e.getMessage());
            return unauthorized(exchange, "认证服务暂时不可用");
        });
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"message\":\"" + message + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -200;
    }

    @SuppressWarnings("unused")
    private List<String> rolesOf(JwtUtil.ParsedToken pt) { return pt.roles(); }
}
