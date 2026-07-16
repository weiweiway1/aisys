package com.aisys.common.core.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具（RS256，DDD 4.1.1）。载荷：sub=userId, tid=tenantId, roles=[...], type=access/refresh, jti, iat, exp。
 * <p>Auth Service 持私钥可签发；网关 / 业务服务持公钥仅验签。
 */
public class JwtUtil {

    private final JwtProperties props;
    private final PublicKey publicKey;
    private final PrivateKey privateKey;

    public JwtUtil(JwtProperties props) {
        this.props = props;
        this.privateKey = props.getPrivateKey() != null && !props.getPrivateKey().isBlank()
                ? JwtKeyUtil.readPrivateKey(props.getPrivateKey()) : null;
        this.publicKey = props.getPublicKey() != null && !props.getPublicKey().isBlank()
                ? JwtKeyUtil.readPublicKey(props.getPublicKey()) : null;
    }

    public boolean canSign() { return privateKey != null; }
    public long accessTokenTtlSeconds() { return props.getAccessTokenTtlSeconds(); }
    public long refreshTtlSeconds() { return props.getRefreshTokenTtlSeconds(); }

    public IssuedToken generateAccessToken(Long userId, Long tenantId, List<String> roles, String username) {
        return build(userId, tenantId, roles, username, "access", props.getAccessTokenTtlSeconds());
    }

    public IssuedToken generateRefreshToken(Long userId, Long tenantId, List<String> roles, String username) {
        return build(userId, tenantId, roles, username, "refresh", props.getRefreshTokenTtlSeconds());
    }

    private IssuedToken build(Long userId, Long tenantId, List<String> roles, String username, String type, long ttlSec) {
        if (privateKey == null) {
            throw new IllegalStateException("本服务未配置 JWT 私钥，无法签发令牌");
        }
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlSec);
        String jti = UUID.randomUUID().toString().replace("-", "");
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .issuer(props.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("type", type)
                .claim("roles", roles)
                .claim("uname", username);
        if (tenantId != null) {
            builder.claim("tid", tenantId);
        }
        String token = builder.signWith(privateKey, Jwts.SIG.RS256).compact();
        return new IssuedToken(token, jti, exp.getEpochSecond(), ttlSec);
    }

    public ParsedToken parse(String token) {
        if (publicKey == null) {
            throw new IllegalStateException("本服务未配置 JWT 公钥，无法验签");
        }
        Claims claims = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(props.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Long userId = parseLong(claims.getSubject());
        Object tid = claims.get("tid");
        Long tenantId = tid == null ? null : parseLong(String.valueOf(tid));
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        String type = claims.get("type", String.class);
        String username = claims.get("uname", String.class);
        return new ParsedToken(userId, tenantId, roles == null ? List.of() : roles,
                type, claims.getId(), claims.getExpiration(), username);
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    public record IssuedToken(String token, String jti, long expiresAtEpochSecond, long ttlSeconds) {}

    public record ParsedToken(Long userId, Long tenantId, List<String> roles,
                              String type, String jti, Date expiration, String username) {
        public boolean isAccessToken() { return "access".equals(type); }
        public boolean isRefreshToken() { return "refresh".equals(type); }
    }

    public static boolean isJwtError(Exception e) { return e instanceof JwtException; }

    @SuppressWarnings("unused")
    private static Map<String, Object> noClaims() { return Map.of(); }
}
