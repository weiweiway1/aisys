package com.aisys.evaluation.service;

import com.aisys.common.core.response.PageResult;
import com.aisys.evaluation.dto.EvaluationResultDtos.ComparisonResponse;
import com.aisys.evaluation.dto.EvaluationResultDtos.EvaluationResultResponse;
import com.aisys.evaluation.dto.EvaluationResultDtos.LeaderboardEntry;
import com.aisys.evaluation.dto.EvaluationResultDtos.SamplePage;
import com.aisys.evaluation.dto.EvaluationTaskDtos.EvaluationTaskCreateRequest;
import com.aisys.evaluation.dto.EvaluationTaskDtos.EvaluationTaskResponse;
import com.aisys.evaluation.mq.TaskStatusMessage;

import java.util.List;

/**
 * 评测任务服务：创建（拆分子任务）、启动/停止/重跑、结果查询、排行榜、对比、样本。
 */
public interface EvaluationTaskService {

    EvaluationTaskResponse create(EvaluationTaskCreateRequest request);

    EvaluationTaskResponse getById(Long id);

    PageResult<EvaluationTaskResponse> list(Long projectId, Long benchmarkId, String status, int page, int size);

    void delete(Long id);

    void start(Long id);

    void stop(Long id);

    void rerun(Long id);

    List<EvaluationResultResponse> results(Long id);

    SamplePage samples(Long id, Long resultId, int page, int size);

    List<LeaderboardEntry> leaderboard(Long benchmarkId, String sortBy);

    ComparisonResponse comparison(List<Long> resultIds);

    /**
     * 消费 task.status 后处理子任务状态、聚合父任务、写结果。
     */
    void handleSubtaskStatus(TaskStatusMessage message);

    /**
     * 模型版本发布后自动创建并启动评测任务（事件驱动）。
     * 使用租户下第一个 active benchmark；无活跃 benchmark 时跳过。
     */
    void autoCreateAndStart(Long modelVersionId);
}
