package com.aisys.resource.service;

import com.aisys.resource.dto.AgentDtos;
import com.aisys.resource.entity.ComputeNode;
import com.aisys.resource.mapper.ComputeNodeMapper;

/**
 * Agent 接入服务接口（注册/心跳/注销/上下线）。
 */
public interface AgentService {

    AgentDtos.AgentRegisterResponse register(AgentDtos.AgentRegisterRequest request);

    void heartbeat(AgentDtos.HeartbeatRequest request);

    void deregister(String agentId);

    /** WebSocket 鉴权通过后调用：置 online + 刷新心跳 */
    void markOnlineByAgentId(String agentId);

    /** 由调度器/转发层使用：根据 agentId 查节点 */
    ComputeNode getNodeByAgentId(String agentId);
}
