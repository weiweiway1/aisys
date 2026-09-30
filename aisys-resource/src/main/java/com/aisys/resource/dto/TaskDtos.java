package com.aisys.resource.dto;

import java.util.List;
import java.util.Map;

/**
 * 调度相关 DTO：任务命令（消费 task.command）、run_task 指令（下发 Agent）。
 */
public final class TaskDtos {

    private TaskDtos() {}

    /** 任务资源需求规格（消费 task.command 后解析） */
    public record TaskResourceSpec(
            int gpuCount,
            int cpu,
            long memoryBytes,
            String gpuTypeLabel        // 标签匹配，如 "a100"；可为空
    ) {}

    /** 消费 task.command 的消息体（TRAINING/EVALUATION 下发）。字段保持宽松，未用到的忽略。 */
    public record TaskCommandMessage(
            String taskType,           // TRAINING / EVALUATION
            Long taskId,
            Long tenantId,
            TaskResourceSpec resourceSpec,
            String image,              // 容器镜像（兼容字段）
            String command,            // 启动命令（兼容字段）
            List<String> args,         // 命令参数
            Map<String, String> env,   // 环境变量
            String outputDir,          // 产出路径（checkpoint/log 落盘目录）
            Map<String, Object> extra, // 额外参数（如 model_version_ids）
            // ——「每个模型是一个容器」扩展：镜像 tar + 数据集均以 relPath 下发，Resource 用 tenantId 拼全 key 后预签名 ——
            String imageName,          // docker load 后要 run 的镜像名，如 aisys/yolo-demo:cpu
            String imageTarRelPath,    // 模型版本存储路径（relPath）：models/{modelId}/{ver}/model.bin
            String datasetRelPath,     // 数据集版本存储路径（relPath）：datasets/{id}/{ver}.ext
            String datasetFormat,      // yolo / coco / csv ...
            String taskMode,           // train / eval
            // —— 评测子任务关联（EVALUATION）：resource 不把子任务标识下发 Agent，
            //    但在调度时缓存 taskId↔(subtaskId,modelVersionId)，状态回写时恢复，否则评测消费侧 subtaskId 恒 null。
            Long subtaskId,
            Long modelVersionId
    ) {}

    /** WebSocket 下发给 Agent 的 run_task 指令负载 */
    public record RunTaskCommand(
            String type,               // run_task
            String taskType,
            Long taskId,
            Long tenantId,
            String image,
            String command,
            List<String> args,
            Map<String, String> env,
            List<Integer> gpuDevices,  // 分配的 GPU 索引
            int cpu,
            long memoryBytes,
            String outputDir,
            String traceId,
            // —— Agent 拉起真实模型容器所需 ——
            String imageName,          // docker load 后 run 的镜像名
            String imageTarUrl,        // 镜像 tar 的预签名下载 URL
            String datasetUrl,         // 数据集对象的预签名下载 URL
            String datasetFormat,      // yolo / coco / csv ...
            String taskMode,           // train / eval
            // —— 训练产物回传：Resource 预签名一个 PUT 上传 URL，Agent 在容器退出、清理工作目录前
            //    把 /output/best.pt 上传到此 URL，否则 best.pt 随 workDir 一起被删除，训练成果无法落盘。
            String outputUploadUrl
    ) {}

    /** WebSocket stop_task 指令负载 */
    public record StopTaskCommand(
            String type,               // stop_task
            Long taskId
    ) {}
}
