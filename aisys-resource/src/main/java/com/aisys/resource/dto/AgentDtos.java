package com.aisys.resource.dto;

import java.util.List;
import java.util.Map;

/**
 * Agent 接入相关 DTO（DDD 5.7.1 / 5.7.2）。
 */
public final class AgentDtos {

    private AgentDtos() {}

    /** Agent 注册请求（DDD 5.7.2） */
    public record AgentRegisterRequest(
            String agentId,          // Agent 唯一标识（若空由平台生成）
            String nodeName,
            String ipAddress,
            String agentVersion,
            String osInfo,
            Map<String, Object> cpuInfo,
            List<Map<String, Object>> gpuInfo,
            Long totalMemory,
            Long totalDisk,
            Map<String, String> labels,
            String enrollToken       // 一次性 enrollment token（与平台配置 aisys.resource.enroll-token 比对）
    ) {}

    /** Agent 注册响应 */
    public record AgentRegisterResponse(
            String agentId,
            Long nodeId,
            String agentToken,       // 平台签发，用于后续心跳/WebSocket 鉴权
            String status
    ) {}

    /** Agent 心跳请求（字段与 Go agent 心跳体一致：agentId/agentToken/nodeName/status/runningTasks） */
    public record HeartbeatRequest(
            String agentId,
            String agentToken,
            String nodeName,         // 可选，便于日志识别人类可读节点名
            String status,           // 可选，Agent 上报的当前运行状态
            Integer runningTasks     // 可选，当前运行中任务数（负载信号）
    ) {}
}
