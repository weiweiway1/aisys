package com.aisys.resource.service.impl;

import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.exception.CommonErrorCode;
import com.aisys.common.core.response.PageResult;
import com.aisys.resource.constant.ResourceConstants;
import com.aisys.resource.constant.ResourceErrorCode;
import com.aisys.resource.dto.NodeDtos;
import com.aisys.resource.entity.ComputeNode;
import com.aisys.resource.mapper.ComputeNodeMapper;
import com.aisys.resource.mapper.NodeHeldAggregate;
import com.aisys.resource.mapper.ResourceAllocationMapper;
import com.aisys.resource.service.ComputeNodeService;
import com.aisys.resource.service.AgentService;
import com.aisys.resource.service.impl.AgentServiceImpl;
import com.aisys.resource.websocket.AgentWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 计算节点管理服务实现（平台共享表）。
 */
@Service
public class ComputeNodeServiceImpl implements ComputeNodeService {

    private final ComputeNodeMapper computeNodeMapper;
    private final ResourceAllocationMapper allocationMapper;
    private final ObjectMapper objectMapper;
    private final NodeInfoParser parser;
    private final AgentServiceImpl agentServiceImpl;
    private final AgentWebSocketHandler agentWebSocketHandler;
    private final StringRedisTemplate redis;
    private final long heartbeatTimeoutSeconds;

