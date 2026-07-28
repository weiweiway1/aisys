package com.aisys.evaluation.mq;

import com.aisys.evaluation.service.EvaluationTaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 模型版本发布事件消费者：监听 notification.event exchange 上的 MODEL_PUBLISHED 消息，
 * 自动为上传完成的模型版本创建并启动评测任务。
 *
 * <p>队列绑定在 {@link com.aisys.evaluation.config.EvaluationRabbitConfig} 中声明：
 * queue = q.evaluation.model.published, routing = MODEL.#</p>
 *
 * <p><b>注意</b>：不直接依赖 aisys-model 的 {@code ModelLifecycleMessage} 类（避免跨模块循环依赖），
 * 而是通过 JSON 字段提取所需信息。</p>
 */
@Component
public class ModelPublishedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ModelPublishedConsumer.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final EvaluationTaskService evaluationTaskService;

    public ModelPublishedConsumer(EvaluationTaskService evaluationTaskService) {
        this.evaluationTaskService = evaluationTaskService;
    }

    @RabbitListener(queues = "${eval.mq.model-published-queue:q.evaluation.model.published}")
    public void onModelPublished(Message message) {
        JsonNode root;
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            log.warn("[ModelPublishedConsumer] 消息解析失败，忽略: {}", e.getMessage());
            return;
        }

        // 仅处理 MODEL_PUBLISHED 事件
        String messageType = pathText(root, "messageType");
        if (!"MODEL_PUBLISHED".equals(messageType)) {
            log.debug("[ModelPublishedConsumer] 忽略非发布事件 type={}", messageType);
            return;
        }

        Long modelId = pathLong(root, "modelId");
        Long versionId = pathLong(root, "versionId");
        if (versionId == null) {
            log.warn("[ModelPublishedConsumer] 事件缺少 versionId，忽略 modelId={}", modelId);
            return;
        }

        String name = pathText(root, "name");
        String version = pathText(root, "version");

        log.info("[AutoEval] 收到模型发布事件 modelId={} versionId={} model={} ver={}",
                modelId, versionId, name, version);

        try {
            evaluationTaskService.autoCreateAndStart(versionId);
        } catch (Exception e) {
            // 吞掉异常避免脏消息无限重试阻塞队列（at-least-once 语义）
            // 自动评测失败不影响模型本身的上传成功状态
            log.error("[AutoEval] 自动创建评测失败 versionId={}: {}",
                    versionId, e.getMessage(), e);
        }
    }

    /** 安全提取路径上的文本值，节点缺失时返回 null */
    private static String pathText(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node != null && !node.isNull() && node.isTextual() ? node.asText() : null;
    }

    /** 安全提取路径上的长整型值 */
    private static Long pathLong(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node != null && !node.isNull() && node.canConvertToLong()) {
            return node.asLong();
        }
        return null;
    }
}
