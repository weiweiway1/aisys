package com.aisys.storage.constant;

/** Storage Service 内部常量（DDD 5.8）。 */
public final class StorageConstants {

    private StorageConstants() {}

    /** 默认存储池名称。 */
    public static final String DEFAULT_POOL_NAME = "default";
    /** 默认存储类型（SeaweedFS S3 Gateway）。 */
    public static final String DEFAULT_POOL_TYPE = "seaweedfs";
    /** 存储池状态：可用。 */
    public static final String STATUS_ACTIVE = "active";
    /** 存储池状态：禁用。 */
    public static final String STATUS_INACTIVE = "inactive";

    /** 秒传对象 key 前缀：dedup/{tenantId}/{hash}（DDD 4.2.1）。 */
    public static final String DEDUP_PREFIX = "dedup";
    /** 租户隔离根前缀：{tenantId}/...（强制由服务端拼装）。 */
    public static final String TENANT_ROOT_SEPARATOR = "/";

    /** 路径穿越 / 非法片段判定。 */
    public static final String PATH_TRAVERSAL_TOKEN = "..";
}
