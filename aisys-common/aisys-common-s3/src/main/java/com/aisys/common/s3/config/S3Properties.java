package com.aisys.common.s3.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3 / SeaweedFS 配置（DDD 4.2）。
 * SeaweedFS S3 Gateway 兼容 S3 协议；pathStyleAccess 必须开启（SeaweedFS 不支持虚拟主机样式）。
 */
@ConfigurationProperties(prefix = "aisys.s3")
public class S3Properties {

    /** S3 端点，如 http://seaweedfs:8333 */
    private String endpoint = "http://localhost:8333";
    private String region = "us-east-1";
    private String accessKey = "aisys";
    private String secretKey = "aisyssecret";
    private String bucket = "aisys";
    private boolean pathStyleAccess = true;
    /** 预签名 URL 有效期（分钟） */
    private int presignMinutes = 60;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String v) { this.endpoint = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String v) { this.accessKey = v; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String v) { this.secretKey = v; }
    public String getBucket() { return bucket; }
    public void setBucket(String v) { this.bucket = v; }
    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean v) { this.pathStyleAccess = v; }
    public int getPresignMinutes() { return presignMinutes; }
    public void setPresignMinutes(int v) { this.presignMinutes = v; }
}
