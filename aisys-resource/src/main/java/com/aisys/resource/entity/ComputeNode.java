package com.aisys.resource.entity;

import java.time.OffsetDateTime;

/**
 * 计算节点（compute_node，平台共享表，不启用 RLS）。
 * <p>cpu_info / gpu_info / labels 以 JSONB 存储，由 MyBatis 以 String 透传（Service 层用 ObjectMapper 解析）。
 */
public class ComputeNode {

    private Long id;
    private Long nodeGroupId;
    private String agentId;
    private String nodeName;
    private String ipAddress;
    private String status;
    private String agentVersion;
    private String osInfo;
    /** JSONB 文本：{model, cores, threads} */
    private String cpuInfo;
    /** JSONB 文本：[{index, model, memoryMb, driverVersion, cudaVersion}] */
    private String gpuInfo;
    private Long totalMemory;
    private Long totalDisk;
    /** JSONB 文本：{region, gpu_type, ...} */
    private String labels;
    /** 当前运行中任务数（心跳上报的负载信号） */
    private Integer runningTasks;
    private OffsetDateTime lastHeartbeatAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getNodeGroupId() { return nodeGroupId; }
    public void setNodeGroupId(Long nodeGroupId) { this.nodeGroupId = nodeGroupId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getNodeName() { return nodeName; }
    public void setNodeName(String nodeName) { this.nodeName = nodeName; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public String getOsInfo() { return osInfo; }
    public void setOsInfo(String osInfo) { this.osInfo = osInfo; }
    public String getCpuInfo() { return cpuInfo; }
    public void setCpuInfo(String cpuInfo) { this.cpuInfo = cpuInfo; }
    public String getGpuInfo() { return gpuInfo; }
    public void setGpuInfo(String gpuInfo) { this.gpuInfo = gpuInfo; }
    public Long getTotalMemory() { return totalMemory; }
    public void setTotalMemory(Long totalMemory) { this.totalMemory = totalMemory; }
    public Long getTotalDisk() { return totalDisk; }
    public void setTotalDisk(Long totalDisk) { this.totalDisk = totalDisk; }
    public String getLabels() { return labels; }
    public void setLabels(String labels) { this.labels = labels; }
    public Integer getRunningTasks() { return runningTasks; }
    public void setRunningTasks(Integer runningTasks) { this.runningTasks = runningTasks; }
    public OffsetDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(OffsetDateTime lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
