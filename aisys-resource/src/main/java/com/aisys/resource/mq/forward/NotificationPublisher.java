package com.aisys.resource.mq.forward;

import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.mq.outbox.EventPublisher;
import com.aisys.resource.constant.ResourceConstants;
import com.aisys.resource.entity.ComputeNode;
import com.aisys.resource.mq.ResourceMessages.NodeOfflineMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 发布 notification.event（NODE_OFFLINE）。
 * <p>由节点离线定时任务触发，通知 Notification Service 据规则通知受影响租户/用户（DDD 5.9）。
 */
@Component
public class NotificationPublisher {

    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    private final EventPublisher eventPublisher;

    public NotificationPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void publishNodeOffline(ComputeNode node, String reason) {
        NodeOfflineMessage msg = new NodeOfflineMessage();
        msg.setNodeId(node.getId());
        msg.setNodeName(node.getNodeName());
        msg.setAgentId(node.getAgentId());
        msg.setReason(reason);
        eventPublisher.publish(msg,
                CommonConstants.EXCHANGE_NOTIFICATION_EVENT,
                ResourceConstants.ROUTING_KEY_NODE_OFFLINE,
                "NODE",
                String.valueOf(node.getId()));
        log.info("发布 NODE_OFFLINE 通知 nodeId={} agentId={} reason={}", node.getId(), node.getAgentId(), reason);
    }
}
