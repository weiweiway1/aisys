package com.aisys.auth.controller;

import com.aisys.auth.dto.UserDtos;
import com.aisys.auth.service.UserService;
import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    public ApiResponse<PageResult<UserDtos.Response>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(userService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    public ApiResponse<UserDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.success(userService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "CREATE", resource = "USER", resourceId = "#result.data.id")
    public ApiResponse<UserDtos.Response> create(@Valid @RequestBody UserDtos.Create req) {
        return ApiResponse.success(userService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "UPDATE", resource = "USER", resourceId = "#id")
    public ApiResponse<UserDtos.Response> update(@PathVariable Long id, @Valid @RequestBody UserDtos.Update req) {
        return ApiResponse.success(userService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "DELETE", resource = "USER", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "UPDATE", resource = "USER", description = "分配角色", resourceId = "#id")
    public ApiResponse<Void> assignRoles(@PathVariable Long id, @RequestBody UserDtos.AssignRoles req) {
        userService.assignRoles(id, req.roleIds());
        return ApiResponse.success();
    }
}