    public ComputeNodeServiceImpl(ComputeNodeMapper computeNodeMapper,
                                  ResourceAllocationMapper allocationMapper,
                                  ObjectMapper objectMapper,
                                  NodeInfoParser parser,
                                  AgentService agentService,
                                  AgentWebSocketHandler agentWebSocketHandler,
                                  StringRedisTemplate redis,
                                  @Value("${aisys.resource.heartbeat-timeout-seconds:90}") long heartbeatTimeoutSeconds) {
        this.computeNodeMapper = computeNodeMapper;
        this.allocationMapper = allocationMapper;
        this.objectMapper = objectMapper;
        this.parser = parser;
        // 节点维护/标签更新复用 AgentServiceImpl 的 Redis 节点状态写入
        this.agentServiceImpl = (AgentServiceImpl) agentService;
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.redis = redis;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<NodeDtos.NodeResponse> listNodes(NodeDtos.NodeQueryRequest query, int page, int size) {
        String status = query == null ? null : query.status();
        String keyword = query == null ? null : query.keyword();
        Long groupId = query == null ? null : query.groupId();
        long total = computeNodeMapper.count(status, keyword, groupId);
        if (total == 0) return PageResult.empty(page, size);
        int offset = Math.max(0, (page - 1) * size);
        List<ComputeNode> rows = computeNodeMapper.page(status, keyword, groupId, offset, size);
        List<NodeDtos.NodeResponse> items = rows.stream().map(this::toResponse).toList();
        return PageResult.of(items, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public NodeDtos.NodeDetailResponse getNodeDetail(Long id) {
        ComputeNode node = requireNode(id);
        return new NodeDtos.NodeDetailResponse(
                node.getId(), node.getNodeGroupId(), node.getAgentId(), node.getNodeName(),
                node.getIpAddress(), node.getStatus(), node.getAgentVersion(), node.getOsInfo(),
                parser.cpuInfoMap(node), parser.gpuInfoList(node),
                node.getTotalMemory(), node.getTotalDisk(), parser.labels(node),
                toStr(node.getLastHeartbeatAt()), toStr(node.getCreatedAt()));
    }

    @Override
    @Transactional
    public void updateLabels(Long id, Map<String, String> labels) {
        requireNode(id);
        try {
            String json = objectMapper.writeValueAsString(labels == null ? Map.of() : labels);
            computeNodeMapper.updateLabels(id, json);
        } catch (Exception e) {
            throw new BusinessException(ResourceErrorCode.INTERNAL_ERROR);
        }
    }

    @Override
    @Transactional
    public void enterMaintenance(Long id) {
        requireNode(id);
        computeNodeMapper.updateStatus(id, ResourceConstants.NODE_STATUS_MAINTENANCE);
        // 维护模式下不再接受新调度；Redis 标记 maintenance（不设 TTL，待退出再删）
        agentServiceImpl.setNodeStatus(id, ResourceConstants.NODE_STATUS_MAINTENANCE);
    }

    @Override
    @Transactional
    public void exitMaintenance(Long id) {
        ComputeNode node = requireNode(id);
        // 退出维护后根据心跳新鲜度决定 online/offline（与 NodeOfflineScheduler 同阈值），
        // 避免基于陈旧心跳把死节点置 online 造成调度抖动。
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(heartbeatTimeoutSeconds);
        OffsetDateTime hb = node.getLastHeartbeatAt();
        String target = (hb != null && hb.isAfter(cutoff))
                ? ResourceConstants.NODE_STATUS_ONLINE
                : ResourceConstants.NODE_STATUS_OFFLINE;
        computeNodeMapper.updateStatus(id, target);
        if (ResourceConstants.NODE_STATUS_ONLINE.equals(target)) {
            agentServiceImpl.setNodeStatus(id, ResourceConstants.NODE_STATUS_ONLINE);
        } else {
            agentServiceImpl.setNodeStatus(id, ResourceConstants.NODE_STATUS_OFFLINE);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public NodeDtos.NodeMetricsResponse getNodeMetrics(Long id) {
        ComputeNode node = requireNode(id);
        NodeHeldAggregate held = allocationMapper.sumHeldByNode(id);
        NodeInfoParser.FreeCapacity cap = parser.freeCapacity(node, held);
        return new NodeDtos.NodeMetricsResponse(
                node.getId(), node.getNodeName(), node.getStatus(),
                cap.totalGpu(), cap.totalCpu(), cap.totalMemory(),
                cap.allocatedGpu(), cap.allocatedCpu(), cap.allocatedMemory(),
                cap.freeGpu(), cap.freeCpu(), cap.freeMemory(),
                cap.heldAllocations());
    }

    @Override
    @Transactional
    public void deleteNode(Long id) {
        ComputeNode node = requireNode(id);
        String agentId = node.getAgentId();
        // 1) 释放该节点 held 租约（标记 released + released_at，保持计费/账目一致；
        //    若仅依赖 FK CASCADE 会硬删租约行、丢失 released 记录）
        allocationMapper.releaseAllHeldByNode(id);
        // 2) 作废 agentToken，防止被删节点的陈旧/恶意 Agent 在 7 天 TTL 内重新 WS 鉴权
        if (agentId != null && !agentId.isBlank()) {
            redis.delete(ResourceConstants.REDIS_KEY_AGENT_TOKEN_PREFIX + agentId);
        }
        // 3) 清理 node:status 标记
        redis.delete(ResourceConstants.REDIS_KEY_NODE_STATUS_PREFIX + id);
        // 4) 删除节点行（FK ON DELETE SET NULL 清理 task.assigned_node_id）
        computeNodeMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void updateNode(Long id, Long nodeGroupId) {
        requireNode(id);
        // 显式更新 node_group_id：null 表示清除分组（updateNodeGroup 无条件 SET，避免 <set>/<if> 跳过 null）
        computeNodeMapper.updateNodeGroup(id, nodeGroupId);
    }

    @Override
    public java.util.Map<String, Object> executeCommand(Long id, String command, Integer timeoutSec) {
        if (command == null || command.isBlank()) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST, "command 不能为空");
        }
        ComputeNode node = requireNode(id);
        int timeout = timeoutSec == null || timeoutSec <= 0 ? 30 : timeoutSec;
        return agentWebSocketHandler.sendShellCommandAndWait(node.getAgentId(), command, timeout);
    }

    private ComputeNode requireNode(Long id) {
        ComputeNode node = computeNodeMapper.selectById(id);
        if (node == null) throw new BusinessException(ResourceErrorCode.NODE_NOT_FOUND);
        return node;
    }

    private NodeDtos.NodeResponse toResponse(ComputeNode node) {
        return new NodeDtos.NodeResponse(
                node.getId(), node.getAgentId(), node.getNodeName(), node.getIpAddress(),
                node.getStatus(), node.getAgentVersion(), node.getOsInfo(),
                node.getTotalMemory(), node.getTotalDisk(),
                parser.totalCpu(node), parser.totalGpu(node),
                node.getRunningTasks() == null ? 0 : node.getRunningTasks(),
                parser.labels(node), toStr(node.getLastHeartbeatAt()));
    }

    private String toStr(java.time.OffsetDateTime t) {
        return t == null ? null : t.toString();
    }
}
