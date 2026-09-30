package com.aisys.resource.controller;

import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.resource.constant.ResourceErrorCode;
import com.aisys.resource.dto.NodeGroupDtos;
import com.aisys.resource.entity.NodeGroup;
import com.aisys.resource.mapper.NodeGroupMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 节点分组（DDD 5.7 / 原项目侧边栏「节点分组」）。
 * <p>提供列表查询与 CRUD 管理。
 */
@RestController
@RequestMapping("/api/v1/resources/node-groups")
public class NodeGroupController {

    private final NodeGroupMapper nodeGroupMapper;

    public NodeGroupController(NodeGroupMapper nodeGroupMapper) {
        this.nodeGroupMapper = nodeGroupMapper;
    }

    @GetMapping
    public ApiResponse<List<NodeGroupDtos.Response>> list() {
        return ApiResponse.success(nodeGroupMapper.selectAll().stream()
                .map(g -> new NodeGroupDtos.Response(g.getId(), g.getName(), g.getDescription(),
                        nodeGroupMapper.countNodesInGroup(g.getId()), g.getCreatedAt()))
                .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<NodeGroupDtos.Response> get(@PathVariable Long id) {
        NodeGroup g = nodeGroupMapper.selectById(id);
        if (g == null) {
            throw new BusinessException(ResourceErrorCode.NODE_NOT_FOUND, "节点分组不存在: id=" + id);
        }
        return ApiResponse.success(new NodeGroupDtos.Response(g.getId(), g.getName(), g.getDescription(),
                nodeGroupMapper.countNodesInGroup(g.getId()), g.getCreatedAt()));
    }

    @PostMapping
    @AuditLog(action = "CREATE", resource = "NODE_GROUP", description = "创建节点分组", resourceId = "#result.data.id")
    public ApiResponse<NodeGroup> create(@Valid @RequestBody NodeGroupDtos.Create req) {
        NodeGroup group = new NodeGroup();
        group.setName(req.name());
        group.setDescription(req.description());
        nodeGroupMapper.insert(group);
        return ApiResponse.success(nodeGroupMapper.selectById(group.getId()));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "UPDATE", resource = "NODE_GROUP", description = "更新节点分组", resourceId = "#id")
    public ApiResponse<NodeGroup> update(@PathVariable Long id, @Valid @RequestBody NodeGroupDtos.Update req) {
        requireGroup(id);
        NodeGroup group = new NodeGroup();
        group.setId(id);
        group.setName(req.name());
        group.setDescription(req.description());
        nodeGroupMapper.update(group);
        return ApiResponse.success(nodeGroupMapper.selectById(id));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "DELETE", resource = "NODE_GROUP", description = "删除节点分组", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requireGroup(id);
        nodeGroupMapper.deleteById(id);
        return ApiResponse.success();
    }

    private void requireGroup(Long id) {
        if (nodeGroupMapper.selectById(id) == null) {
            throw new BusinessException(ResourceErrorCode.NODE_NOT_FOUND, "节点分组不存在: id=" + id);
        }
    }
}
