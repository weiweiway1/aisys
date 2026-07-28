package com.aisys.evaluation.service.impl;

import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.evaluation.constant.EvaluationConstants;
import com.aisys.evaluation.constant.EvaluationErrorCode;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkCreateRequest;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkMetricsResponse;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkResponse;
import com.aisys.evaluation.dto.BenchmarkDtos.BenchmarkUpdateRequest;
import com.aisys.evaluation.dto.BenchmarkDtos.MetricItem;
import com.aisys.evaluation.entity.Benchmark;
import com.aisys.evaluation.mapper.BenchmarkMapper;
import com.aisys.evaluation.service.BenchmarkService;
import com.aisys.evaluation.util.EvalJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Benchmark 服务实现。租户作用域 DB 访问均在 @Transactional 内（含 readOnly），保证 SET LOCAL app.tenant_id 跨语句生效。
 */
@Service
public class BenchmarkServiceImpl implements BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkServiceImpl.class);

    private final BenchmarkMapper benchmarkMapper;

    public BenchmarkServiceImpl(BenchmarkMapper benchmarkMapper) {
        this.benchmarkMapper = benchmarkMapper;
    }

    @Override
    @Transactional
    public BenchmarkResponse create(BenchmarkCreateRequest request) {
        Long tenantId = currentTenant();
        Benchmark b = new Benchmark();
        b.setTenantId(tenantId);
        b.setName(request.name());
        b.setCategory(request.category());
        b.setDescription(request.description());
        b.setDatasetVersionIds(EvalJson.toJson(request.datasetVersionIds()));
        b.setMetricsConfig(request.metricsConfig());
        b.setEvalConfig(request.evalConfig());
        b.setPromptTemplate(request.promptTemplate());
        b.setStatus(EvaluationConstants.BENCHMARK_ACTIVE);
        benchmarkMapper.insert(b);
        return toResponse(b);
    }

    @Override
    @Transactional(readOnly = true)
    public BenchmarkResponse getById(Long id) {
        Benchmark b = mustGet(id);
        return toResponse(b);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<BenchmarkResponse> list(String keyword, String category, String status, int page, int size) {
        Long tenantId = currentTenant();
        int offset = (page - 1) * size;
        List<Benchmark> items = benchmarkMapper.selectPage(tenantId, keyword, category, status, offset, size);
        long total = benchmarkMapper.count(tenantId, keyword, category, status);
        return PageResult.of(items.stream().map(this::toResponse).toList(), total, page, size);
    }

    @Override
    @Transactional
    public BenchmarkResponse update(Long id, BenchmarkUpdateRequest request) {
        Benchmark b = mustGet(id);
        if (request.name() != null) b.setName(request.name());
        if (request.category() != null) b.setCategory(request.category());
        if (request.description() != null) b.setDescription(request.description());
        if (request.datasetVersionIds() != null) b.setDatasetVersionIds(EvalJson.toJson(request.datasetVersionIds()));
        if (request.metricsConfig() != null) b.setMetricsConfig(request.metricsConfig());
        if (request.evalConfig() != null) b.setEvalConfig(request.evalConfig());
        if (request.promptTemplate() != null) b.setPromptTemplate(request.promptTemplate());
        if (request.status() != null) b.setStatus(request.status());
        benchmarkMapper.update(b);
        return toResponse(b);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Long tenantId = currentTenant();
        int rows = benchmarkMapper.deleteByIdAndTenant(id, tenantId);
        if (rows == 0) {
            throw new BusinessException(EvaluationErrorCode.BENCHMARK_NOT_FOUND);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public BenchmarkMetricsResponse getMetrics(Long id) {
        Benchmark b = mustGet(id);
        String rawConfig = b.getMetricsConfig();

        // 默认指标列表（metrics_config 为空或解析失败时的兜底）
        List<MetricItem> fallback = List.of(
                new MetricItem("top1_acc", "Top-1 Accuracy"),
                new MetricItem("top5_acc", "Top-5 Accuracy"),
                new MetricItem("accuracy", "Accuracy"),
                new MetricItem("mAP50", "mAP50"),
                new MetricItem("mAP50-95", "mAP50-95"),
                new MetricItem("precision", "Precision"),
                new MetricItem("recall", "Recall")
        );

        if (rawConfig == null || rawConfig.isBlank()) {
            return new BenchmarkMetricsResponse(fallback, "top1_acc");
        }

        try {
            Map<String, Object> config = EvalJson.toMap(rawConfig);
            String defaultMetric = config.get("defaultSortMetric") != null
                    ? String.valueOf(config.get("defaultSortMetric")) : "top1_acc";

            // 解析 metrics 数组: [{key, label}, ...] 或 [{value, label}, ...]
            Object metricsObj = config.get("metrics");
            if (metricsObj instanceof List<?> list && !list.isEmpty()) {
                List<MetricItem> items = new java.util.ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        String key = m.get("key") != null ? String.valueOf(m.get("key"))
                                : (m.get("value") != null ? String.valueOf(m.get("value")) : "");
                        String label = m.get("label") != null ? String.valueOf(m.get("label")) : key;
                        if (!key.isBlank()) {
                            items.add(new MetricItem(key, label));
                        }
                    }
                }
                if (!items.isEmpty()) {
                    return new BenchmarkMetricsResponse(items, defaultMetric);
                }
            }
        } catch (Exception e) {
            log.warn("[Benchmark] metrics_config 解析失败 benchmarkId={}: {}", id, e.getMessage());
        }

        return new BenchmarkMetricsResponse(fallback, "top1_acc");
    }

    private Benchmark mustGet(Long id) {
        Long tenantId = currentTenant();
        Benchmark b = benchmarkMapper.selectByIdAndTenant(id, tenantId);
        if (b == null) {
            throw new BusinessException(EvaluationErrorCode.BENCHMARK_NOT_FOUND);
        }
        return b;
    }

    private BenchmarkResponse toResponse(Benchmark b) {
        return new BenchmarkResponse(
                b.getId(),
                b.getName(),
                b.getCategory(),
                b.getDescription(),
                EvalJson.toLongList(b.getDatasetVersionIds()),
                b.getMetricsConfig(),
                b.getEvalConfig(),
                b.getPromptTemplate(),
                b.getStatus(),
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }

    private Long currentTenant() {
        Long t = UserContext.getTenantId();
        if (t == null) {
            // 评测资源强租户隔离：无租户上下文不允许
            throw new BusinessException(EvaluationErrorCode.EVALUATION_INTERNAL_ERROR.code(),
                    "缺少租户上下文，无法访问评测资源");
        }
        return t;
    }
}
