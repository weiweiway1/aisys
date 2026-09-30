package com.aisys.resource.entity;

import java.time.OffsetDateTime;

/**
 * 资源租约（resource_allocation，平台共享表，不启用 RLS）。
 * <p>status: held（占用）/ released（已释放）。
 */
public class ResourceAllocation {

    private Long id;
    private Long nodeId;
    private String taskType;
    private Long taskId;
    private Long tenantId;
    private Integer gpuCount;
    private Integer cpu;
    private Long memoryBytes;
    /** JSONB 文本：具体分配的 GPU 索引数组，如 [0,1,2,3] */
    private String gpuDevices;
    private String status;
    private OffsetDateTime acquiredAt;
    private OffsetDateTime releasedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public Integer getGpuCount() { return gpuCount; }
    public void setGpuCount(Integer gpuCount) { this.gpuCount = gpuCount; }
    public Integer getCpu() { return cpu; }
    public void setCpu(Integer cpu) { this.cpu = cpu; }
    public Long getMemoryBytes() { return memoryBytes; }
    public void setMemoryBytes(Long memoryBytes) { this.memoryBytes = memoryBytes; }
    public String getGpuDevices() { return gpuDevices; }
    public void setGpuDevices(String gpuDevices) { this.gpuDevices = gpuDevices; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getAcquiredAt() { return acquiredAt; }
    public void setAcquiredAt(OffsetDateTime acquiredAt) { this.acquiredAt = acquiredAt; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }
}
