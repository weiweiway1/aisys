package com.aisys.resource.scheduler;

import com.aisys.resource.constant.ResourceConstants;
import com.aisys.resource.entity.ComputeNode;
import com.aisys.resource.mapper.ComputeNodeMapper;
import com.aisys.resource.mapper.ResourceAllocationMapper;
import com.aisys.resource.mq.forward.NotificationPublisher;
import com.aisys.resource.service.impl.AgentServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 节点离线检测定时任务（DDD 5.7）。
 * <p>fixedRate 30s：检测 last_heartbeat_at 超时（&gt;90s）的 online 节点 → status=offline，
 * 回收该节点全部 held 租约（status=released），发 notification.event（NODE_OFFLINE）。
 */
@Component
public class NodeOfflineScheduler {

    private static final Logger log = LoggerFactory.getLogger(NodeOfflineScheduler.class);

    private final ComputeNodeMapper computeNodeMapper;
    private final ResourceAllocationMapper allocationMapper;
    private final NotificationPublisher notificationPublisher;
    private final AgentServiceImpl agentServiceImpl;
    private final long heartbeatTimeoutSeconds;

    public NodeOfflineScheduler(ComputeNodeMapper computeNodeMapper,
                                ResourceAllocationMapper allocationMapper,
                                NotificationPublisher notificationPublisher,
                                com.aisys.resource.service.AgentService agentService,
                                @Value("${aisys.resource.heartbeat-timeout-seconds:90}") long heartbeatTimeoutSeconds) {
        this.computeNodeMapper = computeNodeMapper;
        this.allocationMapper = allocationMapper;
        this.notificationPublisher = notificationPublisher;
        this.agentServiceImpl = (AgentServiceImpl) agentService;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }

    @Scheduled(fixedRateString = "${aisys.resource.offline-check-rate-ms:30000}")
    @Transactional
    public void detectOfflineNodes() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(heartbeatTimeoutSeconds);
        List<ComputeNode> timedOut = computeNodeMapper.selectHeartbeatTimeout(cutoff);
        if (timedOut.isEmpty()) return;
        log.info("检测到 {} 个心跳超时节点（阈值 {}s）", timedOut.size(), heartbeatTimeoutSeconds);

        for (ComputeNode node : timedOut) {
            try {
                // 回收 held 租约
                int released = allocationMapper.releaseAllHeldByNode(node.getId());
                // 置离线
                computeNodeMapper.updateStatus(node.getId(), ResourceConstants.NODE_STATUS_OFFLINE);
                agentServiceImpl.setNodeStatus(node.getId(), ResourceConstants.NODE_STATUS_OFFLINE);
                // 发 NODE_OFFLINE 通知
                notificationPublisher.publishNodeOffline(node, "heartbeat timeout (>" + heartbeatTimeoutSeconds + "s)");
                log.info("节点离线 nodeId={} agentId={} releasedAllocs={}", node.getId(), node.getAgentId(), released);
            } catch (Exception e) {
                log.error("处理离线节点失败 nodeId={}: {}", node.getId(), e.getMessage(), e);
            }
        }
    }
}
