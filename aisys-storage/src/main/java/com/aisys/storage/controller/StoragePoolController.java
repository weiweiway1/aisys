package com.aisys.storage.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.storage.dto.StoragePoolDtos;
import com.aisys.storage.service.StoragePoolService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 存储池管理（DDD 5.8.1）。平台共享资源，仅平台超管可管理。
 */
@RestController
@RequestMapping("/api/v1/storage/pools")
public class StoragePoolController {

    private final StoragePoolService poolService;

    public StoragePoolController(StoragePoolService poolService) {
        this.poolService = poolService;
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('TENANT_ADMIN')")
    public ApiResponse<PageResult<StoragePoolDtos.Response>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(poolService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('TENANT_ADMIN')")
    public ApiResponse<StoragePoolDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.success(poolService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @AuditLog(action = "CREATE", resource = "STORAGE_POOL", resourceId = "#result.data.id")
    public ApiResponse<StoragePoolDtos.Response> create(@Valid @RequestBody StoragePoolDtos.Create req) {
        return ApiResponse.success(poolService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @AuditLog(action = "UPDATE", resource = "STORAGE_POOL", resourceId = "#id")
    public ApiResponse<StoragePoolDtos.Response> update(@PathVariable Long id,
                                                        @Valid @RequestBody StoragePoolDtos.Create req) {
        return ApiResponse.success(poolService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @AuditLog(action = "DELETE", resource = "STORAGE_POOL", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        poolService.delete(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/usage")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('TENANT_ADMIN')")
    public ApiResponse<StoragePoolDtos.Usage> usage(@PathVariable Long id) {
        return ApiResponse.success(poolService.usage(id));
    }
}
