package com.aisys.resource.mq;

import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.core.context.UserContext;
import com.aisys.common.mq.outbox.EventPublisher;
import com.aisys.resource.constant.ResourceConstants;
import com.aisys.resource.dto.WebSocketMessage;
import com.aisys.resource.entity.ComputeNode;
import com.aisys.resource.entity.ResourceAllocation;
import com.aisys.resource.service.AgentService;
import com.aisys.resource.mapper.ResourceAllocationMapper;
import com.aisys.resource.mq.ResourceMessages.TaskLogMessage;
import com.aisys.resource.mq.ResourceMessages.TaskMetricsMessage;
import com.aisys.resource.mq.ResourceMessages.TaskStatusMessage;
import com.aisys.resource.service.scheduler.SchedulingServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Agent 上报转发实现：经 Outbox 投递到 task.status/task.log/task.metrics exchange。
 * <p>result 上报（任务终态）会同时释放对应 resource_allocation 租约。
 */
@Service
public class ForwardServiceImpl implements ForwardService {

    private static final Logger log = LoggerFactory.getLogger(ForwardServiceImpl.class);

    private final EventPublisher eventPublisher;
    private final AgentService agentService;
    private final ResourceAllocationMapper allocationMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ForwardServiceImpl(EventPublisher eventPublisher,
                              AgentService agentService,
                              ResourceAllocationMapper allocationMapper,
                              StringRedisTemplate redis,
                              ObjectMapper objectMapper) {
        this.eventPublisher = eventPublisher;
        this.agentService = agentService;
        this.allocationMapper = allocationMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void forwardStatus(String agentId, WebSocketMessage msg) {
        ComputeNode node = agentService.getNodeByAgentId(agentId);
        TaskStatusMessage m = new TaskStatusMessage();
        m.setTaskType(msg.taskType());
        m.setTaskId(msg.taskId());
        String statusText = textOf(msg.data(), "status");
        m.setStatus(statusText);
        // 进度：Agent status/result 帧在 data.progress（0-100）携带，透传给下游更新任务进度。
        m.setProgress(asInt(msg.data() == null ? null : msg.data().get("progress")));
        // 失败原因：Agent result 帧把错误文本放 data.error（非 message 字段）。提取到 message，
        // 否则下游 training/evaluation 的 errorMessage 字段恒为空，error_message 列与失败通知丢失。
        String err = textOf(msg.data(), "error");
        m.setMessage((err != null && !err.isBlank()) ? err : msg.message());
        m.setNodeId(node == null ? null : node.getId());
        // 恢复评测子任务关联（subtaskId/modelVersionId，调度时缓存）+ 提取结果分数（来自 Agent result 帧）
        enrichEvalStatus(m, msg);
        enrichTrainingStatus(m, msg);
        publish(m, CommonConstants.EXCHANGE_TASK_STATUS,
                ResourceConstants.ROUTING_KEY_TASK_STATUS_PREFIX + lower(msg.taskType()),
                msg);
    }

    /**
     * 评测专用：从调度缓存恢复 subtaskId/modelVersionId，从 Agent result 帧提取分数字段。
     * TRAINING 任务无 subtaskId（缓存为空），各字段留 null，不影响训练消费侧。
     */
    @SuppressWarnings("unchecked")
    private void enrichEvalStatus(TaskStatusMessage m, WebSocketMessage msg) {
        if (msg.taskType() == null || msg.taskId() == null) return;
        // msg.taskId() 是调度标识 dispatchId（评测=子任务 id）。据此恢复 parentTaskId/subtaskId/modelVersionId。
        try {
            String ctx = redis.opsForValue().get(SchedulingServiceImpl.taskCtxKey(msg.taskType(), msg.taskId()));
            if (ctx != null && !ctx.isBlank()) {
                java.util.Map<String, Object> map = objectMapper.readValue(ctx, java.util.Map.class);
                // 下游评测消费侧需“父 evaluation_task id”做聚合：把 m.taskId 从 dispatchId 还原为 parentTaskId。
                // 注意：releaseByTask / publish 的租户恢复仍用 msg.taskId()（dispatchId），与租约键一致，不受影响。
                Long parentTaskId = asLong(map.get("parentTaskId"));
                if (parentTaskId != null) m.setTaskId(parentTaskId);
                m.setSubtaskId(asLong(map.get("subtaskId")));
                m.setModelVersionId(asLong(map.get("modelVersionId")));
            }
        } catch (Exception e) {
            log.debug("恢复任务上下文失败 taskType={} taskId={}: {}", msg.taskType(), msg.taskId(), e.getMessage());
        }
        java.util.Map<String, Object> data = msg.data();
        if (data != null) {
            // 分数提取：Agent 把 run.py 的 result 帧包在 data.result 里，run.py 帧内才是 {metrics:{mAP50,...}}，
            // 即真实分数在 data.result.result.metrics（双层嵌套）；部分容器可能直接 data.result.metrics。
            // 排行榜按 overallScores.<metric> 扁平读取，故把 metrics 提到顶层。
            java.util.Map<String, Object> result = asMap(data.get("result"));
            java.util.Map<String, Object> scores = null;
            if (result != null) {
                scores = asMap(result.get("metrics"));
                if (scores == null) {
                    java.util.Map<String, Object> inner = asMap(result.get("result"));
                    if (inner != null) scores = asMap(inner.get("metrics"));
                }
            }
            m.setOverallScores(scores != null ? scores : result);
            m.setCategoryScores(asMap(data.get("categoryScores")));
            m.setSampleCount(asInt(data.get("sampleCount")));
            Object dp = data.get("detailPath");
            m.setDetailPath(dp == null ? null : String.valueOf(dp));
        }
    }


    private void enrichTrainingStatus(TaskStatusMessage m, WebSocketMessage msg) {
        if (!"TRAINING".equalsIgnoreCase(msg.taskType()) || msg.taskId() == null) return;
        String status = m.getStatus();
        if (!"completed".equalsIgnoreCase(status)) return;
        java.util.Map<String, Object> data = msg.data();
        if (data == null) return;
        String storagePath = firstText(data, "outputPath", "artifactPath", "bestWeightPath", "checkpointPath", "storagePath");
        Object uploaded = data.get("outputUploaded");
        if ((storagePath == null || storagePath.isBlank()) && Boolean.TRUE.equals(uploaded)) {
            storagePath = "training/" + msg.taskId() + "/output/best.pt";
        }
        if (storagePath == null || storagePath.isBlank()) return;
        java.util.Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("storagePath", storagePath);
        extra.put("outputPath", storagePath);
        extra.put("artifactName", "best.pt");
        Object step = data.get("step");
        if (step != null) extra.put("step", step);
        Object result = data.get("result");
        if (result != null) extra.put("result", result);
        m.setExtra(extra);
    }

    private String firstText(java.util.Map<String, Object> data, String... keys) {
        if (data == null) return null;
        for (String key : keys) {
            Object value = data.get(key);
            if (value != null) {
                String text = String.valueOf(value);
                if (!text.isBlank()) return text;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> asMap(Object o) {
        if (o instanceof java.util.Map<?, ?> mm) return (java.util.Map<String, Object>) mm;
        return null;
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.valueOf(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    @Override
    @Transactional
    public void forwardLog(String agentId, WebSocketMessage msg) {
        ComputeNode node = agentService.getNodeByAgentId(agentId);
        TaskLogMessage m = new TaskLogMessage();
        m.setTaskType(msg.taskType());
        m.setTaskId(msg.taskId());
        m.setLevel(msg.level() == null ? "INFO" : msg.level());
        m.setMessage(msg.message());
        m.setNodeId(node == null ? null : node.getId());
        publish(m, CommonConstants.EXCHANGE_TASK_LOG,
                ResourceConstants.ROUTING_KEY_TASK_LOG_PREFIX + lower(msg.taskType()),
                msg);
    }

    @Override
    @Transactional
    public void forwardMetrics(String agentId, WebSocketMessage msg) {
        ComputeNode node = agentService.getNodeByAgentId(agentId);
        TaskMetricsMessage m = new TaskMetricsMessage();
        m.setTaskType(msg.taskType());
        m.setTaskId(msg.taskId());
        m.setNodeId(node == null ? null : node.getId());
        m.setMetrics(msg.metrics());
        publish(m, CommonConstants.EXCHANGE_TASK_METRICS,
                ResourceConstants.ROUTING_KEY_TASK_METRICS_PREFIX + lower(msg.taskType()),
                msg);
    }

    @Override
    @Transactional
    public void forwardResult(String agentId, WebSocketMessage msg) {
        // 终态：转发为 status（SUCCEEDED/FAILED）并释放租约
        forwardStatus(agentId, msg);
        if (msg.taskType() != null && msg.taskId() != null) {
            int n = allocationMapper.releaseByTask(msg.taskType(), msg.taskId());
            log.info("任务终态释放租约 taskType={} taskId={} released={}", msg.taskType(), msg.taskId(), n);
        }
    }

    private void publish(com.aisys.common.mq.message.BaseMessage m, String topic, String routingKey, WebSocketMessage src) {
        // WS 处理线程无 UserContext：从 resource_allocation 恢复租户，供 EventPublisher 写入 tenantId（下游 RLS 需要）
        Long tenantId = null;
        if (src.taskType() != null && src.taskId() != null) {
            try {
                ResourceAllocation a = allocationMapper.selectHeldByTask(src.taskType(), src.taskId());
                if (a != null) tenantId = a.getTenantId();
            } catch (Exception ignore) { }
        }
        boolean admin = tenantId == null;
        UserContext.set(new UserContext.CurrentUser(null, tenantId,
                admin ? java.util.List.of("ROLE_PLATFORM_ADMIN") : java.util.List.of(),
                null, null, admin));
        try {
            eventPublisher.publish(m, topic, routingKey, "TASK",
                    src.taskId() == null ? "" : String.valueOf(src.taskId()));
        } finally {
            UserContext.clear();
        }
    }

    private String textOf(java.util.Map<String, Object> data, String key) {
        return (data == null || !data.containsKey(key)) ? null : String.valueOf(data.get(key));
    }

    private String lower(String s) {
        return s == null ? "task" : s.toLowerCase();
    }
}
