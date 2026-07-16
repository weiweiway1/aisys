package com.aisys.evaluation.controller;

import com.aisys.common.core.response.ApiResponse;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.log.annotation.AuditLog;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkCreateRequest;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkResponse;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkUpdateRequest;
import com.aisys.evaluation.service.BenchmarkService;
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
 * 评测基准管理（DDD 5.6.1）。
 */
@RestController
@RequestMapping("/api/v1/benchmarks")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    public BenchmarkController(BenchmarkService benchmarkService) {
        this.benchmarkService = benchmarkService;
    }

    @PostMapping
    @AuditLog(action = "CREATE", resource = "BENCHMARK", description = "创建评测基准", resourceId = "#result.data.id")
    public ApiResponse<BenchmarkResponse> create(@Valid @RequestBody BenchmarkCreateRequest request) {
        return ApiResponse.success(benchmarkService.create(request));
    }

    @GetMapping
    public ApiResponse<PageResult<BenchmarkResponse>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.success(benchmarkService.list(keyword, category, status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<BenchmarkResponse> getById(@PathVariable("id") Long id) {
        return ApiResponse.success(benchmarkService.getById(id));
    }

    @PutMapping("/{id}")
    @AuditLog(action = "UPDATE", resource = "BENCHMARK", description = "更新评测基准", resourceId = "#id")
    public ApiResponse<BenchmarkResponse> update(@PathVariable("id") Long id,
                                                 @Valid @RequestBody BenchmarkUpdateRequest request) {
        return ApiResponse.success(benchmarkService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @AuditLog(action = "DELETE", resource = "BENCHMARK", description = "删除评测基准", resourceId = "#id")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        benchmarkService.delete(id);
        return ApiResponse.success();
    }
}
