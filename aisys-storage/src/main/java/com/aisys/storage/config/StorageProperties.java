package com.aisys.storage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储服务配置（aisys.storage.*）。
 * <p>默认池配置由 {@code DefaultPoolInitializer} 在启动时使用，确保 default 存储池存在。
 */
@ConfigurationProperties(prefix = "aisys.storage")
public class StorageProperties {

    /** 默认存储池名称。 */
    private String defaultPoolName = "default";
    /** 默认存储池配额（字节），默认 1 TiB。 */
    private long defaultPoolQuotaBytes = 1099511627776L;

    public String getDefaultPoolName() { return defaultPoolName; }
    public void setDefaultPoolName(String defaultPoolName) { this.defaultPoolName = defaultPoolName; }
    public long getDefaultPoolQuotaBytes() { return defaultPoolQuotaBytes; }
    public void setDefaultPoolQuotaBytes(long defaultPoolQuotaBytes) { this.defaultPoolQuotaBytes = defaultPoolQuotaBytes; }
}
