package com.aisys.resource.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.resource.dto.AgentDtos.AgentRegisterRequest;
import com.aisys.resource.dto.AgentDtos.AgentRegisterResponse;
import com.aisys.resource.dto.AgentDtos.HeartbeatRequest;
import com.aisys.resource.service.AgentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 接入端点（DDD 5.7.1）。
 * <p>注册/心跳/注销。鉴权：注册使用一次性 enrollment token（网关层校验）+ mTLS；
 * 心跳与 WebSocket 使用注册后签发的 agentToken。本服务侧仅校验 agentToken（Redis）。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/register")
    @AuditLog(action = "REGISTER", resource = "AGENT", description = "Agent 注册", resourceId = "#result.data.agentId")
    public ApiResponse<AgentRegisterResponse> register(@Valid @RequestBody AgentRegisterRequest request) {
        return ApiResponse.success(agentService.register(request));
    }

    @PostMapping("/heartbeat")
    public ApiResponse<Void> heartbeat(@Valid @RequestBody HeartbeatRequest request) {
        agentService.heartbeat(request);
        return ApiResponse.success();
    }

    @DeleteMapping("/deregister/{agentId}")
    @AuditLog(action = "DEREGISTER", resource = "AGENT", description = "Agent 注销", resourceId = "#agentId")
    public ApiResponse<Void> deregister(@PathVariable String agentId) {
        agentService.deregister(agentId);
        return ApiResponse.success();
    }
}
