package com.aisys.storage.mq;

import com.aisys.common.mq.message.BaseMessage;

import java.util.List;

/**
 * 存储事件消息（DDD 5.8 / 4.3.2）。
 * <p>由 storage 服务生产，投递到 storage.event 交换机，供 notification / monitor 等服务消费。
 * <ul>
 *   <li>QUOTA_EXCEEDED：上传时配额超额，触发租户通知。</li>
 *   <li>FILE_DELETED：批量删除完成。</li>
 *   <li>POOL_UPDATED：存储池配置变更。</li>
 * </ul>
 */
public class StorageEventMessage extends BaseMessage {

    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String FILE_DELETED = "FILE_DELETED";
    public static final String POOL_UPDATED = "POOL_UPDATED";

    private String eventType;
    private Long poolId;
    private String poolName;
    private List<String> keys;
    private Long sizeBytes;
    private Long quotaBytes;
    private Long usedBytes;
    private String reason;

    public StorageEventMessage() {}

    public static StorageEventMessage quotaExceeded(Long poolId, String poolName,
                                                    Long sizeBytes, Long quotaBytes, Long usedBytes) {
        StorageEventMessage m = new StorageEventMessage();
        m.eventType = QUOTA_EXCEEDED;
        m.poolId = poolId;
        m.poolName = poolName;
        m.sizeBytes = sizeBytes;
        m.quotaBytes = quotaBytes;
        m.usedBytes = usedBytes;
        m.reason = "upload rejected: quota exceeded";
        return m;
    }

    public static StorageEventMessage fileDeleted(Long poolId, List<String> keys) {
        StorageEventMessage m = new StorageEventMessage();
        m.eventType = FILE_DELETED;
        m.poolId = poolId;
        m.keys = keys;
        m.reason = "batch delete completed";
        return m;
    }

    public static StorageEventMessage poolUpdated(Long poolId, String poolName) {
        StorageEventMessage m = new StorageEventMessage();
        m.eventType = POOL_UPDATED;
        m.poolId = poolId;
        m.poolName = poolName;
        m.reason = "storage pool updated";
        return m;
    }

    @Override
    public String messageType() {
        // 返回具体事件类型（QUOTA_EXCEEDED/FILE_DELETED/POOL_UPDATED）作为消息鉴别字段，
        // 供 notification 消费侧按 event_type 匹配通知规则（否则恒为 "STORAGE_EVENT"，无规则可匹配）。
        return eventType != null ? eventType : "STORAGE_EVENT";
    }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public Long getPoolId() { return poolId; }
    public void setPoolId(Long poolId) { this.poolId = poolId; }
    public String getPoolName() { return poolName; }
    public void setPoolName(String poolName) { this.poolName = poolName; }
    public List<String> getKeys() { return keys; }
    public void setKeys(List<String> keys) { this.keys = keys; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Long getQuotaBytes() { return quotaBytes; }
    public void setQuotaBytes(Long quotaBytes) { this.quotaBytes = quotaBytes; }
    public Long getUsedBytes() { return usedBytes; }
    public void setUsedBytes(Long usedBytes) { this.usedBytes = usedBytes; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
