package com.aisys.auth.controller;

import com.aisys.auth.dto.TenantDtos;
import com.aisys.auth.service.TenantService;
import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    public ApiResponse<PageResult<TenantDtos.Response>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(tenantService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<TenantDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.success(tenantService.get(id));
    }

    @PostMapping
    @AuditLog(action = "CREATE", resource = "TENANT", resourceId = "#result.data.id")
    public ApiResponse<TenantDtos.Response> create(@Valid @RequestBody TenantDtos.Create req) {
        return ApiResponse.success(tenantService.create(req));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "UPDATE", resource = "TENANT", resourceId = "#id")
    public ApiResponse<TenantDtos.Response> update(@PathVariable Long id, @Valid @RequestBody TenantDtos.Create req) {
        return ApiResponse.success(tenantService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "DELETE", resource = "TENANT", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tenantService.delete(id);
        return ApiResponse.success();
    }
}
