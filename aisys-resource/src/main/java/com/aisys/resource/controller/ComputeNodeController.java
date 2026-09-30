package com.aisys.resource.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.resource.dto.NodeDtos.NodeDetailResponse;
import com.aisys.resource.dto.NodeDtos.NodeMetricsResponse;
import com.aisys.resource.dto.NodeDtos.NodeQueryRequest;
import com.aisys.resource.dto.NodeDtos.NodeResponse;
import com.aisys.resource.dto.NodeDtos.UpdateLabelsRequest;
import com.aisys.resource.dto.NodeDtos.UpdateNodeRequest;
import com.aisys.resource.dto.NodeDtos.CommandRequest;
import com.aisys.resource.service.ComputeNodeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 计算节点管理接口（DDD 5.7.1）。
 */
@RestController
@RequestMapping("/api/v1/resources/nodes")
public class ComputeNodeController {

    private final ComputeNodeService computeNodeService;

    public ComputeNodeController(ComputeNodeService computeNodeService) {
        this.computeNodeService = computeNodeService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResult<NodeResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long groupId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        NodeQueryRequest query = new NodeQueryRequest(status, keyword, null, groupId);
        return ApiResponse.success(computeNodeService.listNodes(query, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<NodeDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.success(computeNodeService.getNodeDetail(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @AuditLog(action = "DELETE", resource = "COMPUTE_NODE", description = "删除计算节点", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        computeNodeService.deleteNode(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "UPDATE", resource = "COMPUTE_NODE", description = "更新节点（分配分组）", resourceId = "#id")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody UpdateNodeRequest req) {
        computeNodeService.updateNode(id, req == null ? null : req.nodeGroupId());
        return ApiResponse.success();
    }

    @PostMapping("/{id}/command")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @AuditLog(action = "COMMAND", resource = "COMPUTE_NODE", description = "远程命令执行", resourceId = "#id")
    public ApiResponse<Map<String, Object>> executeCommand(@PathVariable Long id,
                                                           @RequestBody CommandRequest req) {
        int timeout = req == null || req.timeout() == null ? 30 : req.timeout();
        return ApiResponse.success(computeNodeService.executeCommand(id, req == null ? null : req.command(), timeout));
    }

    @PutMapping("/{id}/labels")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "UPDATE", resource = "COMPUTE_NODE", description = "更新节点标签", resourceId = "#id")
    public ApiResponse<Void> updateLabels(@PathVariable Long id, @RequestBody UpdateLabelsRequest req) {
        computeNodeService.updateLabels(id, req == null ? Map.of() : req.labels());
        return ApiResponse.success();
    }

    @PostMapping("/{id}/maintenance")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @AuditLog(action = "MAINTENANCE", resource = "COMPUTE_NODE", description = "节点进入维护模式", resourceId = "#id")
    public ApiResponse<Void> enterMaintenance(@PathVariable Long id) {
        computeNodeService.enterMaintenance(id);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}/maintenance")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @AuditLog(action = "MAINTENANCE", resource = "COMPUTE_NODE", description = "节点退出维护模式", resourceId = "#id")
    public ApiResponse<Void> exitMaintenance(@PathVariable Long id) {
        computeNodeService.exitMaintenance(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/metrics")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<NodeMetricsResponse> metrics(@PathVariable Long id,
                                                    @RequestParam(required = false) Long startTimestamp,
                                                    @RequestParam(required = false) Long endTimestamp) {
        // 当前返回总量/已分配/空闲快照（start/end 预留，由 monitor 指标源提供时序）
        return ApiResponse.success(computeNodeService.getNodeMetrics(id));
    }
}
