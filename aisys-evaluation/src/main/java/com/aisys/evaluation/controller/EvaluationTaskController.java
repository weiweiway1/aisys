package com.aisys.evaluation.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.evaluation.dto.EvaluationResultDtos.ComparisonResponse;
import com.aisys.evaluation.dto.EvaluationResultDtos.EvaluationResultResponse;
import com.aisys.evaluation.dto.EvaluationResultDtos.LeaderboardEntry;
import com.aisys.evaluation.dto.EvaluationResultDtos.SamplePage;
import com.aisys.evaluation.dto.EvaluationTaskDtos.EvaluationTaskCreateRequest;
import com.aisys.evaluation.dto.EvaluationTaskDtos.EvaluationTaskResponse;
import com.aisys.evaluation.service.EvaluationTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评测任务管理（DDD 5.6.1）。
 */
@RestController
@RequestMapping("/api/v1/evaluation")
public class EvaluationTaskController {

    private final EvaluationTaskService taskService;

    public EvaluationTaskController(EvaluationTaskService taskService) {
        this.taskService = taskService;
    }

    // ---------- 任务 CRUD ----------

    @PostMapping("/tasks")
    @AuditLog(action = "CREATE", resource = "EVALUATION_TASK", description = "创建评测任务", resourceId = "#result.data.id")
    public ApiResponse<EvaluationTaskResponse> create(@Valid @RequestBody EvaluationTaskCreateRequest request) {
        return ApiResponse.success(taskService.create(request));
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResult<EvaluationTaskResponse>> list(
            @RequestParam(value = "projectId", required = false) Long projectId,
            @RequestParam(value = "benchmarkId", required = false) Long benchmarkId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.success(taskService.list(projectId, benchmarkId, status, page, size));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<EvaluationTaskResponse> getById(@PathVariable("id") Long id) {
        return ApiResponse.success(taskService.getById(id));
    }

    @DeleteMapping("/tasks/{id}")
    @AuditLog(action = "DELETE", resource = "EVALUATION_TASK", description = "删除评测任务", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        taskService.delete(id);
        return ApiResponse.success();
    }

    // ---------- 任务生命周期 ----------

    @PostMapping("/tasks/{id}/start")
    @AuditLog(action = "START", resource = "EVALUATION_TASK", description = "启动评测任务", resourceId = "#id")
    public ApiResponse<Void> start(@PathVariable("id") Long id) {
        taskService.start(id);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/stop")
    @AuditLog(action = "STOP", resource = "EVALUATION_TASK", description = "停止评测任务", resourceId = "#id")
    public ApiResponse<Void> stop(@PathVariable("id") Long id) {
        taskService.stop(id);
        return ApiResponse.success();
    }

    @PostMapping("/tasks/{id}/rerun")
    @AuditLog(action = "RERUN", resource = "EVALUATION_TASK", description = "重跑评测任务", resourceId = "#id")
    public ApiResponse<Void> rerun(@PathVariable("id") Long id) {
        taskService.rerun(id);
        return ApiResponse.success();
    }

    // ---------- 结果 / 样本 ----------

    @GetMapping("/tasks/{id}/results")
    public ApiResponse<List<EvaluationResultResponse>> results(@PathVariable("id") Long id) {
        return ApiResponse.success(taskService.results(id));
    }

    @GetMapping("/tasks/{id}/results/{rid}/samples")
    public ApiResponse<SamplePage> samples(@PathVariable("id") Long id,
                                           @PathVariable("rid") Long rid,
                                           @RequestParam(value = "page", defaultValue = "1") int page,
                                           @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.success(taskService.samples(id, rid, page, size));
    }

    // ---------- 排行榜 / 对比 ----------

    @GetMapping("/leaderboard")
    public ApiResponse<List<LeaderboardEntry>> leaderboard(
            @RequestParam(value = "benchmarkId", required = false) Long benchmarkId,
            @RequestParam(value = "sortBy", required = false) String sortBy) {
        // benchmarkId 可选：未指定时返回空列表，避免前端未传参时 MissingServletRequestParameterException → 500
        if (benchmarkId == null) {
            return ApiResponse.success(java.util.List.of());
        }
        return ApiResponse.success(taskService.leaderboard(benchmarkId, sortBy));
    }

    @GetMapping("/comparison")
    public ApiResponse<ComparisonResponse> comparison(@RequestParam("resultIds") List<Long> resultIds) {
        return ApiResponse.success(taskService.comparison(resultIds));
    }
}
