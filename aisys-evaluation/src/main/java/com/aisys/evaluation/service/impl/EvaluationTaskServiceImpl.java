package com.aisys.evaluation.service.impl;

import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.mq.outbox.EventPublisher;
import com.aisys.evaluation.constant.EvaluationConstants;
import com.aisys.evaluation.constant.EvaluationErrorCode;
import com.aisys.evaluation.dto.EvaluationResultDtos.ComparisonResponse;
import com.aisys.evaluation.dto.EvaluationResultDtos.EvaluationResultResponse;
import com.aisys.evaluation.dto.EvaluationResultDtos.EvaluationSampleResponse;
import com.aisys.evaluation.dto.EvaluationResultDtos.LeaderboardEntry;
import com.aisys.evaluation.dto.EvaluationResultDtos.ResultComparisonEntry;
import com.aisys.evaluation.dto.EvaluationResultDtos.SamplePage;
import com.aisys.evaluation.dto.EvaluationTaskDtos.EvaluationTaskCreateRequest;
import com.aisys.evaluation.dto.EvaluationTaskDtos.EvaluationTaskResponse;
import com.aisys.evaluation.dto.EvaluationTaskDtos.SubtaskSummary;
import com.aisys.evaluation.entity.Benchmark;
import com.aisys.evaluation.entity.EvaluationResult;
import com.aisys.evaluation.entity.EvaluationSubtask;
import com.aisys.evaluation.entity.EvaluationTask;
import com.aisys.evaluation.mapper.BenchmarkMapper;
import com.aisys.evaluation.mapper.EvaluationResultMapper;
import com.aisys.evaluation.mapper.EvaluationSubtaskMapper;
import com.aisys.evaluation.mapper.EvaluationTaskMapper;
import com.aisys.evaluation.mq.EvaluationCommandMessage;
import com.aisys.evaluation.mq.TaskStatusMessage;
import com.aisys.evaluation.service.EvaluationReportService;
import com.aisys.evaluation.service.EvaluationTaskService;
import com.aisys.evaluation.util.EvalJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 评测任务服务实现（DDD 5.6）。
 * <p>核心流程：
 * <ul>
 *   <li>create：为每个 model_version 创建 evaluation_subtask（拆分）。</li>
 *   <li>start：每个子任务发布一条 task.command(EVALUATION) → resource 调度执行。</li>
 *   <li>handleSubtaskStatus：消费 task.status，更新子任务；全成功→completed；
 *       部分失败→completed_with_errors；全失败→failed。聚合写 evaluation_result。</li>
 * </ul>
 * 所有租户作用域 DB 访问均在 @Transactional 内（含 readOnly），保证 RLS SET LOCAL 生效。
 */
