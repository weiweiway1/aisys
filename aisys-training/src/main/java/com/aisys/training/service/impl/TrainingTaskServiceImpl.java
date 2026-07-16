package com.aisys.training.service.impl;

import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.common.core.response.PageResult;
import com.aisys.common.mq.outbox.EventPublisher;
import com.aisys.training.constant.TaskStatus;
import com.aisys.training.constant.TrainingErrorCode;
import com.aisys.training.dto.CheckpointDto;
import com.aisys.training.dto.ResourceSpec;
import com.aisys.training.dto.TrainingTaskDtos;
import com.aisys.training.entity.Checkpoint;
import com.aisys.training.entity.TaskLog;
import com.aisys.training.entity.TaskMetric;
import com.aisys.training.entity.TrainingTask;
import com.aisys.training.mapper.CheckpointMapper;
import com.aisys.training.mapper.TaskLogMapper;
import com.aisys.training.mapper.TaskMetricMapper;
import com.aisys.training.mapper.TrainingTaskMapper;
import com.aisys.training.mq.NotificationEvent;
import com.aisys.training.mq.TaskCommandMessage;
import com.aisys.training.service.TrainingTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 训练任务领域服务实现（DDD 5.5）。
 * <p>关键约束：
 * <ul>
 *   <li>租户作用域的 DB 访问均在 {@code @Transactional}（含 readOnly）内执行，确保 SET LOCAL app.tenant_id 跨语句生效。</li>
 *   <li>任务状态机变更统一走 {@link #requireTransition(TrainingTask, TaskStatus)} 校验。</li>
 *   <li>发消息走 Outbox（EventPublisher），保证「更新 DB + 发消息」事务一致。</li>
 * </ul>
 */
@Service
public class TrainingTaskServiceImpl implements TrainingTaskService {

    private static final Logger log = LoggerFactory.getLogger(TrainingTaskServiceImpl.class);
    private static final int METRICS_MAX_PER_PAGE = 1000;

    private final TrainingTaskMapper taskMapper;
    private final TaskLogMapper taskLogMapper;
    private final TaskMetricMapper taskMetricMapper;
    private final CheckpointMapper checkpointMapper;
    private final EventPublisher eventPublisher;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @Value("${aisys.training.metrics-downsample-seconds:15}")
    private int downsampleSeconds;

    // 复用最近降采样窗口的内存态（per-task 最近一次写入 ts）—— 简单窗口去抖；多实例下重复写可接受。
    private final Map<Long, Instant> lastMetricTs = new java.util.concurrent.ConcurrentHashMap<>();

    public TrainingTaskServiceImpl(TrainingTaskMapper taskMapper,
                                   TaskLogMapper taskLogMapper,
                                   TaskMetricMapper taskMetricMapper,
                                   CheckpointMapper checkpointMapper,
                                   EventPublisher eventPublisher,
                                   tools.jackson.databind.ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.taskLogMapper = taskLogMapper;
        this.taskMetricMapper = taskMetricMapper;
        this.checkpointMapper = checkpointMapper;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    // ============== 查询 ==============

    @Override
    @Transactional(readOnly = true)
    public PageResult<TrainingTaskDtos.Response> list(Long projectId, String status, String keyword, int page, int size) {
        Long tenantId = UserContext.isPlatformAdmin() ? null : UserContext.getTenantId();
        long total = taskMapper.count(tenantId, projectId, status, keyword);
        int offset = Math.max(0, (page - 1) * size);
        List<TrainingTask> rows = taskMapper.page(tenantId, projectId, status, keyword, offset, size);
        List<TrainingTaskDtos.Response> items = rows.stream().map(this::toResponse).toList();
        return PageResult.of(items, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public TrainingTaskDtos.Response get(Long id) {
        return toResponse(requireOwnedTask(id));
    }

    // ============== 创建 ==============

    @Override
    @Transactional
    public TrainingTaskDtos.Response create(TrainingTaskDtos.Create req) {
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            // 平台超管无租户上下文，training_task.tenant_id NOT NULL，必须显式指定租户
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "缺少租户上下文，无法创建训练任务");
        }
        TrainingTask t = new TrainingTask();
        t.setTenantId(tenantId);
        t.setProjectId(req.projectId());
        t.setName(req.name());
        t.setModelVersionId(req.modelVersionId());
        t.setDatasetVersionId(req.datasetVersionId());
        t.setImage(req.image());
        t.setCommand(req.command());
        t.setResourceSpec(toJson(req.resourceSpec()));
        t.setHyperparameters(toJson(req.hyperparameters()));
        t.setContainerSpec(toJson(req.containerSpec()));
        // image 列可能 NOT NULL：containerSpec.imageName 兜底
        if (t.getImage() == null || t.getImage().isBlank()) {
            Map<String, Object> cs = fromJsonMap(t.getContainerSpec());
            if (cs != null && cs.get("imageName") != null) {
                t.setImage(String.valueOf(cs.get("imageName")));
            } else {
                t.setImage("model-container");
            }
        }
        t.setStatus(TaskStatus.pending.name());
        t.setPriority(req.priority() == null ? 0 : req.priority());
        t.setProgress(0);
        t.setCreatedBy(UserContext.getUserId());
        taskMapper.insert(t);
        log.info("创建训练任务 id={} tenant={} name={}", t.getId(), t.getTenantId(), t.getName());
        return toResponse(taskMapper.selectById(t.getId()));
    }

    // ============== 生命周期动作 ==============

    @Override
    @Transactional
    public TrainingTaskDtos.Response start(Long id) {
        TrainingTask t = requireOwnedTask(id);
        requireTransition(t, TaskStatus.queued);
        // 条件入队（resetForRequeue 仅当当前状态非 queued/running 时写入）：
        // 并发 start/resume 不会重复下发容器——第二个调用读到 0 行直接报错，避免一任务双容器。
        if (taskMapper.resetForRequeue(id) == 0) {
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "任务状态已变更，无法启动（可能已被并发调度，当前非 pending/paused）");
        }
        publishTaskCommand(t, false);
        log.info("启动训练任务 id={} → queued", id);
        return toResponse(taskMapper.selectById(id));
    }

    @Override
    @Transactional
    public TrainingTaskDtos.Response stop(Long id) {
        TrainingTask t = requireOwnedTask(id);
        TaskStatus cur = TaskStatus.valueOf(t.getStatus());
        if (cur.isTerminal()) {
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "任务已处于终态：" + cur);
        }
        // 条件更新：若返回 0 行，说明并发 MQ 状态事件已把任务推入终态（completed/failed），
        // updateStatus 的 WHERE status NOT IN(终态) 守卫使其成为 no-op → 以最新状态为准拒绝重复 stop。
        if (taskMapper.updateStatus(id, TaskStatus.cancelled.name(), null, null, null, null, Instant.now()) == 0) {
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "任务已处于终态，无法停止");
        }
        publishTaskCommand(t, true);
        log.info("停止训练任务 id={} → cancelled", id);
        return toResponse(taskMapper.selectById(id));
    }

    @Override
    @Transactional
    public TrainingTaskDtos.Response pause(Long id) {
        TrainingTask t = requireOwnedTask(id);
        requireTransition(t, TaskStatus.paused);
        // 简化：落一条 active checkpoint 占位（真实场景由 Agent 上报）
        saveCheckpointPlaceholder(t, "pause");
        if (taskMapper.updateStatus(id, TaskStatus.paused.name(), null, null, null, null, null) == 0) {
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "任务已处于终态，无法暂停");
        }
        log.info("暂停训练任务 id={} → paused", id);
        return toResponse(taskMapper.selectById(id));
    }

    @Override
    @Transactional
    public TrainingTaskDtos.Response resume(Long id) {
        TrainingTask t = requireOwnedTask(id);
        requireTransition(t, TaskStatus.queued);
        if (taskMapper.resetForRequeue(id) == 0) {
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "任务状态已变更，无法恢复（可能已被并发调度）");
        }
        publishTaskCommand(t, false);
        log.info("恢复训练任务 id={} → queued（基于最近 checkpoint 调度）", id);
        return toResponse(taskMapper.selectById(id));
    }

    @Override
    @Transactional
    public TrainingTaskDtos.Response updatePriority(Long id, Integer priority) {
        if (priority == null || priority < -100 || priority > 100) {
            throw new BusinessException(TrainingErrorCode.INVALID_PRIORITY);
        }
        TrainingTask t = requireOwnedTask(id);
        taskMapper.updatePriority(id, priority);
        log.info("更新训练任务优先级 id={} priority={}", id, priority);
        return toResponse(taskMapper.selectById(id));
    }

    // ============== 日志/指标/checkpoint 查询 ==============

    @Override
    @Transactional(readOnly = true)
    public PageResult<TrainingTaskDtos.LogLine> logs(Long id, int page, int size) {
        requireOwnedTask(id);
        long total = taskLogMapper.countByTaskId(id);
        int offset = Math.max(0, (page - 1) * size);
        List<TaskLog> rows = taskLogMapper.pageByTaskId(id, offset, size);
        List<TrainingTaskDtos.LogLine> items = rows.stream().map(l ->
                new TrainingTaskDtos.LogLine(l.getId(), l.getTaskId(), l.getLevel(), l.getStep(),
                        l.getMessage(), l.getLoggedAt())).toList();
        return PageResult.of(items, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<TrainingTaskDtos.MetricPoint> metrics(Long id, Instant from, Instant to, int page, int size) {
        requireOwnedTask(id);
        long total = taskMetricMapper.countByTaskIdAndRange(id, from, to);
        int safeSize = Math.min(Math.max(size, 1), METRICS_MAX_PER_PAGE);
        int offset = Math.max(0, (page - 1) * safeSize);
        List<TaskMetric> rows = taskMetricMapper.pageByTaskIdAndRange(id, from, to, offset, safeSize);
        List<TrainingTaskDtos.MetricPoint> items = rows.stream().map(m ->
                new TrainingTaskDtos.MetricPoint(m.getId(), m.getTaskId(), m.getTs(), m.getStep(),
                        fromJson(m.getMetrics()))).toList();
        return PageResult.of(items, total, page, safeSize);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CheckpointDto> checkpoints(Long id) {
        requireOwnedTask(id);
        return checkpointMapper.listByTaskId(id).stream().map(c ->
                new CheckpointDto(c.getId(), c.getTaskId(), c.getStep(), c.getStoragePath(),
                        c.getLoss(), fromJson(c.getMetrics()), c.getIsActive(), c.getCreatedAt())).toList();
    }

    @Override
    @Transactional
    public TrainingTaskDtos.Response rollback(Long id, Long checkpointId) {
        TrainingTask t = requireOwnedTask(id);
        // 终态保护：禁止对 completed/failed/cancelled 的任务回滚，避免复活已结束任务（与状态机一致）。
        if (TaskStatus.valueOf(t.getStatus()).isTerminal()) {
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "任务已处于终态：" + t.getStatus() + "，无法回滚");
        }
        if (TaskStatus.running.name().equalsIgnoreCase(t.getStatus())) {
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "任务运行中，无法回滚（请先暂停/停止）");
        }
        Checkpoint cp = checkpointMapper.selectById(checkpointId);
        if (cp == null || !Objects.equals(cp.getTaskId(), id)) {
            throw new BusinessException(TrainingErrorCode.CHECKPOINT_NOT_FOUND);
        }
        checkpointMapper.clearActiveByTaskId(id);
        checkpointMapper.setActive(checkpointId);
        // 条件入队：并发场景下若状态已非 paused/pending 则 0 行 → 报错，避免重复下发。
        if (taskMapper.resetForRequeue(id) == 0) {
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "任务状态已变更，无法回滚（可能已被并发调度）");
        }
        publishTaskCommand(t, false);
        log.info("回滚训练任务 id={} → checkpoint={} → queued", id, checkpointId);
        return toResponse(taskMapper.selectById(id));
    }

    // ============== MQ 消费入口（由 TaskEventConsumer 调用） ==============

    /**
     * 处理状态事件：running/completed/failed/cancelled/paused，更新 training_task；完成发通知。
     */
    @Transactional
    public void handleStatusEvent(com.aisys.training.mq.TaskStatusEvent ev) {
        // MQ 消费线程无 HTTP 租户上下文：状态更新为平台内部事件，按超管（BYPASSRLS）查询以跨租户定位任务
        Long tenant = ev.getTenantId();
        UserContext.set(new UserContext.CurrentUser(null, tenant,
                tenant == null ? java.util.List.of("ROLE_PLATFORM_ADMIN") : java.util.List.of(),
                null, null, tenant == null));
        try {
            TrainingTask t = taskMapper.selectById(ev.getTaskId());
            if (t == null) {
                log.warn("收到未知任务的状态事件 taskId={} status={}", ev.getTaskId(), ev.getStatus());
                return;
            }
            // 终态保护：已 completed/failed/cancelled 的任务不再被后续状态事件覆盖，
            // 防止乱序/重复 MQ 消息（如迟到的 running）复活已结束的任务。
            if (isTerminalStatus(t.getStatus())) {
                log.info("任务已终态 {}，忽略状态事件 taskId={} incoming={}", t.getStatus(), t.getId(), ev.getStatus());
                return;
            }
            String status = ev.getStatus();
            Instant startedAt = "running".equals(status) ? Instant.now() : null;
            Instant completedAt = ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status))
                    ? Instant.now() : null;
            // 原子终态守卫：updateStatus 带 WHERE status NOT IN(终态)。
            // 返回 0 行表示任务在我们读取后被并发动作（stop / 另一帧）推入终态 → 本次事件为迟到/重复，忽略且不发通知。
            int updated = taskMapper.updateStatus(t.getId(), status, ev.getProgress(), ev.getErrorMessage(),
                    ev.getAssignedNodeId(), startedAt, completedAt);
            if (updated == 0) {
                log.info("任务已终态（或状态未变），忽略状态事件 taskId={} incoming={}", t.getId(), status);
                return;
            }
            log.info("任务状态更新 taskId={} → {} progress={} err={}", t.getId(), status,
                    ev.getProgress(), ev.getErrorMessage());

            if ("completed".equals(status) || "failed".equals(status)) {
                publishNotification(t, status);
            }
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 处理日志事件：批量写入 task_log。
     */
    @Transactional
    public void handleLogEvent(com.aisys.training.mq.TaskLogEvent ev) {
        // 兼容两种来源：批量 entries（List<LogEntry>）或资源侧单条扁平（level/message/step）。
        List<com.aisys.training.mq.TaskLogEvent.LogEntry> entries = ev.getEntries();
        if (entries == null || entries.isEmpty()) {
            if (ev.getMessage() == null || ev.getMessage().isBlank()) return;
            entries = List.of(new com.aisys.training.mq.TaskLogEvent.LogEntry(
                    ev.getLevel(), ev.getStep(), ev.getMessage(), ev.getLoggedAt()));
        }
        Long tenantId = ev.getTenantId() != null ? ev.getTenantId() : UserContext.getTenantId();
        List<TaskLog> rows = new ArrayList<>(entries.size());
        for (com.aisys.training.mq.TaskLogEvent.LogEntry e : entries) {
            TaskLog l = new TaskLog();
            l.setTaskId(ev.getTaskId());
            l.setTenantId(tenantId);
            l.setLevel(e.getLevel());
            l.setStep(e.getStep());
            l.setMessage(e.getMessage());
            l.setLoggedAt(e.getLoggedAt());
            rows.add(l);
        }
        // 分批插入，避免单条 SQL 过长
        int batch = 500;
        for (int i = 0; i < rows.size(); i += batch) {
            taskLogMapper.batchInsert(rows.subList(i, Math.min(i + batch, rows.size())));
        }
    }

    /**
     * 处理指标事件：按窗口降采样后写入 task_metric。
     */
    @Transactional
    public void handleMetricsEvent(com.aisys.training.mq.TaskMetricsEvent ev) {
        Instant now = ev.getTs() != null ? ev.getTs() : Instant.now();
        Instant last = lastMetricTs.get(ev.getTaskId());
        if (last != null && Duration.between(last, now).getSeconds() < downsampleSeconds) {
            return; // 窗口内跳过
        }
        lastMetricTs.put(ev.getTaskId(), now);
        TaskMetric m = new TaskMetric();
        m.setTaskId(ev.getTaskId());
        m.setTenantId(ev.getTenantId() != null ? ev.getTenantId() : UserContext.getTenantId());
        m.setTs(now);
        m.setStep(ev.getStep());
        m.setMetrics(toJson(ev.getMetrics()));
        taskMetricMapper.insert(m);
    }

    // ============== 私有辅助 ==============

    private TrainingTask requireOwnedTask(Long id) {
        TrainingTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(TrainingErrorCode.TASK_NOT_FOUND);
        // RLS 已隔离；超管可跨租户。此处仅做防御性双重校验。
        if (!UserContext.isPlatformAdmin() && !Objects.equals(t.getTenantId(), UserContext.getTenantId())) {
            throw new BusinessException(TrainingErrorCode.TASK_NOT_FOUND);
        }
        return t;
    }

    private void requireTransition(TrainingTask task, TaskStatus target) {
        TaskStatus cur = TaskStatus.valueOf(task.getStatus());
        if (!cur.canTransitionTo(target)) {
            throw new BusinessException(TrainingErrorCode.INVALID_STATUS_TRANSITION,
                    "非法状态变更：" + cur + " → " + target);
        }
    }

    /** 终态判断（大小写兼容，防 MQ 上报的大小写差异）。 */
    private boolean isTerminalStatus(String s) {
        if (s == null) return false;
        String u = s.toUpperCase(java.util.Locale.ROOT);
        return u.equals("COMPLETED") || u.equals("FAILED") || u.equals("CANCELLED") || u.equals("CANCELED");
    }

    private void publishTaskCommand(TrainingTask t, boolean cancel) {
        TaskCommandMessage msg = new TaskCommandMessage();
        msg.setTaskId(t.getId());
        msg.setTaskType("TRAINING");
        ResourceSpec spec = fromJsonSpec(t.getResourceSpec());
        if (spec != null) {
            msg.setResourceSpec(new TaskCommandMessage.ResourceSpecPayload(
                    spec.gpuCount(), spec.cpu(), spec.memoryBytes(), spec.gpuType()));
        }
        msg.setImage(t.getImage());
        msg.setCommand(cancel ? "__CANCEL__" : t.getCommand());
        msg.setPriority(t.getPriority());

        // 「每个模型是一个容器」：从 containerSpec 取镜像/数据集信息，下发 Resource 预签名后转 Agent
        String taskMode = "train";
        Map<String, Object> cs = fromJsonMap(t.getContainerSpec());
        if (cs != null) {
            msg.setImageName((String) cs.get("imageName"));
            msg.setImageTarRelPath((String) cs.get("imageTarRelPath"));
            msg.setDatasetRelPath((String) cs.get("datasetRelPath"));
            msg.setDatasetFormat((String) cs.get("datasetFormat"));
            if (cs.get("taskMode") != null) taskMode = String.valueOf(cs.get("taskMode"));
        }
        msg.setTaskMode(taskMode);

        // 注入容器环境变量（Agent 透传给统一脚本）
        Map<String, String> env = new HashMap<>();
        if (t.getHyperparameters() != null && !t.getHyperparameters().isBlank()) {
            env.put("AISYS_HYPERPARAMS", t.getHyperparameters());
        }
        env.put("AISYS_TASK", taskMode);
        if (msg.getDatasetFormat() != null) env.put("AISYS_DATASET_FORMAT", msg.getDatasetFormat());
        msg.setEnv(env);

        String routingKey = "task.command.train." + t.getId();
        eventPublisher.publish(msg, CommonConstants.EXCHANGE_TASK_COMMAND, routingKey,
                "TRAINING_TASK", String.valueOf(t.getId()));
    }

    private void publishNotification(TrainingTask t, String status) {
        NotificationEvent n = new NotificationEvent();
        n.setRecipientUserId(t.getCreatedBy());
        n.setType("completed".equals(status) ? "TASK_COMPLETED" : "TASK_FAILED");
        n.setTitle("completed".equals(status) ? "训练任务完成" : "训练任务失败");
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", t.getId());
        data.put("taskName", t.getName());
        data.put("status", status);
        n.setData(data);
        n.setContent(String.format("任务「%s」(#%d) %s", t.getName(), t.getId(),
                "completed".equals(status) ? "已成功完成" : "已失败"));
        String routingKey = "notification.task." + t.getId();
        eventPublisher.publish(n, CommonConstants.EXCHANGE_NOTIFICATION_EVENT, routingKey,
                "TRAINING_TASK", String.valueOf(t.getId()));
    }

    private void saveCheckpointPlaceholder(TrainingTask t, String reason) {
        Checkpoint cp = new Checkpoint();
        cp.setTaskId(t.getId());
        cp.setTenantId(t.getTenantId());
        cp.setStep(0L);
        cp.setStoragePath("internal://pause/" + t.getId());
        cp.setLoss(null);
        cp.setMetrics(toJson(Map.of("reason", reason)));
        cp.setIsActive(true);
        checkpointMapper.clearActiveByTaskId(t.getId());
        checkpointMapper.insert(cp);
    }

    private TrainingTaskDtos.Response toResponse(TrainingTask t) {
        return new TrainingTaskDtos.Response(
                t.getId(), t.getTenantId(), t.getProjectId(), t.getName(),
                t.getModelVersionId(), t.getDatasetVersionId(), t.getImage(), t.getCommand(),
                fromJsonSpec(t.getResourceSpec()), fromJson(t.getHyperparameters()),
                fromJson(t.getContainerSpec()),
                t.getStatus(), t.getPriority(), t.getAssignedNodeId(), t.getProgress(),
                t.getErrorMessage(), t.getStartedAt(), t.getCompletedAt(),
                t.getCreatedBy(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }

    // ---- JSON 工具（统一用 tools.jackson） ----

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private Object fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (tools.jackson.core.JacksonException e) {
            return json; // 退化返回原始字符串
        }
    }

    private ResourceSpec fromJsonSpec(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, ResourceSpec.class);
        } catch (tools.jackson.core.JacksonException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (tools.jackson.core.JacksonException e) {
            return null;
        }
    }
}
