package com.aisys.evaluation.service;

import com.aisys.common.core.response.PageResult;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkCreateRequest;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkMetricsResponse;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkResponse;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkUpdateRequest;

/**
 * 评测基准服务。
 */
public interface BenchmarkService {

    BenchmarkResponse create(BenchmarkCreateRequest request);

    BenchmarkResponse getById(Long id);

    PageResult<BenchmarkResponse> list(String keyword, String category, String status, int page, int size);

    BenchmarkResponse update(Long id, BenchmarkUpdateRequest request);

    void delete(Long id);

    /** 获取评测集的指标配置（解析 metrics_config JSON，返回结构化指标列表） */
    BenchmarkMetricsResponse getMetrics(Long id);
}
