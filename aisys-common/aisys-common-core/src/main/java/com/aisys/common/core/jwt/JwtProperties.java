package com.aisys.common.core.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** JWT 配置（DDD 4.1.1）。私钥仅 Auth 配置；其余服务/网关只配公钥。 */
@ConfigurationProperties(prefix = "aisys.jwt")
public class JwtProperties {

    private long accessTokenTtlSeconds = 24 * 3600L;
    private long refreshTokenTtlSeconds = 7 * 24 * 3600L;
    private String privateKey;
    private String publicKey;
    private String issuer = "aisys-auth";

    public long getAccessTokenTtlSeconds() { return accessTokenTtlSeconds; }
    public void setAccessTokenTtlSeconds(long v) { this.accessTokenTtlSeconds = v; }
    public long getRefreshTokenTtlSeconds() { return refreshTokenTtlSeconds; }
    public void setRefreshTokenTtlSeconds(long v) { this.refreshTokenTtlSeconds = v; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String v) { this.privateKey = v; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String v) { this.publicKey = v; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String v) { this.issuer = v; }
}
