package com.aisys.model.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.model.dto.ModelDtos.ModelCreateRequest;
import com.aisys.model.dto.ModelDtos.ModelDetailResponse;
import com.aisys.model.dto.ModelDtos.ModelListResponse;
import com.aisys.model.dto.ModelDtos.ModelUpdateRequest;
import com.aisys.model.service.ModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型管理接口（DDD 5.3.1）。
 * <p>路径前缀 /api/v1/models。状态机：draft → published → deprecated → archived。
 * 查询/创建/更新/删除/发布/废弃/归档；列表 keyword 用 ILIKE name/description。
 * 租户作用域 DB 访问已在 Service 层 @Transactional 内完成（RLS SET LOCAL 跨语句生效）。
 */
@RestController
@RequestMapping("/api/v1/models")
public class ModelController {

    private final ModelService modelService;
    private final com.aisys.model.service.ModelVersionService versionService;

    public ModelController(ModelService modelService,
                           com.aisys.model.service.ModelVersionService versionService) {
        this.modelService = modelService;
        this.versionService = versionService;
    }

    /** 按版本 ID 查询模型版本（跨服务解析镜像 tar 路径 + config.imageName，如评测/训练调度）。 */
    @GetMapping("/versions/by-id/{versionId}")
    public ApiResponse<com.aisys.model.dto.VersionDtos.ModelVersionResponse> getVersionById(
            @PathVariable Long versionId) {
        return ApiResponse.success(versionService.getVersionById(versionId));
    }

    @GetMapping
    public ApiResponse<PageResult<ModelListResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var query = new com.aisys.model.dto.ModelDtos.ModelQueryRequest(
                keyword, status, projectId, page, size);
        return ApiResponse.success(modelService.listModels(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<ModelDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.success(modelService.getModel(id));
    }

    @PostMapping
    @AuditLog(action = "CREATE", resource = "MODEL", description = "创建模型",
            resourceId = "#result.data.id")
    public ApiResponse<ModelDetailResponse> create(@Valid @RequestBody ModelCreateRequest request) {
        return ApiResponse.success(modelService.createModel(request));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "UPDATE", resource = "MODEL", description = "更新模型",
            resourceId = "#id")
    public ApiResponse<ModelDetailResponse> update(@PathVariable Long id,
                                                   @Valid @RequestBody ModelUpdateRequest request) {
        return ApiResponse.success(modelService.updateModel(id, request));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "DELETE", resource = "MODEL", description = "删除模型",
            resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        modelService.deleteModel(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/publish")
    @AuditLog(action = "PUBLISH", resource = "MODEL", description = "发布模型",
            resourceId = "#id")
    public ApiResponse<Void> publish(@PathVariable Long id) {
        modelService.publishModel(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/deprecate")
    @AuditLog(action = "DEPRECATE", resource = "MODEL", description = "废弃模型",
            resourceId = "#id")
    public ApiResponse<Void> deprecate(@PathVariable Long id) {
        modelService.deprecateModel(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/archive")
    @AuditLog(action = "ARCHIVE", resource = "MODEL", description = "归档模型",
            resourceId = "#id")
    public ApiResponse<Void> archive(@PathVariable Long id) {
        modelService.archiveModel(id);
        return ApiResponse.success();
    }
}
