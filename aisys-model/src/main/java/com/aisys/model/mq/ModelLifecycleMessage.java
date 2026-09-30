package com.aisys.model.mq;

import com.aisys.common.mq.message.BaseMessage;

import java.util.Map;

/**
 * 模型生命周期事件消息（写 outbox，由 OutboxPublisher 投递到 notification.event）。
 * 消费方（notification 服务）按 {@link #messageType()} 分发。
 * <p>messageType 在构造期固化（final 字段，构造器赋值），覆盖抽象方法 {@link BaseMessage#messageType()}。
 */
public class ModelLifecycleMessage extends BaseMessage {

    private final String messageType;

    private Long modelId;
    private Long versionId;
    private String name;
    private String version;
    private String status;
    private Map<String, Object> extra;

    public ModelLifecycleMessage(String messageType) {
        this.messageType = messageType;
    }

    @Override
    public String messageType() {
        return messageType;
    }

    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }
}
