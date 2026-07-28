package com.aisys.evaluation.controller;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.evaluation.dto.EvaluationReportDtos.EvaluationReportResponse;
import com.aisys.evaluation.dto.EvaluationReportDtos.RegenerateRequest;
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
    private final com.aisys.evaluation.service.EvaluationReportService reportService;

    public EvaluationTaskController(EvaluationTaskService taskService,
                                    com.aisys.evaluation.service.EvaluationReportService reportService) {
        this.taskService = taskService;
        this.reportService = reportService;
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

    // ---------- 评测报告 ----------

    /** 获取某任务的评测报告（查看页面用）。 */
    @GetMapping("/tasks/{id}/report")
    public ApiResponse<EvaluationReportResponse> getReport(@PathVariable("id") Long id) {
        return ApiResponse.success(reportService.getReportByTaskId(id));
    }

    /** 手动触发重新生成报告。 */
    @PostMapping("/tasks/{id}/report/regenerate")
    @AuditLog(action = "GENERATE", resource = "EVALUATION_REPORT", description = "生成评测报告", resourceId = "#id")
    public ApiResponse<Void> regenerateReport(@PathVariable("id") Long id,
                                              @RequestBody(required = false) RegenerateRequest request) {
        boolean force = request != null && request.force();
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            // 平台超管场景：从任务本身获取 tenantId（或拒绝操作）
            throw new com.aisys.common.core.exception.BusinessException(
                com.aisys.evaluation.constant.EvaluationErrorCode.EVALUATION_TASK_NOT_FOUND);
        }
        reportService.generateReportAsync(id, force, tenantId);
        return ApiResponse.success();
    }

    /** 下载 Markdown 格式报告。 */
    @GetMapping(value = "/tasks/{id}/report/download/md", produces = "text/markdown;charset=UTF-8")
    public org.springframework.http.ResponseEntity<String> downloadMd(@PathVariable("id") Long id) {
        String md = reportService.getReportMd(id);
        if (md == null || md.isBlank()) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=evaluation-report-" + id + ".md")
                .body(md);
    }

    /** 下载 Word 格式报告（HTML → Word 兼容格式）。
     *  注意：当前实现为 HTML 内容以 .doc 扩展名返回，Word 可直接打开。
     *  如需真正的 .docx 格式，需集成 Apache POI / docx4j 库。 */
    @GetMapping(value = "/tasks/{id}/report/download/word", produces = "application/msword;charset=UTF-8")
    public org.springframework.http.ResponseEntity<byte[]> downloadWord(@PathVariable("id") Long id) throws Exception {
        String html = reportService.getReportHtml(id);
        if (html == null || html.isBlank()) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        // HTML 转 Word：返回 .doc 格式（Word 可打开 HTML 内容）
        byte[] bytes = ("<!DOCTYPE html><html><head><meta charset='utf-8'></head><body>" + html + "</body></html>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=evaluation-report-" + id + ".doc")
                .body(bytes);
    }
}
