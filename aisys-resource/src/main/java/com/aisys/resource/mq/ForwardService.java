package com.aisys.resource.mq;

import com.aisys.resource.dto.WebSocketMessage;

/**
 * 转发 Agent WebSocket 上报到对应 MQ exchange（DDD 5.7.5 消费并转发）。
 */
public interface ForwardService {

    /** status → task.status */
    void forwardStatus(String agentId, WebSocketMessage msg);

    /** log → task.log */
    void forwardLog(String agentId, WebSocketMessage msg);

    /** metrics → task.metrics */
    void forwardMetrics(String agentId, WebSocketMessage msg);

    /** result → task.status（终态）+ 释放租约 */
    void forwardResult(String agentId, WebSocketMessage msg);
}