@Service
public class EvaluationTaskServiceImpl implements EvaluationTaskService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationTaskServiceImpl.class);

    private final EvaluationTaskMapper taskMapper;
    private final EvaluationSubtaskMapper subtaskMapper;
    private final EvaluationResultMapper resultMapper;
    private final BenchmarkMapper benchmarkMapper;
    private final EventPublisher eventPublisher;
    private final com.aisys.evaluation.client.ModelClient modelClient;
    private final com.aisys.evaluation.client.DatasetClient datasetClient;
    private final EvaluationReportService reportService;   // 评测报告服务（异步生成）

    public EvaluationTaskServiceImpl(EvaluationTaskMapper taskMapper,
                                     EvaluationSubtaskMapper subtaskMapper,
                                     EvaluationResultMapper resultMapper,
                                     BenchmarkMapper benchmarkMapper,
                                     EventPublisher eventPublisher,
                                     com.aisys.evaluation.client.ModelClient modelClient,
                                     com.aisys.evaluation.client.DatasetClient datasetClient,
                                     EvaluationReportService reportService) {
        this.taskMapper = taskMapper;
        this.subtaskMapper = subtaskMapper;
        this.resultMapper = resultMapper;
        this.benchmarkMapper = benchmarkMapper;
        this.eventPublisher = eventPublisher;
        this.modelClient = modelClient;
        this.datasetClient = datasetClient;
        this.reportService = reportService;
    }

    // ====================== 创建（拆分子任务）======================

    @Override
    @Transactional
    public EvaluationTaskResponse create(EvaluationTaskCreateRequest request) {
        Long tenantId = currentTenant();
        if (request.modelVersionIds() == null || request.modelVersionIds().isEmpty()) {
            throw new BusinessException(EvaluationErrorCode.NO_MODEL_VERSIONS);
        }

        // 校验 benchmark 存在且 active
        Benchmark benchmark = benchmarkMapper.selectByIdAndTenant(request.benchmarkId(), tenantId);
        if (benchmark == null) {
            throw new BusinessException(EvaluationErrorCode.BENCHMARK_NOT_FOUND);
        }
        if (!EvaluationConstants.BENCHMARK_ACTIVE.equalsIgnoreCase(benchmark.getStatus())) {
            throw new BusinessException(EvaluationErrorCode.BENCHMARK_INACTIVE);
        }

        EvaluationTask task = new EvaluationTask();
        task.setTenantId(tenantId);
        task.setProjectId(request.projectId());
        task.setBenchmarkId(request.benchmarkId());
        task.setName(request.name());
        task.setModelVersionIds(EvalJson.toJson(request.modelVersionIds()));
        task.setStatus(EvaluationConstants.STATUS_PENDING);
        task.setProgress(0);
        task.setConfig(request.config());
        task.setCreatedBy(UserContext.getUserId());
        taskMapper.insert(task);

        // 为每个 model_version 创建子任务
        List<EvaluationSubtask> subtasks = new ArrayList<>();
        for (Long mvId : request.modelVersionIds()) {
            EvaluationSubtask st = new EvaluationSubtask();
            st.setParentTaskId(task.getId());
            st.setTenantId(tenantId);
            st.setModelVersionId(mvId);
            st.setStatus(EvaluationConstants.STATUS_PENDING);
            subtaskMapper.insert(st);
            subtasks.add(st);
        }

        return toResponse(task, subtasks);
    }

    // ====================== 查询 ======================

    @Override
    @Transactional(readOnly = true)
    public EvaluationTaskResponse getById(Long id) {
        EvaluationTask task = mustGetTask(id);
        List<EvaluationSubtask> subtasks = subtaskMapper.selectByParentTaskId(id, task.getTenantId());
        return toResponse(task, subtasks);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<EvaluationTaskResponse> list(Long projectId, Long benchmarkId, String status, int page, int size) {
        Long tenantId = currentTenant();
        int offset = (page - 1) * size;
        List<EvaluationTask> items = taskMapper.selectPage(tenantId, projectId, benchmarkId, status, offset, size);
        long total = taskMapper.count(tenantId, projectId, benchmarkId, status);
        return PageResult.of(
                items.stream().map(t -> toResponse(t, subtaskMapper.selectByParentTaskId(t.getId(), tenantId))).toList(),
                total, page, size
        );
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Long tenantId = currentTenant();
        EvaluationTask task = taskMapper.selectByIdAndTenant(id, tenantId);
        if (task == null) {
            throw new BusinessException(EvaluationErrorCode.EVALUATION_TASK_NOT_FOUND);
        }
        // 清理评测结果（evaluation_result 无 FK 级联，否则成孤儿污染排行榜/对比）
        resultMapper.deleteByTaskId(id, tenantId);
        // 清理评测报告
        try { reportService.deleteReport(id); } catch (Exception ignored) {}
        // 子任务由 ON DELETE CASCADE 级联删除
        taskMapper.deleteByIdAndTenant(id, tenantId);
    }

    // ====================== 启动 / 停止 / 重跑 ======================

    @Override
    @Transactional
    public void start(Long id) {
        Long tenantId = currentTenant();
        EvaluationTask task = mustGetTask(id, tenantId);
        if (EvaluationConstants.STATUS_RUNNING.equalsIgnoreCase(task.getStatus())) {
            throw new BusinessException(EvaluationErrorCode.TASK_ALREADY_RUNNING);
        }

        Benchmark benchmark = benchmarkMapper.selectByIdAndTenant(task.getBenchmarkId(), tenantId);
        List<EvaluationSubtask> subtasks = subtaskMapper.selectByParentTaskId(id, tenantId);
        if (subtasks.isEmpty()) {
            throw new BusinessException(EvaluationErrorCode.NO_MODEL_VERSIONS);
        }

        // 父任务进入 running
        taskMapper.updateStatus(id, tenantId, EvaluationConstants.STATUS_RUNNING, 0, Instant.now(), null);

        // 每个子任务一条 task.command 指令
        for (EvaluationSubtask st : subtasks) {
            if (EvaluationConstants.STATUS_COMPLETED.equalsIgnoreCase(st.getStatus())
                    || EvaluationConstants.STATUS_COMPLETED_WITH_ERRORS.equalsIgnoreCase(st.getStatus())) {
                // 已完成的子任务跳过（支持断点续跑）
                continue;
            }
            subtaskMapper.updateStatus(st.getId(), tenantId, EvaluationConstants.STATUS_PENDING, null, null, null, null);

            EvaluationCommandMessage msg = new EvaluationCommandMessage();
            msg.setTaskId(id);
            msg.setSubtaskId(st.getId());
            msg.setBenchmarkId(task.getBenchmarkId());
            msg.setModelVersionId(st.getModelVersionId());
            msg.setDatasetVersionIds(EvalJson.toLongList(benchmark == null ? null : benchmark.getDatasetVersionIds()));
            msg.setPromptTemplate(benchmark == null ? null : benchmark.getPromptTemplate());
            msg.setMetricsConfig(benchmark == null ? null : benchmark.getMetricsConfig());
            msg.setEvalConfig(benchmark == null ? null : benchmark.getEvalConfig());
            msg.setTenantId(tenantId);
            // 解析容器描述：模型版本 → 镜像 tar + imageName；数据集版本 → storagePath（与训练同一机制）
            fillContainerSpec(msg, st.getModelVersionId(),
                    EvalJson.toLongList(benchmark == null ? null : benchmark.getDatasetVersionIds()));
            // 发布到 task.command exchange（Outbox 模式，事务内写入）
            eventPublisher.publish(msg, CommonConstants.EXCHANGE_TASK_COMMAND,
                    EvaluationRabbitConfig_ROUTING_EVAL_COMMAND(),
                    "evaluation-task", String.valueOf(id));
            log.info("[start] 发布评测指令 taskId={} subtaskId={} modelVersionId={} image={} dataset={}",
                    id, st.getId(), st.getModelVersionId(), msg.getImageName(), msg.getDatasetRelPath());
        }
    }

    @Override
    @Transactional
    public void stop(Long id) {
        Long tenantId = currentTenant();
        EvaluationTask task = mustGetTask(id, tenantId);
        if (!isLive(task.getStatus())) {
            throw new BusinessException(EvaluationErrorCode.TASK_NOT_RUNNING);
        }
        // 取消所有未完成子任务
        subtaskMapper.cancelByParentTaskId(id, tenantId);
        taskMapper.updateStatus(id, tenantId, EvaluationConstants.STATUS_STOPPED, task.getProgress(), null, Instant.now());
        log.info("[stop] 停止评测任务 id={}", id);
    }

    @Override
    @Transactional
    public void rerun(Long id) {
        Long tenantId = currentTenant();
        EvaluationTask task = mustGetTask(id, tenantId);
        List<EvaluationSubtask> subtasks = subtaskMapper.selectByParentTaskId(id, tenantId);
        // 清理上一次运行的评测结果（否则 writeResultIfAbsent 命中旧结果，新分数被丢弃）
        resultMapper.deleteByTaskId(id, tenantId);
        // 清理上一次的报告（否则用户看到过期报告且不会重新生成）
        try { reportService.deleteReport(id); } catch (Exception ignored) {}
        // 重置所有子任务为 pending
        for (EvaluationSubtask st : subtasks) {
            subtaskMapper.updateStatus(st.getId(), tenantId, EvaluationConstants.STATUS_PENDING, null, null, null, null);
        }
        taskMapper.updateStatus(id, tenantId, EvaluationConstants.STATUS_PENDING, 0, null, null);
        log.info("[rerun] 重置评测任务 id={} 子任务数={}", id, subtasks.size());
        // 直接进入启动流程
        start(id);
    }

    // ====================== 状态消费 / 聚合 / 写结果 ======================

    @Override
    @Transactional
    public void handleSubtaskStatus(TaskStatusMessage message) {
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null && message.getTenantId() != null) {
            // 异步消费线程可能无 UserContext（HeaderAuthFilter 仅 servlet 链路生效）；
            // task.status 由 MQ 线程消费，需显式设置租户上下文以走 app 连接池（RLS）。
            tenantId = message.getTenantId();
            UserContext.set(new UserContext.CurrentUser(
                    null, tenantId, List.of(), "system-eval-consumer", message.getTraceId(), false));
        }
        if (tenantId == null) {
            log.warn("[handleSubtaskStatus] 无法确定租户，丢弃消息 subtaskId={}", message.getSubtaskId());
            return;
        }
        try {
            Long subtaskId = message.getSubtaskId();
            EvaluationSubtask st = subtaskMapper.selectByIdAndTenant(subtaskId, tenantId);
            if (st == null) {
                log.warn("[handleSubtaskStatus] 子任务不存在 subtaskId={} tenantId={}", subtaskId, tenantId);
                return;
            }
            // 更新子任务状态
            Instant now = Instant.now();
            String status = normalizeStatus(message.getStatus());
            Instant startedAt = EvaluationConstants.STATUS_RUNNING.equalsIgnoreCase(status) ? now : null;
            Instant completedAt = isTerminal(status) ? now : null;
            subtaskMapper.updateStatus(subtaskId, tenantId, status,
                    message.getAssignedNodeId(),
                    message.getErrorMessage(),
                    startedAt, completedAt);

            // 仅在子任务终态时聚合父任务
            if (!isTerminal(status)) {
                return;
            }

            // 写评测结果（每模型一条，仅 completed 才有有效分数；failed 写空分数占位以便完整呈现）
            writeResultIfAbsent(message, st, tenantId, now);

            // 聚合父任务状态
            aggregateParent(st.getParentTaskId(), tenantId);
        } finally {
            UserContext.clear();
        }
    }

    private void writeResultIfAbsent(TaskStatusMessage message, EvaluationSubtask st, Long tenantId, Instant now) {
        // 同一 task + model 已有结果则不重复写（幂等）
        EvaluationTask task = taskMapper.selectByIdAndTenant(st.getParentTaskId(), tenantId);
        if (task == null) return;
        List<EvaluationResult> existing = resultMapper.selectByTaskId(task.getId(), tenantId);
        boolean has = existing.stream().anyMatch(r -> Objects.equals(r.getModelVersionId(), st.getModelVersionId()));
        if (has) return;

        EvaluationResult r = new EvaluationResult();
        r.setEvaluationTaskId(task.getId());
        r.setTenantId(tenantId);
        r.setModelVersionId(st.getModelVersionId());
        r.setBenchmarkId(task.getBenchmarkId());
        r.setOverallScores(EvalJson.toJson(message.getOverallScores()));
        r.setCategoryScores(EvalJson.toJson(message.getCategoryScores()));
        r.setSampleCount(message.getSampleCount());
        r.setDetailPath(message.getDetailPath());
        r.setCompletedAt(now);
        resultMapper.insert(r);
    }

    private void aggregateParent(Long parentTaskId, Long tenantId) {
        EvaluationTask parent = taskMapper.selectByIdAndTenant(parentTaskId, tenantId);
        if (parent == null) return;
        // 已显式停止（stop）的任务，不被迟到的子任务终态复活成 completed/failed
        if (EvaluationConstants.STATUS_STOPPED.equalsIgnoreCase(parent.getStatus())) {
            log.info("[aggregateParent] 父任务已停止，跳过聚合 taskId={}", parentTaskId);
            return;
        }
        List<EvaluationSubtaskMapper.StatusCount> counts = subtaskMapper.countByStatus(parentTaskId, tenantId);
        int completed = 0, failed = 0, pendingOrRunning = 0, total = 0;
        for (EvaluationSubtaskMapper.StatusCount c : counts) {
            int n = c.getCnt() == null ? 0 : c.getCnt().intValue();
            total += n;
            String s = c.getStatus();
            if (EvaluationConstants.STATUS_COMPLETED.equalsIgnoreCase(s)) {
                completed += n;
            } else if (EvaluationConstants.STATUS_FAILED.equalsIgnoreCase(s)
                    || EvaluationConstants.STATUS_CANCELED.equalsIgnoreCase(s)) {
                failed += n;
            } else {
                pendingOrRunning += n;
            }
        }
        // 仍有未终态子任务 → 父任务保持 running，更新进度
        int progress = total == 0 ? 0 : (int) Math.round(((completed + failed) * 100.0) / total);
        if (pendingOrRunning > 0) {
            taskMapper.updateStatus(parentTaskId, tenantId, EvaluationConstants.STATUS_RUNNING, progress, null, null);
            return;
        }
        // 全部终态 → 聚合
        String finalStatus;
        if (failed == 0) {
            finalStatus = EvaluationConstants.STATUS_COMPLETED;
        } else if (completed == 0) {
            finalStatus = EvaluationConstants.STATUS_FAILED;
        } else {
            finalStatus = EvaluationConstants.STATUS_COMPLETED_WITH_ERRORS;
        }
        taskMapper.updateStatus(parentTaskId, tenantId, finalStatus, 100, null, Instant.now());
        log.info("[aggregateParent] 父任务聚合完成 taskId={} status={} completed={} failed={}",
                parentTaskId, finalStatus, completed, failed);

        // ★ 评测完成 → 异步触发 LLM 报告生成（不阻塞主流程）
        if (EvaluationConstants.STATUS_COMPLETED.equals(finalStatus)
                || EvaluationConstants.STATUS_COMPLETED_WITH_ERRORS.equals(finalStatus)) {
            try {
                reportService.generateReportAsync(parentTaskId, false, tenantId);
            } catch (Exception e) {
                // 报告生成失败不影响评测流程
                log.warn("[aggregateParent] 触发报告生成失败（非致命）taskId={} : {}", parentTaskId, e.getMessage());
            }
        }
    }

    // ====================== 结果 / 样本 / 排行榜 / 对比 ======================

    @Override
    @Transactional(readOnly = true)
    public List<EvaluationResultResponse> results(Long id) {
        Long tenantId = currentTenant();
        EvaluationTask task = mustGetTask(id, tenantId);
        return resultMapper.selectByTaskId(task.getId(), tenantId).stream()
                .map(this::toResultResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SamplePage samples(Long id, Long resultId, int page, int size) {
        Long tenantId = currentTenant();
        // 简化：评测样本明细由 resource 写入 detail_path（对象存储），本服务不落样本明细表。
        // 此处分页返回占位结构（按 result 维度），真实明细由前端通过 storage presign 下载 detail_path。
        EvaluationResult result = null;
        if (resultId != null) {
            result = resultMapper.selectByIdAndTenant(resultId, tenantId);
        } else {
            List<EvaluationResult> rs = resultMapper.selectByTaskId(id, tenantId);
            if (!rs.isEmpty()) result = rs.get(0);
        }
        List<EvaluationSampleResponse> items = List.of();
        long total = 0;
        if (result != null) {
            total = result.getSampleCount() == null ? 0 : result.getSampleCount();
            items = List.of(new EvaluationSampleResponse(
                    result.getId(), result.getId(), result.getModelVersionId(),
                    null, null, null, null, null, null));
        }
        return new SamplePage(items, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaderboardEntry> leaderboard(Long benchmarkId, String sortBy) {
        Long tenantId = currentTenant();
        String metric = (sortBy == null || sortBy.isBlank())
                ? EvaluationConstants.DEFAULT_SORT_METRIC : sortBy;
        List<EvaluationResult> rs = resultMapper.selectByBenchmark(benchmarkId, tenantId);

        // ★ 当未指定排序指标（或为空）时，从数据中自动推断首个可用的数值型指标
        if (metric.isEmpty() && !rs.isEmpty()) {
            metric = detectDefaultMetric(rs);
        }

        // ★ 批量获取模型名称（通过 Feign 调用 model 服务）
        Map<Long, Map<String, Object>> modelInfoMap = batchFetchModelInfo(rs);

        final String finalMetric = metric;
        List<LeaderboardEntry> entries = rs.stream().map(r -> {
            Map<String, Object> overall = EvalJson.toMap(r.getOverallScores());
            Double score = extractMetric(overall, finalMetric);

            // 从批量查询结果中取模型名称
            Map<String, Object> minfo = modelInfoMap.get(r.getModelVersionId());
            String modelName = extractModelName(minfo);
            String modelVer = extractModelVersion(minfo);

            return new LeaderboardEntry(r.getId(), r.getModelVersionId(), modelName, modelVer,
                    r.getBenchmarkId(), r.getSampleCount(), score, overall, r.getCompletedAt());
        }).toList();

        // 按指标降序（null 排末尾）
        List<LeaderboardEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(LeaderboardEntry::sortScore,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return sorted;
    }

    /**
     * 批量获取模型版本信息（避免 N+1 Feign 调用）。
     * 返回 {modelVersionId → {name, version, modelName, ...}} 的映射。
     */
    private Map<Long, Map<String, Object>> batchFetchModelInfo(List<EvaluationResult> results) {
        Map<Long, Map<String, Object>> map = new java.util.HashMap<>();
        if (results == null || results.isEmpty()) return map;

        // 去重收集所有 modelVersionId
        Set<Long> ids = results.stream()
                .map(EvaluationResult::getModelVersionId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        for (Long vid : ids) {
            try {
                var resp = modelClient.getVersionById(vid);
                if (resp != null && resp.isSuccess() && resp.data() != null) {
                    map.put(vid, resp.data());
                }
            } catch (Exception e) {
                log.warn("[leaderboard] 获取模型版本信息失败 versionId={} : {}", vid, e.getMessage());
            }
        }
        return map;
    }

    /** 从 model 服务返回的 Map 中提取模型名称。优先级：modelName > name > model_name > null */
    private static String extractModelName(Map<String, Object> info) {
        if (info == null) return null;
        for (String key : new String[]{"modelName", "name", "model_name"}) {
            Object v = info.get(key);
            if (v instanceof String s && !s.isBlank()) return s.trim();
        }
        return null;
    }

    /** 从 model 服务返回的 Map 中提取版本号。优先级：version > versionTag > ver > null */
    private static String extractModelVersion(Map<String, Object> info) {
        if (info == null) return null;
        for (String key : new String[]{"version", "versionTag", "ver"}) {
            Object v = info.get(key);
            if (v != null) return String.valueOf(v).trim();
        }
        return null;
    }

    /**
     * 从所有评测结果中自动推断默认排序指标。
     * 优先级：top1_acc > accuracy > mAP50 > 首个数值型字段。
     */
    private String detectDefaultMetric(List<EvaluationResult> results) {
        // 优先使用分类/检测通用的高优指标
        java.util.List<String> preferred = java.util.List.of(
                "top1_acc", "accuracy", "mAP50", "mAP50-95",
                "precision", "recall", "top5_acc");
        for (String p : preferred) {
            for (EvaluationResult r : results) {
                Map<String, Object> m = EvalJson.toMap(r.getOverallScores());
                if (m != null && m.get(p) instanceof Number) return p;
            }
        }
        // 兜底：取任意数值型字段的第一个 key
        for (EvaluationResult r : results) {
            Map<String, Object> m = EvalJson.toMap(r.getOverallScores());
            if (m != null) {
                for (java.util.Map.Entry<String, Object> e : m.entrySet()) {
                    if (e.getValue() instanceof Number) return e.getKey();
                }
            }
        }
        return "top1_acc";  // 终极兜底
    }

    @Override
    @Transactional(readOnly = true)
    public ComparisonResponse comparison(List<Long> resultIds) {
        Long tenantId = currentTenant();
        if (resultIds == null || resultIds.isEmpty()) {
            throw new BusinessException(EvaluationErrorCode.INVALID_RESULT_IDS);
        }
        List<EvaluationResult> rs = resultMapper.selectByIds(resultIds, tenantId);
        if (rs.isEmpty()) {
            throw new BusinessException(EvaluationErrorCode.RESULT_NOT_FOUND);
        }
        Long benchmarkId = rs.get(0).getBenchmarkId();
        List<ResultComparisonEntry> entries = rs.stream()
                .map(r -> new ResultComparisonEntry(r.getId(), r.getModelVersionId(),
                        EvalJson.toMap(r.getOverallScores()),
                        EvalJson.toMap(r.getCategoryScores()),
                        r.getSampleCount(), r.getCompletedAt()))
                .toList();
        return new ComparisonResponse(benchmarkId, entries);
    }

    // ====================== helpers ======================

    private EvaluationTask mustGetTask(Long id) {
        return mustGetTask(id, currentTenant());
    }

    private EvaluationTask mustGetTask(Long id, Long tenantId) {
        EvaluationTask task = taskMapper.selectByIdAndTenant(id, tenantId);
        if (task == null) {
            throw new BusinessException(EvaluationErrorCode.EVALUATION_TASK_NOT_FOUND);
        }
        return task;
    }

    private EvaluationTaskResponse toResponse(EvaluationTask t, List<EvaluationSubtask> subtasks) {
        // 查询评测集名称
        String benchmarkName = null;
        if (t.getBenchmarkId() != null) {
            Benchmark bm = benchmarkMapper.selectByIdAndTenant(t.getBenchmarkId(), t.getTenantId());
            if (bm != null) {
                benchmarkName = bm.getName();
            }
        }

        List<SubtaskSummary> summaries = subtasks.stream()
                .map(s -> new SubtaskSummary(s.getId(), s.getModelVersionId(), s.getStatus(),
                        s.getAssignedNodeId(), s.getErrorMessage(), s.getStartedAt(), s.getCompletedAt()))
                .toList();
        return new EvaluationTaskResponse(
                t.getId(), t.getProjectId(), t.getBenchmarkId(), benchmarkName, t.getName(),
                EvalJson.toLongList(t.getModelVersionIds()),
                t.getStatus(), t.getProgress(), t.getConfig(),
                t.getStartedAt(), t.getCompletedAt(), t.getCreatedBy(),
                t.getCreatedAt(), t.getUpdatedAt(), summaries
        );
    }

    private EvaluationResultResponse toResultResponse(EvaluationResult r) {
        return new EvaluationResultResponse(r.getId(), r.getEvaluationTaskId(),
                r.getModelVersionId(), r.getBenchmarkId(),
                EvalJson.toMap(r.getOverallScores()),
                EvalJson.toMap(r.getCategoryScores()),
                r.getSampleCount(), r.getDetailPath(),
                r.getCompletedAt(), r.getCreatedAt());
    }

    private Double extractMetric(Map<String, Object> overall, String metric) {
        if (overall == null) return null;
        Object v = overall.get(metric);
        if (v != null) {
            // ★ 跳过数组/集合/嵌套对象（如 confusion_matrix 是二维数组，不能作为排序分）
            if (v instanceof Number n) return n.doubleValue();
            if (v instanceof java.util.Collection || v.getClass().isArray()) return null;
            try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
        }
        // 兜底：指定指标缺失时，取第一个可用的**纯数值**字段（跳过数组/嵌套对象等非数值）
        for (Object vv : overall.values()) {
            if (vv instanceof Number n) return n.doubleValue();
        }
        return null;
    }

    private static boolean isLive(String status) {
        return EvaluationConstants.STATUS_RUNNING.equalsIgnoreCase(status)
                || EvaluationConstants.STATUS_PENDING.equalsIgnoreCase(status);
    }

    private static boolean isTerminal(String status) {
        return EvaluationConstants.STATUS_COMPLETED.equalsIgnoreCase(status)
                || EvaluationConstants.STATUS_COMPLETED_WITH_ERRORS.equalsIgnoreCase(status)
                || EvaluationConstants.STATUS_FAILED.equalsIgnoreCase(status)
                || EvaluationConstants.STATUS_CANCELED.equalsIgnoreCase(status);
    }

    private static String normalizeStatus(String s) {
        if (s == null) return EvaluationConstants.STATUS_RUNNING;
        String up = s.toUpperCase();
        return switch (up) {
            case "RUNNING", "STARTED" -> EvaluationConstants.STATUS_RUNNING;
            case "COMPLETED", "SUCCESS", "SUCCEEDED" -> EvaluationConstants.STATUS_COMPLETED;
            case "FAILED", "ERROR" -> EvaluationConstants.STATUS_FAILED;
            case "CANCELED", "CANCELLED", "STOPPED" -> EvaluationConstants.STATUS_CANCELED;
            default -> s;
        };
    }

    private Long currentTenant() {
        Long t = UserContext.getTenantId();
        if (t == null) {
            throw new BusinessException(EvaluationErrorCode.EVALUATION_INTERNAL_ERROR.code(),
                    "缺少租户上下文，无法访问评测资源");
        }
        return t;
    }

    /** 间接引用 EvaluationRabbitConfig 常量（避免循环解释，编译期常量内联）。 */
    private static String EvaluationRabbitConfig_ROUTING_EVAL_COMMAND() {
        return com.aisys.evaluation.config.EvaluationRabbitConfig.ROUTING_EVAL_COMMAND;
    }

    /**
     * 解析容器描述：模型版本 → 镜像 tar relPath + imageName；数据集版本 → storagePath + format。
     * 与训练同一套「每个模型是一个容器」机制。Feign 调用失败仅告警（评测仍下发，Agent 侧无镜像则报错）。
     */
    private void fillContainerSpec(EvaluationCommandMessage msg, Long modelVersionId, java.util.List<Long> datasetVersionIds) {
        if (modelVersionId != null) {
            // 快速失败：模型版本解析失败（已删除 / 服务不可用 / 缺镜像信息）不得继续用 null 镜像下发，
            // 否则 Agent 会跑空/合成数据并写出伪造评测分数污染排行榜。
            boolean resolved = false;
            try {
                var resp = modelClient.getVersionById(modelVersionId);
                if (resp != null && resp.isSuccess() && resp.data() != null) {
                    Map<String, Object> v = resp.data();
                    msg.setImageTarRelPath((String) v.get("storagePath"));
                    Object cfg = v.get("config");
                    if (cfg instanceof Map<?, ?> cm && cm.get("imageName") != null) {
                        msg.setImageName(String.valueOf(cm.get("imageName")));
                    }
                    // 改进：同时检查 storagePath 和 imageName（至少需要 storagePath，但 imageName 为空时警告）
                    resolved = msg.getImageTarRelPath() != null;
                    if (resolved && (msg.getImageName() == null || msg.getImageName().isBlank())) {
                        log.warn("[start] 模型版本缺少 imageName modelVersionId={}（Agent 将尝试从 imageTar 解析或使用默认行为）", modelVersionId);
                    }
                }
            } catch (Exception e) {
                throw new BusinessException(EvaluationErrorCode.EVALUATION_INTERNAL_ERROR,
                        "模型版本解析失败 modelVersionId=" + modelVersionId + ": " + e.getMessage());
            }
            if (!resolved) {
                throw new BusinessException(EvaluationErrorCode.EVALUATION_INTERNAL_ERROR,
                        "模型版本不可用或缺少镜像信息 modelVersionId=" + modelVersionId);
            }
        }
        if (datasetVersionIds != null && !datasetVersionIds.isEmpty()) {
            Long dvid = datasetVersionIds.get(0);
            boolean resolved = false;
            try {
                var resp = datasetClient.getVersionById(dvid);
                if (resp != null && resp.isSuccess() && resp.data() != null) {
                    Map<String, Object> v = resp.data();
                    String sp = (String) v.get("storagePath");
                    msg.setDatasetRelPath(sp);
                    if (sp != null && sp.contains(".")) {
                        msg.setDatasetFormat(sp.substring(sp.lastIndexOf('.') + 1));
                    }
                    resolved = sp != null;
                }
            } catch (Exception e) {
                throw new BusinessException(EvaluationErrorCode.EVALUATION_INTERNAL_ERROR,
                        "数据集版本解析失败 datasetVersionId=" + dvid + ": " + e.getMessage());
            }
            if (!resolved) {
                throw new BusinessException(EvaluationErrorCode.EVALUATION_INTERNAL_ERROR,
                        "数据集版本不可用 datasetVersionId=" + dvid);
            }
        }
    }

    // ====================== 自动评测（模型发布事件触发） ======================

    @Override
    @Transactional
    public void autoCreateAndStart(Long modelVersionId) {
        // 使用 system 用户上下文（MQ 消费线程无 HTTP session）
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            tenantId = 1L; // 默认租户（生产环境应从消息体或配置读取）
            log.warn("[AutoEval] 无法获取租户上下文，使用默认 tenantId={}", tenantId);
        }

        // 1. 查找活跃 benchmark
        Benchmark benchmark = benchmarkMapper.selectActiveByTenant(tenantId);
        if (benchmark == null) {
            log.info("[AutoEval] 无活跃 benchmark，跳过自动评测 modelVersionId={}", modelVersionId);
            return;
        }

        // 2. 创建评测任务
        String taskName = String.format("自动评测-%s-v%s",
                modelNameForVersion(modelVersionId), Instant.now().getEpochSecond());
        EvaluationTaskCreateRequest request = new EvaluationTaskCreateRequest(
                null,   // projectId — 自动评测不需要项目归属
                benchmark.getId(),
                taskName,
                List.of(modelVersionId),
                null    // config
        );

        EvaluationTaskResponse task = create(request);
        log.info("[AutoEval] 已创建评测任务 taskId={} modelVersionId={} benchmarkId={}",
                task.id(), modelVersionId, benchmark.getId());

        // 3. 立即启动
        start(task.id());
        log.info("[AutoEval] 已启动评测任务 taskId={} modelVersionId={}", task.id(), modelVersionId);
    }

    /** 获取模型版本名称（用于自动生成任务名），失败时返回 ID */
    private String modelNameForVersion(Long modelVersionId) {
        try {
            var resp = modelClient.getVersionById(modelVersionId);
            if (resp != null && resp.isSuccess() && resp.data() != null) {
                Map<String, Object> v = resp.data();
                String name = (String) v.get("modelName");
                if (name != null) return name;
                name = (String) v.get("name");
                if (name != null) return name;
            }
        } catch (Exception e) {
            log.debug("[AutoEval] 获取模型名称失败 versionId={}: {}", modelVersionId, e.getMessage());
        }
        return "v" + modelVersionId;
    }
}
