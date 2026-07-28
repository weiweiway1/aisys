package com.aisys.evaluation.mapper;

import com.aisys.evaluation.entity.EvaluationReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 评测报告 Mapper（LLM 生成的分析报告）。
 */
@Mapper
public interface EvaluationReportMapper {

    void insert(EvaluationReport report);

    EvaluationReport selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** 按 taskId 查询最新报告（同一 task_id 只保留一份 completed 报告）。 */
    EvaluationReport selectByTaskId(@Param("taskId") Long taskId, @Param("tenantId") Long tenantId);

    void updateStatusAndContent(@Param("id") Long id,
                                @Param("tenantId") Long tenantId,
                                @Param("status") String status,
                                @Param("contentMd") String contentMd,
                                @Param("contentHtml") String contentHtml,
                                @Param("llmModel") String llmModel,
                                @Param("promptSummary") String promptSummary,
                                @Param("errorMessage") String errorMessage);

    /** 删除某评测任务下的报告（rerun / delete 时清理）。 */
    int deleteByTaskId(@Param("taskId") Long taskId, @Param("tenantId") Long tenantId);
}
