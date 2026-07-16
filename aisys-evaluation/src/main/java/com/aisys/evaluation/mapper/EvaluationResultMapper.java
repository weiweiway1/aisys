package com.aisys.evaluation.mapper;

import com.aisys.evaluation.entity.EvaluationResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评测结果 Mapper。
 */
@Mapper
public interface EvaluationResultMapper {

    void insert(EvaluationResult result);

    EvaluationResult selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

    List<EvaluationResult> selectByTaskId(@Param("taskId") Long taskId, @Param("tenantId") Long tenantId);

    /**
     * 排行榜：按 benchmark 查询所有 result，由 Java 侧按 sortMetric 排序。
     */
    List<EvaluationResult> selectByBenchmark(@Param("benchmarkId") Long benchmarkId,
                                             @Param("tenantId") Long tenantId);

    List<EvaluationResult> selectByIds(@Param("ids") List<Long> ids, @Param("tenantId") Long tenantId);

    /** 删除某评测任务下的全部结果（rerun 清旧结果 / delete 防孤儿，evaluation_result 无 FK 级联）。 */
    int deleteByTaskId(@Param("taskId") Long taskId, @Param("tenantId") Long tenantId);
}
