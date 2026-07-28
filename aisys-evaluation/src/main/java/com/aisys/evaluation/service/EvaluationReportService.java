package com.aisys.evaluation.service;

import com.aisys.evaluation.dto.EvaluationReportDtos.EvaluationReportResponse;
import com.aisys.evaluation.dto.EvaluationReportDtos.RegenerateRequest;

/**
 * 评测报告服务。
 * <p>职责：
 * <ul>
 *   <li>评测完成后异步调用 LLM 生成分析报告</li>
 *   <li>查询 / 下载报告（Markdown / Word）</li>
 *   <li>支持手动重新触发生成</li>
 * </ul>
 */
public interface EvaluationReportService {

    /**
     * 获取某评测任务的报告（不存在则返回 pending 状态的空记录）。
     */
    EvaluationReportResponse getReportByTaskId(Long taskId);

    /**
     * 异步生成报告（由评测完成事件触发，或用户手动触发）。
     *
     * @param taskId 评测任务 ID
     * @param force  是否强制重新生成（覆盖已有 completed 报告）
     */
    void generateReportAsync(Long taskId, boolean force);

    /**
     * 异步生成报告（带显式租户 ID，用于异步线程上下文丢失场景）。
     *
     * @param taskId 评测任务 ID
     * @param force  是否强制重新生成
     * @param tenantId 租户 ID（优先于 UserContext）
     */
    default void generateReportAsync(Long taskId, boolean force, Long tenantId) {
        generateReportAsync(taskId, force);
    }

    /**
     * 获取 Markdown 格式报告内容（用于下载）。
     */
    String getReportMd(Long taskId);

    /**
     * 获取 HTML 格式报告内容（用于 Word 导出）。
     */
    String getReportHtml(Long taskId);

    /**
     * 删除某评测任务的报告（任务删除/重跑时调用）。
     */
    void deleteReport(Long taskId);
}
