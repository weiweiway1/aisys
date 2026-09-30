package com.aisys.resource.service.impl;

import com.aisys.common.core.exception.BusinessException;
import com.aisys.resource.constant.ResourceConstants;
import com.aisys.resource.constant.ResourceErrorCode;
import com.aisys.resource.dto.AgentDtos;
import com.aisys.resource.entity.ComputeNode;
import com.aisys.resource.mapper.ComputeNodeMapper;
import com.aisys.resource.mapper.ResourceAllocationMapper;
import com.aisys.resource.mq.forward.NotificationPublisher;
import com.aisys.resource.service.AgentService;
import com.aisys.common.mq.outbox.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 接入服务实现。
 * <p>compute_node / resource_allocation 为平台共享表（不启用 RLS）。由于调度需全局可见所有租户的持有量，
 * 这些写入操作在 @Transactional 内执行（即使 readOnly，SET LOCAL 也需事务包裹，DDD 4.1.3）；
 * 超管上下文走 admin 池 BYPASSRLS，普通调度线程无 UserContext 时走 app 默认池。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceImpl.class);
    private static final Duration AGENT_TOKEN_TTL = Duration.ofDays(7);

    private final ComputeNodeMapper computeNodeMapper;
    private final ResourceAllocationMapper allocationMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int nodeStatusTtlSeconds;
    /** 一次性 enrollment token（配置为空则不强制，便于开发环境；生产应配置）。 */
    private final String enrollToken;

    public AgentServiceImpl(ComputeNodeMapper computeNodeMapper,
                            ResourceAllocationMapper allocationMapper,
                            StringRedisTemplate redis,
                            ObjectMapper objectMapper,
                            @Value("${aisys.resource.node-status-ttl-seconds:30}") int nodeStatusTtlSeconds,
                            @Value("${aisys.resource.enroll-token:}") String enrollToken) {
        this.computeNodeMapper = computeNodeMapper;
        this.allocationMapper = allocationMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.nodeStatusTtlSeconds = nodeStatusTtlSeconds;
        this.enrollToken = enrollToken == null ? "" : enrollToken.trim();
    }

    @Override
    @Transactional
    public AgentDtos.AgentRegisterResponse register(AgentDtos.AgentRegisterRequest request) {
        // enrollment token 校验（仅当平台配置了 enroll-token 时强制，防止任意调用方注册/冒充 agentId）
        if (!enrollToken.isEmpty()) {
            String got = request.enrollToken() == null ? "" : request.enrollToken().trim();
            if (!enrollToken.equals(got)) {
                log.warn("Agent 注册 enrollment token 校验失败 agentId={}", request.agentId());
                throw new BusinessException(ResourceErrorCode.HEARTBEAT_INVALID, "enrollment token 无效");
            }
        }

        String agentId = request.agentId();
        if (agentId == null || agentId.isBlank()) {
            agentId = "agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }

        ComputeNode node = computeNodeMapper.selectByAgentId(agentId);
        boolean isNew = (node == null);

        ComputeNode toSave = isNew ? new ComputeNode() : node;
        toSave.setAgentId(agentId);
        toSave.setNodeName(request.nodeName());
        toSave.setIpAddress(request.ipAddress());
        toSave.setAgentVersion(request.agentVersion());
        toSave.setOsInfo(request.osInfo());
        toSave.setTotalMemory(request.totalMemory());
        toSave.setTotalDisk(request.totalDisk());
        toSave.setStatus(ResourceConstants.NODE_STATUS_ONLINE);
        toSave.setLastHeartbeatAt(OffsetDateTime.now());
        toSave.setCpuInfo(toJson(request.cpuInfo()));
        toSave.setGpuInfo(toJson(request.gpuInfo()));
        toSave.setLabels(toJson(request.labels() != null ? request.labels() : Map.of()));

        if (isNew) {
            computeNodeMapper.insert(toSave);
        } else {
            computeNodeMapper.update(toSave);
        }

        // 签发 agentToken 存 Redis（TTL 7 天）。对已注册 Agent 重注册时复用现有有效 token，
        // 避免每次容器重启轮换 token、使并存会话/副本失效（幂等注册）。
        String tokenKey = ResourceConstants.REDIS_KEY_AGENT_TOKEN_PREFIX + agentId;
        String existing = redis.opsForValue().get(tokenKey);
        String agentToken = (existing != null && !existing.isBlank())
                ? existing
                : UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(tokenKey, agentToken, AGENT_TOKEN_TTL);

        // 标记节点在线
        setNodeStatus(toSave.getId(), ResourceConstants.NODE_STATUS_ONLINE);

        log.info("Agent 注册成功 agentId={} nodeId={} isNew={}", agentId, toSave.getId(), isNew);
        return new AgentDtos.AgentRegisterResponse(agentId, toSave.getId(), agentToken, toSave.getStatus());
    }

    @Override
    @Transactional
    public void heartbeat(AgentDtos.HeartbeatRequest request) {
        ComputeNode node = validateAgentToken(request.agentId(), request.agentToken());
        OffsetDateTime now = OffsetDateTime.now();
        // 刷新心跳 + 在线状态 + 运行中任务数（负载信号）
        computeNodeMapper.updateHeartbeat(node.getId(), now, request.runningTasks());
        setNodeStatus(node.getId(), ResourceConstants.NODE_STATUS_ONLINE);
        log.debug("Agent 心跳 agentId={} nodeId={} running={}", request.agentId(), node.getId(), request.runningTasks());
    }

    @Override
    @Transactional
    public void deregister(String agentId) {
        ComputeNode node = computeNodeMapper.selectByAgentId(agentId);
        if (node == null) {
            throw new BusinessException(ResourceErrorCode.AGENT_NOT_FOUND);
        }
        // 回收该节点全部 held 租约
        allocationMapper.releaseAllHeldByNode(node.getId());
        computeNodeMapper.updateStatus(node.getId(), ResourceConstants.NODE_STATUS_OFFLINE);
        redis.delete(ResourceConstants.REDIS_KEY_AGENT_TOKEN_PREFIX + agentId);
        redis.delete(ResourceConstants.REDIS_KEY_NODE_STATUS_PREFIX + node.getId());
        log.info("Agent 注销 agentId={} nodeId={}", agentId, node.getId());
    }

    @Override
    @Transactional
    public void markOnlineByAgentId(String agentId) {
        ComputeNode node = computeNodeMapper.selectByAgentId(agentId);
        if (node != null) {
            computeNodeMapper.updateHeartbeat(node.getId(), OffsetDateTime.now(), null);
            setNodeStatus(node.getId(), ResourceConstants.NODE_STATUS_ONLINE);
        }
    }

    @Override
    public ComputeNode getNodeByAgentId(String agentId) {
        return computeNodeMapper.selectByAgentId(agentId);
    }

    /** 校验 agentToken，返回对应节点 */
    public ComputeNode validateAgentToken(String agentId, String agentToken) {
        if (agentId == null || agentToken == null) {
            throw new BusinessException(ResourceErrorCode.HEARTBEAT_INVALID);
        }
        String stored = redis.opsForValue().get(ResourceConstants.REDIS_KEY_AGENT_TOKEN_PREFIX + agentId);
        if (stored == null || !stored.equals(agentToken)) {
            throw new BusinessException(ResourceErrorCode.AGENT_TOKEN_INVALID);
        }
        ComputeNode node = computeNodeMapper.selectByAgentId(agentId);
        if (node == null) {
            throw new BusinessException(ResourceErrorCode.AGENT_NOT_FOUND);
        }
        return node;
    }

    /** 在 Redis 写节点在线标记（TTL 30s），供调度快速过滤 */
    public void setNodeStatus(Long nodeId, String status) {
        String key = ResourceConstants.REDIS_KEY_NODE_STATUS_PREFIX + nodeId;
        if (ResourceConstants.NODE_STATUS_ONLINE.equals(status)) {
            redis.opsForValue().set(key, status, Duration.ofSeconds(nodeStatusTtlSeconds));
        } else {
            redis.opsForValue().set(key, status);
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("序列化 JSON 失败: {}", e.getMessage());
            return null;
        }
    }
}
