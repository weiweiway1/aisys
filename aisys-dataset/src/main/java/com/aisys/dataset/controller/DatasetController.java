package com.aisys.dataset.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.dataset.dto.DatasetDtos;
import com.aisys.dataset.dto.DatasetVersionDtos;
import com.aisys.dataset.service.DatasetService;
import com.aisys.dataset.service.DatasetVersionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 数据集元数据 CRUD（DDD 5.4.1）。
 */
@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

    private final DatasetService datasetService;
    private final DatasetVersionService versionService;

    public DatasetController(DatasetService datasetService, DatasetVersionService versionService) {
        this.datasetService = datasetService;
        this.versionService = versionService;
    }

    @GetMapping
    public ApiResponse<PageResult<DatasetDtos.Response>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(datasetService.list(keyword, type, projectId, taskType, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<DatasetDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.success(datasetService.get(id));
    }

    /** 按版本 ID 查询数据集版本（跨服务解析 storagePath 用，如评测调度）。 */
    @GetMapping("/versions/{versionId}")
    public ApiResponse<DatasetVersionDtos.Response> getVersionById(@PathVariable Long versionId) {
        return ApiResponse.success(versionService.getById(versionId));
    }

    @PostMapping
    @AuditLog(action = "CREATE", resource = "DATASET", description = "创建数据集", resourceId = "#result.data.id")
    public ApiResponse<DatasetDtos.Response> create(@Valid @RequestBody DatasetDtos.Create req) {
        return ApiResponse.success(datasetService.create(req));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "UPDATE", resource = "DATASET", description = "更新数据集", resourceId = "#id")
    public ApiResponse<DatasetDtos.Response> update(@PathVariable Long id,
                                                    @Valid @RequestBody DatasetDtos.Update req) {
        return ApiResponse.success(datasetService.update(id, req));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "DELETE", resource = "DATASET", description = "删除数据集", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        datasetService.delete(id);
        return ApiResponse.success();
    }
}
