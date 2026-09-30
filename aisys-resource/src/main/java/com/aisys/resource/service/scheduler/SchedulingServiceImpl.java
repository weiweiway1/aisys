package com.aisys.resource.service.scheduler;

import com.aisys.common.redis.lock.DistributedLock;
import com.aisys.common.core.context.UserContext;
import com.aisys.resource.constant.ResourceConstants;
import com.aisys.resource.dto.TaskDtos.RunTaskCommand;
import com.aisys.resource.dto.TaskDtos.TaskCommandMessage;
import com.aisys.resource.dto.TaskDtos.TaskResourceSpec;
import com.aisys.resource.entity.ComputeNode;
import com.aisys.resource.entity.ResourceAllocation;
import com.aisys.resource.mapper.ComputeNodeMapper;
import com.aisys.resource.mapper.NodeHeldAggregate;
import com.aisys.resource.mapper.ResourceAllocationMapper;
import com.aisys.resource.service.impl.NodeInfoParser;
import com.aisys.resource.service.impl.NodeInfoParser.FreeCapacity;
import com.aisys.resource.websocket.AgentWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 调度服务实现（DDD 5.7.3 / 5.7.4）。
 * <ol>
 *   <li>查 status=online 候选节点，按空闲量（总量 − Σheld）与标签过滤。</li>
 *   <li>BestFitStrategy 选最优节点。</li>
 *   <li>在 lock:node:{nodeId} 锁内 double-check 空闲量 → 写 resource_allocation(held)。</li>
 *   <li>经 WebSocket 向 Agent 下发 run_task（含 taskId/taskType/image/command/env/gpuDevices/产出路径）。</li>
 * </ol>
 * <p>平台共享表（不启用 RLS）：调度聚合需以平台身份全局可见所有租户的持有量，
 * 因此调度线程在无 UserContext（@Scheduled/MQ 消费）时走 app 默认池。
 */
