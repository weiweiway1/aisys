package com.aisys.evaluation.dto;

import java.time.Instant;

/**
 * 评测报告相关 DTO（record）。
 */
public final class EvaluationReportDtos {

    private EvaluationReportDtos() {}

    /** 报告响应（给前端展示 + 下载）。 */
    public record EvaluationReportResponse(
            Long id,
            Long evaluationTaskId,
            String status,           // pending / generating / completed / failed
            String contentMd,        // Markdown 原文
            String contentHtml,      // HTML（用于 Word 转换）
            String llmModel,
            String promptSummary,
            String errorMessage,
            Instant generatedAt,
            Instant createdAt
    ) {}

    /** 手动触发重新生成报告的请求（前端 POST）。 */
    public record RegenerateRequest(
            boolean force   // true=强制重新生成（覆盖已有报告），false=已有completed则跳过
    ) {}
}
