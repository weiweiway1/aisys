package com.aisys.training.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.training.dto.TrainingTemplateDtos;
import com.aisys.training.service.TrainingTemplateService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 训练模板 REST 接口（DDD 5.5.1）。
 */
@RestController
@RequestMapping("/api/v1/training/templates")
public class TrainingTemplateController {

    private final TrainingTemplateService templateService;

    public TrainingTemplateController(TrainingTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    public ApiResponse<PageResult<TrainingTemplateDtos.Response>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(templateService.list(keyword, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    public ApiResponse<TrainingTemplateDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.success(templateService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "CREATE", resource = "TRAINING_TEMPLATE", resourceId = "#result.data.id")
    public ApiResponse<TrainingTemplateDtos.Response> create(@Valid @RequestBody TrainingTemplateDtos.Create req) {
        return ApiResponse.success(templateService.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "UPDATE", resource = "TRAINING_TEMPLATE", resourceId = "#id")
    public ApiResponse<TrainingTemplateDtos.Response> update(@PathVariable Long id,
                                                             @Valid @RequestBody TrainingTemplateDtos.Update req) {
        return ApiResponse.success(templateService.update(id, req));
    }

    @PostMapping("/{id}/instantiate")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    @AuditLog(action = "INSTANTIATE", resource = "TRAINING_TEMPLATE", resourceId = "#id")
    public ApiResponse<java.util.Map<String, Object>> instantiate(@PathVariable Long id,
                                                                  @Valid @RequestBody TrainingTemplateDtos.Instantiate req) {
        Long taskId = templateService.instantiate(id, req);
        return ApiResponse.success(java.util.Map.of("taskId", taskId));
    }
}