@Service
public class SchedulingServiceImpl implements SchedulingService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingServiceImpl.class);

    private final ComputeNodeMapper computeNodeMapper;
    private final ResourceAllocationMapper allocationMapper;
    private final NodeInfoParser parser;
    private final SchedulingStrategy strategy;
    private final DistributedLock distributedLock;
    private final AgentWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;
    private final long leaseSeconds;
    /** 独立事务模板：写/释放租约须先于下发指令提交，避免消费侧事务提交失败导致
     *  “Agent 已在跑但租约被回滚 → GPU 被二次分配”。 */
    private final TransactionTemplate requiresNewTx;
    /** 预签名模型镜像 tar / 数据集对象给 Agent 下载（同一 S3 存储池）。 */
    private final com.aisys.common.s3.service.S3StorageService s3;
    /** 缓存评测子任务关联（taskId↔subtaskId/modelVersionId），状态回写时恢复。 */
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public SchedulingServiceImpl(ComputeNodeMapper computeNodeMapper,
                                 ResourceAllocationMapper allocationMapper,
                                 NodeInfoParser parser,
                                 SchedulingStrategy strategy,
                                 DistributedLock distributedLock,
                                 AgentWebSocketHandler webSocketHandler,
                                 ObjectMapper objectMapper,
                                 PlatformTransactionManager transactionManager,
                                 com.aisys.common.s3.service.S3StorageService s3,
                                 org.springframework.data.redis.core.StringRedisTemplate redis,
                                 @Value("${aisys.resource.schedule-lease-seconds:60}") long leaseSeconds) {
        this.computeNodeMapper = computeNodeMapper;
        this.allocationMapper = allocationMapper;
        this.parser = parser;
        this.strategy = strategy;
        this.distributedLock = distributedLock;
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
        this.s3 = s3;
        this.redis = redis;
        this.leaseSeconds = leaseSeconds;
        TransactionTemplate tpl = new TransactionTemplate(transactionManager);
        tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tpl;
    }

    /**
     * 为 Agent 预签名一个对象（relPath 用租户前缀拼全 key）。镜像 tar / 数据集均较大，
     * 用较长 TTL（6h）避免下载中途过期。relPath 为空或预签名失败返回 null（Agent 侧按缺数据集/镜像处理）。
     */
    private String presign(Long tenantId, String relPath) {
        if (relPath == null || relPath.isBlank()) return null;
        String fullKey = (tenantId == null ? "" : tenantId + "/") + relPath;
        try {
            return s3.presignDownload(s3.bucket(), fullKey, java.time.Duration.ofHours(6));
        } catch (Exception e) {
            log.warn("预签名对象失败 tenant={} relPath={}: {}", tenantId, relPath, e.getMessage());
            return null;
        }
    }

    /** 预签名 PUT 上传 URL（训练产物 best.pt 回传）。relPath 为空或失败返回 null。 */
    private String presignUpload(Long tenantId, String relPath) {
        if (relPath == null || relPath.isBlank()) return null;
        String fullKey = (tenantId == null ? "" : tenantId + "/") + relPath;
        try {
            return s3.presignUpload(s3.bucket(), fullKey);
        } catch (Exception e) {
            log.warn("预签名上传失败 tenant={} relPath={}: {}", tenantId, relPath, e.getMessage());
            return null;
        }
    }

    /**
     * 任务上下文缓存键。注意：key 中的 taskId 实际是“调度标识 dispatchId”——
     * 对 EVALUATION 用 subtaskId（每个子任务唯一），对 TRAINING 用 taskId。这样多个评测子任务不会互相覆盖。
     */
    public static String taskCtxKey(String taskType, Long dispatchId) {
        return "resource:taskctx:" + (taskType == null ? "task" : taskType.toLowerCase()) + ":" + dispatchId;
    }

    /**
     * 缓存任务上下文（按 dispatchId 唯一键）：parentTaskId（评测聚合用）/ subtaskId / modelVersionId。
     * EVALUATION：dispatchId=subtaskId，存 parentTaskId=父 evaluation_task.id。TRAINING：dispatchId=taskId，subtaskId/modelVersionId 为 null。
     */
    private void saveTaskCtx(TaskCommandMessage command, Long dispatchId) {
        if (dispatchId == null) return;
        // 仅评测子任务需要缓存（TRAINING 无 subtaskId/modelVersionId，消费侧直接用 taskId，无需恢复）。
        if (command.subtaskId() == null && command.modelVersionId() == null) return;
        try {
            java.util.Map<String, Object> ctx = new java.util.HashMap<>();
            if (command.taskId() != null) ctx.put("parentTaskId", command.taskId());
            if (command.subtaskId() != null) ctx.put("subtaskId", command.subtaskId());
            if (command.modelVersionId() != null) ctx.put("modelVersionId", command.modelVersionId());
            redis.opsForValue().set(taskCtxKey(command.taskType(), dispatchId),
                    objectMapper.writeValueAsString(ctx), java.time.Duration.ofHours(6));
        } catch (Exception e) {
            log.warn("缓存任务上下文失败 taskType={} dispatchId={}: {}", command.taskType(), dispatchId, e.getMessage());
        }
    }

    @Override
    public Long scheduleAndDispatch(TaskCommandMessage command) {
        TaskResourceSpec spec = command.resourceSpec();
        if (spec == null) {
            spec = new TaskResourceSpec(0, 0, 0L, null);
        }
        final TaskResourceSpec taskSpec = spec;

        // 1. 候选节点：online，排除 maintenance
        List<ComputeNode> onlineNodes = computeNodeMapper.selectOnlineCandidates();
        if (onlineNodes.isEmpty()) {
            log.warn("无在线节点，调度失败 taskType={} taskId={}", command.taskType(), command.taskId());
            return null;
        }

        // 2. 一次性聚合所有候选节点的 held 汇总
        List<Long> nodeIds = onlineNodes.stream().map(ComputeNode::getId).toList();
        List<NodeHeldAggregate> helds = allocationMapper.sumHeldByNodes(nodeIds);
        java.util.Map<Long, NodeHeldAggregate> heldByNode = new java.util.HashMap<>();
        for (NodeHeldAggregate h : helds) heldByNode.put(h.getNodeId(), h);

        // 3. 预过滤（资源量 + 标签）→ 候选 ScoredNode
        List<SchedulingStrategy.ScoredNode> candidates = new ArrayList<>();
        for (ComputeNode node : onlineNodes) {
            FreeCapacity free = parser.freeCapacity(node, heldByNode.get(node.getId()));
            if (!satisfies(free, taskSpec)) continue;
            if (!labelMatches(node, taskSpec)) continue;
            candidates.add(new SchedulingStrategy.ScoredNode(node, free));
        }
        if (candidates.isEmpty()) {
            log.info("无节点满足资源需求 taskType={} taskId={} gpu={} cpu={} mem={}",
                    command.taskType(), command.taskId(), taskSpec.gpuCount(), taskSpec.cpu(), taskSpec.memoryBytes());
            return null;
        }

        // 4. 按策略选最优节点
        SchedulingStrategy.ScoredNode picked = strategy.schedule(taskSpec, candidates);
        if (picked == null) return null;

        // 5. 在 lock:node:{nodeId} 锁内 double-check → 写租约 → 下发指令
        String lockKey = ResourceConstants.REDIS_KEY_NODE_LOCK_PREFIX + picked.nodeId();
        String token = distributedLock.tryLock(lockKey, Duration.ofSeconds(leaseSeconds + 10));
        if (token == null) {
            log.warn("获取节点调度锁失败 nodeId={} taskType={} taskId={}", picked.nodeId(), command.taskType(), command.taskId());
            return null;
        }
        try {
            return doAllocateAndDispatch(picked.node(), command, taskSpec);
        } finally {
            distributedLock.unlock(lockKey, token);
        }
    }

    /**
     * 锁内执行：double-check 空闲量 → 写租约 → 下发 run_task。
     * 此方法自身不开新事务（由调用方按需包裹），写库操作在调用线程的事务上下文里完成；
     * 由于 MQ 消费侧 @Transactional 已在更外层，此处直接写库即可。
     */
    private Long doAllocateAndDispatch(ComputeNode node, TaskCommandMessage command, TaskResourceSpec taskSpec) {
        // double-check 空闲量（锁内重读聚合）
        NodeHeldAggregate held = allocationMapper.sumHeldByNode(node.getId());
        FreeCapacity free = parser.freeCapacity(node, held);
        if (!satisfies(free, taskSpec)) {
            log.info("double-check 空闲量不足 nodeId={} taskId={}", node.getId(), command.taskId());
            return null;
        }

        // 选定具体 GPU 设备索引（取前 N 个空闲槽位）
        List<Integer> gpuDevices = pickGpuDevices(node, free, taskSpec);

        // 调度标识 dispatchId：EVALUATION 用 subtaskId（每个子任务唯一，避免多模型评测时
        // workDir/容器名/租约/上下文缓存互相碰撞），TRAINING 用 taskId。Agent 据此唯一标识任务。
        Long dispatchId = (command.subtaskId() != null) ? command.subtaskId() : command.taskId();

        // 写租约（独立事务提交）：保证“Agent 收到 run_task 时，DB 中 held 租约已持久化”。
        // 若写在消费侧外层事务里，一旦提交失败会回滚租约，而 Agent 已开始执行 → 资源被占却计为空闲 → 超卖。
        // 租约按 dispatchId 唯一：评测每个子任务一条独立租约，forwardResult 释放时不会误伤兄弟子任务。
        ResourceAllocation alloc = new ResourceAllocation();
        alloc.setNodeId(node.getId());
        alloc.setTaskType(command.taskType());
        alloc.setTaskId(dispatchId);
        alloc.setTenantId(command.tenantId());
        alloc.setGpuCount(taskSpec.gpuCount());
        alloc.setCpu(taskSpec.cpu());
        alloc.setMemoryBytes(taskSpec.memoryBytes());
        alloc.setGpuDevices(toJsonArray(gpuDevices));
        alloc.setStatus(ResourceConstants.ALLOCATION_HELD);
        requiresNewTx.executeWithoutResult(status -> allocationMapper.insert(alloc));

        // 训练产物上传 URL（仅 TRAINING）：Agent 在容器退出、清理 workDir 前把 best.pt PUT 到此 URL。
        String outputUploadUrl = "TRAINING".equalsIgnoreCase(command.taskType())
                ? presignUpload(command.tenantId(), "training/" + dispatchId + "/output/best.pt")
                : null;

        // 下发 run_task（租约已提交，此时下发与租约状态一致）。taskId 传 dispatchId（Agent 唯一标识）。
        RunTaskCommand run = new RunTaskCommand(
                ResourceConstants.CMD_RUN_TASK,
                command.taskType(),
                dispatchId,
                command.tenantId(),
                command.image(),
                command.command(),
                command.args(),
                command.env(),
                gpuDevices,
                taskSpec.cpu(),
                taskSpec.memoryBytes(),
                command.outputDir(),
                UserContext.getTraceId(),
                // 「每个模型是一个容器」：把镜像 tar / 数据集 relPath 用租户前缀拼全 key 后预签名，
                // Agent 收到后 HTTP GET 下载 → docker load → 挂载数据集 → docker run。
                command.imageName(),
                presign(command.tenantId(), command.imageTarRelPath()),
                presign(command.tenantId(), command.datasetRelPath()),
                command.datasetFormat(),
                command.taskMode(),
                outputUploadUrl);
        // 缓存任务上下文（按 dispatchId 唯一）：ForwardService 据此恢复 parentTaskId/subtaskId/modelVersionId。
        saveTaskCtx(command, dispatchId);
        boolean ok = webSocketHandler.sendCommand(node.getAgentId(), run);
        if (!ok) {
            // Agent 不在线/链路断 → 回滚租约（独立事务，幂等：仅释放 status=held）
            requiresNewTx.executeWithoutResult(status -> allocationMapper.releaseById(alloc.getId()));
            log.warn("下发 run_task 失败（Agent 链路不可用），回滚租约 agentId={} taskId={}",
                    node.getAgentId(), command.taskId());
            return null;
        }
        log.info("调度成功 nodeId={} agentId={} taskType={} taskId={} gpuDevices={}",
                node.getId(), node.getAgentId(), command.taskType(), command.taskId(), gpuDevices);
        return node.getId();
    }

    /** 资源量是否满足 */
    private boolean satisfies(FreeCapacity free, TaskResourceSpec task) {
        if (task == null) return true;
        if (free.freeGpu() < task.gpuCount()) return false;
        if (free.freeCpu() < task.cpu()) return false;
        if (free.freeMemory() < task.memoryBytes()) return false;
        return true;
    }

    /** 标签匹配：task.gpuTypeLabel 非空时，要求节点 labels 含 gpu_type==该值 */
    private boolean labelMatches(ComputeNode node, TaskResourceSpec task) {
        if (task == null || task.gpuTypeLabel() == null || task.gpuTypeLabel().isBlank()) return true;
        return task.gpuTypeLabel().equalsIgnoreCase(parser.labels(node).get("gpu_type"));
    }

    /** 取前 gpuCount 个 GPU 索引（简单策略：0..N-1；实际可结合 held 用量选未占用的） */
    private List<Integer> pickGpuDevices(ComputeNode node, FreeCapacity free, TaskResourceSpec task) {
        int need = task == null ? 0 : task.gpuCount();
        if (need <= 0) return List.of();
        List<Integer> all = new ArrayList<>();
        int totalGpu = free.totalGpu();
        // 空闲槽位 = 总数 − 已分配；取前 need 个索引
        int alreadyHeld = free.allocatedGpu();
        for (int i = 0; i < need && (alreadyHeld + i) < totalGpu; i++) {
            all.add(alreadyHeld + i);
        }
        return all;
    }

    private String toJsonArray(List<Integer> devices) {
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            for (Integer d : devices) arr.add(d);
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            return "[]";
        }
    }
}
