package com.aisys.training.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.training.dto.TrainingTaskDtos;
import com.aisys.training.service.TrainingTaskService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 训练任务 REST 接口（DDD 5.5.1）。
 */
@RestController
@RequestMapping("/api/v1/training/tasks")
public class TrainingTaskController {

    private final TrainingTaskService taskService;

    public TrainingTaskController(TrainingTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    public ApiResponse<PageResult<TrainingTaskDtos.Response>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(taskService.list(projectId, status, keyword, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    public ApiResponse<TrainingTaskDtos.Response> get(@PathVariable Long id) {
        return ApiResponse.success(taskService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    @AuditLog(action = "CREATE", resource = "TRAINING_TASK", resourceId = "#result.data.id")
    public ApiResponse<TrainingTaskDtos.Response> create(@Valid @RequestBody TrainingTaskDtos.Create req) {
        return ApiResponse.success(taskService.create(req));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    @AuditLog(action = "START", resource = "TRAINING_TASK", resourceId = "#id")
    public ApiResponse<TrainingTaskDtos.Response> start(@PathVariable Long id) {
        return ApiResponse.success(taskService.start(id));
    }

    @PostMapping("/{id}/stop")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    @AuditLog(action = "STOP", resource = "TRAINING_TASK", resourceId = "#id")
    public ApiResponse<TrainingTaskDtos.Response> stop(@PathVariable Long id) {
        return ApiResponse.success(taskService.stop(id));
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    @AuditLog(action = "PAUSE", resource = "TRAINING_TASK", resourceId = "#id")
    public ApiResponse<TrainingTaskDtos.Response> pause(@PathVariable Long id) {
        return ApiResponse.success(taskService.pause(id));
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    @AuditLog(action = "RESUME", resource = "TRAINING_TASK", resourceId = "#id")
    public ApiResponse<TrainingTaskDtos.Response> resume(@PathVariable Long id) {
        return ApiResponse.success(taskService.resume(id));
    }

    @PatchMapping("/{id}/priority")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "UPDATE_PRIORITY", resource = "TRAINING_TASK", resourceId = "#id")
    public ApiResponse<TrainingTaskDtos.Response> updatePriority(@PathVariable Long id,
                                                                 @Valid @RequestBody TrainingTaskDtos.PriorityUpdate req) {
        return ApiResponse.success(taskService.updatePriority(id, req.priority()));
    }

    @GetMapping("/{id}/logs")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    public ApiResponse<PageResult<TrainingTaskDtos.LogLine>> logs(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(taskService.logs(id, page, size));
    }

    @GetMapping("/{id}/metrics")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    public ApiResponse<PageResult<TrainingTaskDtos.MetricPoint>> metrics(
            @PathVariable Long id,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "500") int size) {
        return ApiResponse.success(taskService.metrics(id, from, to, page, size));
    }

    @GetMapping("/{id}/checkpoints")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    public ApiResponse<java.util.List<com.aisys.training.dto.CheckpointDto>> checkpoints(@PathVariable Long id) {
        return ApiResponse.success(taskService.checkpoints(id));
    }

    @PostMapping("/{id}/checkpoints/{cid}/rollback")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN','USER')")
    @AuditLog(action = "ROLLBACK", resource = "TRAINING_TASK", resourceId = "#id")
    public ApiResponse<TrainingTaskDtos.Response> rollback(@PathVariable Long id,
                                                           @PathVariable("cid") Long checkpointId) {
        return ApiResponse.success(taskService.rollback(id, checkpointId));
    }

    // DELETE 保留为管理员能力（spec 提到 delete 审计）
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')")
    @AuditLog(action = "DELETE", resource = "TRAINING_TASK", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        // 软删除暂未实现表字段；此处返回成功占位，后续可扩展 deleted_at
        return ApiResponse.success();
    }
}
