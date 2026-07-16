package com.aisys.resource.dto;

import java.util.List;
import java.util.Map;

/**
 * 计算节点相关 DTO（DDD 5.7.1）。
 */
public final class NodeDtos {

    private NodeDtos() {}

    /** 节点列表查询参数 */
    public record NodeQueryRequest(
            String status,
            String keyword,
            String label,         // 形如 "gpu_type=a100"
            Long groupId
    ) {}

    /** 节点列表项响应（含 cpuCores/gpuCount/runningTasks 供列表卡片展示硬件与负载摘要） */
    public record NodeResponse(
            Long id,
            String agentId,
            String nodeName,
            String ipAddress,
            String status,
            String agentVersion,
            String osInfo,
            Long totalMemory,
            Long totalDisk,
            int cpuCores,
            int gpuCount,
            int runningTasks,
            Map<String, String> labels,
            String lastHeartbeatAt
    ) {}

    /** 节点详情响应（含 cpu/gpu 完整信息） */
    public record NodeDetailResponse(
            Long id,
            Long nodeGroupId,
            String agentId,
            String nodeName,
            String ipAddress,
            String status,
            String agentVersion,
            String osInfo,
            Map<String, Object> cpuInfo,
            List<Map<String, Object>> gpuInfo,
            Long totalMemory,
            Long totalDisk,
            Map<String, String> labels,
            String lastHeartbeatAt,
            String createdAt
    ) {}

    /** 标签更新请求 */
    public record UpdateLabelsRequest(Map<String, String> labels) {}

    /** 节点通用更新请求（当前用于分配/变更节点分组） */
    public record UpdateNodeRequest(Long nodeGroupId) {}

    /** 远程命令执行请求 */
    public record CommandRequest(String command, Integer timeout) {}

    /** 节点指标响应（getNodeMetrics） */
    public record NodeMetricsResponse(
            Long nodeId,
            String nodeName,
            String status,
            int totalGpu,
            int totalCpu,
            long totalMemoryBytes,
            int allocatedGpu,
            int allocatedCpu,
            long allocatedMemoryBytes,
            int freeGpu,
            int freeCpu,
            long freeMemoryBytes,
            int heldAllocations
    ) {}
}
