package com.aisys.auth.controller;

import com.aisys.auth.dto.RoleDtos;
import com.aisys.auth.dto.RoleResponse;
import com.aisys.auth.entity.Permission;
import com.aisys.auth.service.RoleService;
import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.log.annotation.AuditLog;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 角色/权限查询与管理（供前端角色分配与权限码展示，DDD 5.7）。 */
@RestController
@RequestMapping("/api/v1/roles")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public ApiResponse<List<RoleResponse>> list() {
        return ApiResponse.success(roleService.listRoles());
    }

    @GetMapping("/permissions")
    public ApiResponse<List<Permission>> permissions() {
        return ApiResponse.success(roleService.listPermissions());
    }

    @PostMapping
    @AuditLog(action = "CREATE", resource = "ROLE", description = "创建角色", resourceId = "#result.data.id")
    public ApiResponse<RoleResponse> create(@Valid @RequestBody RoleDtos.Create req) {
        return ApiResponse.success(roleService.create(req));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "UPDATE", resource = "ROLE", description = "更新角色", resourceId = "#id")
    public ApiResponse<RoleResponse> update(@PathVariable Long id, @Valid @RequestBody RoleDtos.Update req) {
        return ApiResponse.success(roleService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "DELETE", resource = "ROLE", description = "删除角色", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/permissions")
    public ApiResponse<List<Long>> getPermissions(@PathVariable Long id) {
        return ApiResponse.success(roleService.getPermissionIds(id));
    }

    @PutMapping("/{id}/permissions")
    @AuditLog(action = "ASSIGN", resource = "ROLE", description = "分配角色权限", resourceId = "#id")
    public ApiResponse<Void> assignPermissions(@PathVariable Long id, @RequestBody RoleDtos.AssignPermissions req) {
        roleService.assignPermissions(id, req == null ? null : req.permissionIds());
        return ApiResponse.success();
    }
}
