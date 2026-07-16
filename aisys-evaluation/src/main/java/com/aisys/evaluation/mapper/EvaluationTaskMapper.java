package com.aisys.evaluation.mapper;

import com.aisys.evaluation.entity.EvaluationTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 评测任务 Mapper（父任务）。
 */
@Mapper
public interface EvaluationTaskMapper {

    void insert(EvaluationTask task);

    EvaluationTask selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);

    List<EvaluationTask> selectPage(@Param("tenantId") Long tenantId,
                                    @Param("projectId") Long projectId,
                                    @Param("benchmarkId") Long benchmarkId,
                                    @Param("status") String status,
                                    @Param("offset") int offset,
                                    @Param("size") int size);

    long count(@Param("tenantId") Long tenantId,
               @Param("projectId") Long projectId,
               @Param("benchmarkId") Long benchmarkId,
               @Param("status") String status);

    int updateStatus(@Param("id") Long id,
                     @Param("tenantId") Long tenantId,
                     @Param("status") String status,
                     @Param("progress") Integer progress,
                     @Param("startedAt") Instant startedAt,
                     @Param("completedAt") Instant completedAt);

    int deleteByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
