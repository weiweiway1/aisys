package com.aisys.evaluation.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 评测结果 / 排行榜 / 对比相关 DTO（record）。
 * overallScores / categoryScores 以解析后的 Map<String,Object> 返回给前端，
 * 前端雷达图（categoryScores）与表格（overallScores）直接消费。
 */
public final class EvaluationResultDtos {

    private EvaluationResultDtos() {}

    public record EvaluationResultResponse(
            Long id,
            Long evaluationTaskId,
            Long modelVersionId,
            Long benchmarkId,
            Map<String, Object> overallScores,
            Map<String, Object> categoryScores,
            Integer sampleCount,
            String detailPath,
            Instant completedAt,
            Instant createdAt
    ) {}

    public record LeaderboardEntry(
            Long resultId,
            Long modelVersionId,
            Long benchmarkId,
            Integer sampleCount,
            Double sortScore,
            Map<String, Object> overallScores,
            Instant completedAt
    ) {}

    public record ComparisonResponse(
            Long benchmarkId,
            List<ResultComparisonEntry> results
    ) {}

    public record ResultComparisonEntry(
            Long resultId,
            Long modelVersionId,
            Map<String, Object> overallScores,
            Map<String, Object> categoryScores,
            Integer sampleCount,
            Instant completedAt
    ) {}

    public record EvaluationSampleResponse(
            Long id,
            Long resultId,
            Long modelVersionId,
            String input,
            String expected,
            String actual,
            Boolean correct,
            Double score,
            String category
    ) {}

    public record SamplePage(
            List<EvaluationSampleResponse> items,
            long total,
            int page,
            int size
    ) {}
}
