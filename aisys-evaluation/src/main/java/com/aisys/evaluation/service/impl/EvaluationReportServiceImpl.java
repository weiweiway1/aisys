package com.aisys.evaluation.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.evaluation.dto.EvaluationReportDtos.EvaluationReportResponse;
import com.aisys.evaluation.entity.EvaluationReport;
import com.aisys.evaluation.entity.EvaluationResult;
import com.aisys.evaluation.entity.EvaluationSubtask;
import com.aisys.evaluation.entity.EvaluationTask;
import com.aisys.evaluation.mapper.BenchmarkMapper;
import com.aisys.evaluation.mapper.EvaluationReportMapper;
import com.aisys.evaluation.mapper.EvaluationResultMapper;
import com.aisys.evaluation.mapper.EvaluationSubtaskMapper;
import com.aisys.evaluation.mapper.EvaluationTaskMapper;
import com.aisys.evaluation.service.EvaluationReportService;
import com.aisys.evaluation.constant.EvaluationErrorCode;
import com.aisys.evaluation.util.EvalJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 评测报告服务实现。
 * <p>流程：
 * <ol>
 *   <li>从 DB 读取评测结果（overallScores / categoryScores）</li>
 *   <li>组装 prompt = SYSTEM_TEMPLATE + 动态数据上下文</li>
 *   <li>调用 OpenAI 兼容 API（支持本地部署模型，Key 可选）</li>
 *   <li>存储 Markdown + HTML 到 evaluation_report 表</li>
 * </ol>
 */
@Service
public class EvaluationReportServiceImpl implements EvaluationReportService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationReportServiceImpl.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    // ==================== LLM 配置（application.yml 或环境变量）====================

    @Value("${aisys.llm.url:}")
    private String llmUrl;

    @Value("${aisys.llm.api-key:}")
    private String llmApiKey;

    @Value("${aisys.llm.model:gpt-4o-mini}")
    private String llmModel;

    /** 超时时间（秒），LLM 生成可能较慢。 */
    @Value("${aisys.llm.timeout-seconds:120}")
    private int llmTimeoutSeconds;

    // ==================== 依赖注入 ====================

    private final EvaluationReportMapper reportMapper;
    private final EvaluationTaskMapper taskMapper;
    private final EvaluationResultMapper resultMapper;
    private final EvaluationSubtaskMapper subtaskMapper;
    private final BenchmarkMapper benchmarkMapper;
    private final com.aisys.evaluation.client.ModelClient modelClient;
    private final com.aisys.evaluation.client.DatasetClient datasetClient;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public EvaluationReportServiceImpl(EvaluationReportMapper reportMapper,
                                       EvaluationTaskMapper taskMapper,
                                       EvaluationResultMapper resultMapper,
                                       EvaluationSubtaskMapper subtaskMapper,
                                       BenchmarkMapper benchmarkMapper,
                                       com.aisys.evaluation.client.ModelClient modelClient,
                                       com.aisys.evaluation.client.DatasetClient datasetClient,
                                       org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.reportMapper = reportMapper;
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
        this.subtaskMapper = subtaskMapper;
        this.benchmarkMapper = benchmarkMapper;
        this.modelClient = modelClient;
        this.datasetClient = datasetClient;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    // ==================== 查询接口 ====================

    @Override
    @Transactional(readOnly = true)
    public EvaluationReportResponse getReportByTaskId(Long taskId) {
        Long tenantId = currentTenant();
        EvaluationReport report = reportMapper.selectByTaskId(taskId, tenantId);
        if (report == null) {
            // 返回空记录（前端可据此显示"报告生成中"或"暂无报告"）
            return new EvaluationReportResponse(
                    null, taskId, "pending", null, null, null, null, null, null, null);
        }
        return toResponse(report);
    }

    @Override
    @Transactional(readOnly = true)
    public String getReportMd(Long taskId) {
        Long tenantId = currentTenant();
        EvaluationReport report = reportMapper.selectByTaskId(taskId, tenantId);
        return report != null ? report.getContentMd() : null;
    }

    @Override
    @Transactional(readOnly = true)
    public String getReportHtml(Long taskId) {
        Long tenantId = currentTenant();
        EvaluationReport report = reportMapper.selectByTaskId(taskId, tenantId);
        return report != null ? report.getContentHtml() : null;
    }

    @Override
    @Transactional
    public void deleteReport(Long taskId) {
        Long tenantId = currentTenant();
        reportMapper.deleteByTaskId(taskId, tenantId);
    }

    // ==================== 异步生成 ====================

    /**
     * 异步生成报告（由 @Async 在独立线程执行，不阻塞请求方）。
     * 幂等：同一 taskId 已有 completed 报告且 !force 时跳过。
     */
    @Override
    @Async("evaluationReportExecutor")
    public void generateReportAsync(Long taskId, boolean force) {
        // 同步调用场景：UserContext 可用
        Long tenantId = UserContext.getTenantId();
        if (tenantId == null) {
            log.error("[ReportGen] 同步调用但缺少租户上下文，放弃生成报告 taskId={}", taskId);
            return;
        }
        doGenerateReport(taskId, force, tenantId);
    }

    @Override
    @Async("evaluationReportExecutor")
    public void generateReportAsync(Long taskId, boolean force, Long tenantId) {
        log.info("[ReportGen] 异步入口 taskId={} force={} tenantId={}", taskId, force, tenantId);
        // 异步线程场景：使用传入的 tenantId
        if (tenantId == null) {
            log.warn("[ReportGen] tenantId 为 null，尝试从 UserContext 获取");
            // 降级：走两参数版本（尝试 UserContext）
            generateReportAsync(taskId, force);
            return;
        }
        // 设置 UserContext（ThreadLocal），让 RLS 拦截器（TenantContextInterceptor）能拿到租户 ID
        // RLS 拦截器会在每条 SQL 前执行 SET LOCAL app.tenant_id = <tenantId>
        try {
            UserContext.set(new UserContext.CurrentUser(null, tenantId, java.util.List.of(), "report-gen", null, false));
            log.info("[ReportGen] UserContext 已设置 tenantId={}，开始执行报告生成", tenantId);
            doGenerateReport(taskId, force, tenantId);
        } catch (Exception e) {
            log.error("[ReportGen] 报告生成异常 taskId={} tenantId={} : {}", taskId, tenantId, e.getMessage(), e);
            throw e;  // 让 @Async 的异常处理机制处理
        } finally {
            UserContext.clear();
            log.debug("[ReportGen] UserContext 已清理");
        }
    }

    /**
     * 报告生成的核心逻辑（统一入口）。
     * 使用编程式事务（TransactionTemplate），确保 SET LOCAL app.tenant_id 在多条 SQL 间持续生效。
     * 注意：不能用 @Transactional（private 方法 + 同类调用 → Spring AOP 代理失效）。
     */
    private void doGenerateReport(Long taskId, boolean force, Long tenantId) {

        log.info("[ReportGen] 开始生成报告 taskId={} force={} tenantId={}", taskId, force, tenantId);

        // 编程式事务：确保所有 DB 操作在同一事务内（RLS 的 SET LOCAL 跨语句保持）
        transactionTemplate.execute(status -> {
            try {
                // 1. 检查已有报告
                EvaluationReport existing = reportMapper.selectByTaskId(taskId, tenantId);
                if (existing != null && "completed".equals(existing.getStatus()) && !force) {
                    log.info("[ReportGen] 已有 completed 报告，跳过 taskId={}", taskId);
                    return null;
                }

                // 2. 幂等处理：删除旧的 non-completed 记录（处理 pending/generating/failed 残留数据），重新创建
                if (existing != null) {
                    log.info("[ReportGen] 清理旧记录 taskId={} oldStatus={} id={}", taskId, existing.getStatus(), existing.getId());
                    reportMapper.deleteByTaskId(taskId, tenantId);
                }

                // 3. 创建新记录
                EvaluationReport report = new EvaluationReport();
                report.setTenantId(tenantId);
                report.setEvaluationTaskId(taskId);
                report.setStatus("pending");
                reportMapper.insert(report);

                // 4. 标记为 generating
                reportMapper.updateStatusAndContent(
                        report.getId(), tenantId, "generating",
                        null, null, null, null, null);

                // 5. 构建提示词
                ReportContext ctx = buildContext(taskId, tenantId);
                String userPrompt = buildUserPrompt(ctx);
                // 按任务类型选 system prompt 骨架，替换 {taskName}；
                // benchmark.prompt_template 非空时作为 per-benchmark 风格补充 append 到末尾。
                String systemPrompt = chooseSystemPrompt(ctx.modelTaskType())
                        .replace("{taskName}", nonNull(ctx.taskName()));
                if (ctx.benchmarkPromptTemplate() != null && !ctx.benchmarkPromptTemplate().isBlank()) {
                    systemPrompt += "\n\n## 测评集自定义补充要求\n" + ctx.benchmarkPromptTemplate();
                }

                // 6. 调用 LLM
                String mdContent = callLlm(systemPrompt, userPrompt);
                String htmlContent = markdownToHtml(mdContent);

                // 7. 存储结果
                reportMapper.updateStatusAndContent(
                        report.getId(), tenantId, "completed",
                        mdContent, htmlContent, llmModel,
                        truncate(userPrompt, 500), null);

                log.info("[ReportGen] 报告生成完成 taskId={} id={}", taskId, report.getId());
                return null;

            } catch (Exception e) {
                log.error("[ReportGen] 报告生成失败 taskId={} : {}", taskId, e.getMessage(), e);
                // 在同一事务内更新失败状态
                try {
                    EvaluationReport r = reportMapper.selectByTaskId(taskId, tenantId);
                    if (r != null) {
                        reportMapper.updateStatusAndContent(
                                r.getId(), tenantId, "failed",
                                null, null, null, null,
                                truncate(e.getMessage(), 1000));
                    }
                } catch (Exception ignored) {}
                status.setRollbackOnly();  // 回滚整个事务
                return null;
            }
        });
    }

    // ==================== Prompt 构建 ====================

    /**
     * 系统级提示词模板（通用骨架）。
     * <p>不硬编码具体指标名——指标清单来自容器实际输出的 JSONB key（数据驱动）。
     * {metricHint} 由 {@link #chooseSystemPrompt(String)} 按任务类型替换为该类任务常见的指标举例
     * （仅作 LLM 解读参考，不限定）；{taskName} 在运行时替换为实际任务名。
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一位资深的 AI 模型评测分析师。基于客观数据进行专业分析，输出中文。

            ## 风格要求（必须遵守）
            - 严肃准确，禁营销化措辞（"惊艳/强大/完美/卓越"等），只用中性技术语。
            - 每个结论必须引用具体数值（指标名 + 数值）。
            - 数据不足或样本量过小时，显式标注"样本量不足，结论待验证"，不得臆测。
            - 不臆测未提供的原因；改进建议必须基于已给出的数据。
            - 若模型或数据集描述缺失，依据任务类型与模型架构推断分析，报告中不出现"暂无""未知"字样。

            ## 报告结构（严格按此输出）

            # {taskName} — 模型评测分析报告

            ## 一、评测概况
            任务名 / 模型（名称+版本+任务类型）/ 测评集 / 数据集（名称+任务类型）/ 样本数 / 评测时间 / 一句话总体评价。

            ## 二、核心指标解读
            对"本次评测产出指标"清单中的每个指标，逐一给出数值并判断其相对高低或是否达标。
            {metricHint}

            ## 三、类别级 / 分组级详细分析
            若 category_scores 提供了分组数据（各类别/各分组），用表格列出关键组别的指标；无分组数据则跳过本节。

            ## 四、弱项与风险
            按链式结构组织：问题描述 — 证据（引用具体指标数值与所在分组）— 量化影响 — 可能原因 — 改进建议。
            按"漏检/误检/类别混淆/定位偏差/置信度异常/输出协议异常"分类（适用项才写，不适用则不写）。
            对"显著偏低或相比同类骤降"的指标标注 **高风险** 并限定使用场景；子任务错误信息（若有）作为输出协议异常/失败模式的证据。

            ## 五、改进建议（三档，每条绑定指标与量化预期收益）
            ### 立即可做（不改模型：阈值/后处理/输入尺寸，量化预期收益）
            ### 需返工（数据补强/数据增强/难负样本，量化预期收益与工作量）
            ### 需重训练（结构调整/模型选型/超参，量化预期收益与工作量）

            ## 六、结论与部署建议
            是否适合部署 + 红线判定（高风险项是否阻断）+ 最终推荐意见（推荐 / 受限推荐 / 不推荐）。

            ---
            *本报告由 AISys 评测平台自动生成*
            """;

    /** 按任务类型选择指标解读举例（仅作 LLM 参考，不限定指标范围）。 */
    private static String chooseSystemPrompt(String modelTaskType) {
        String hint;
        if (modelTaskType == null) {
            hint = "不举具体指标例子，按给出的指标清单逐项解读。";
        } else switch (modelTaskType.toLowerCase()) {
            case "object_detection":
                hint = "检测类任务常见指标：mAP@0.5、mAP@0.5:0.95、AP_small/medium/large、Precision/Recall、分尺度 AP。可分析小目标与密集目标能力。";
                break;
            case "image_classification":
                hint = "分类任务常见指标：Top-1/Top-5 Accuracy、Macro-F1、混淆矩阵（最易混淆的类别对）。可分析类间混淆与置信度校准。";
                break;
            case "time_series":
                hint = "时序预测任务常见指标：MAE/RMSE/MAPE、趋势准确率、异常召回率、误差随预测步长累积形态。不出现 mAP/混淆矩阵。";
                break;
            default:
                hint = "不举具体指标例子，按给出的指标清单逐项解读。";
        }
        return SYSTEM_PROMPT_TEMPLATE.replace("{metricHint}", hint);
    }

    /**
     * 用户级提示词模板（通用结构，不写死指标名）。占位符在 {@link #buildUserPrompt} 替换。
     */
    private static final String USER_PROMPT_TEMPLATE = """
            ## 评测基本信息
            - **任务名称**: {taskName}
            - **模型**: {modelName} {modelVersion}（任务类型: {modelTaskType}）
            - **测评集**: {benchmarkName}
            - **数据集**: {datasetName}（任务类型: {datasetTaskType}，样本数: {sampleCount}）
            - **评测时间**: {evalTime}

            ## 本次评测产出指标（来自容器输出，按此清单解读）
            {metricKeys}

            ## 模型描述
            {modelDescription}

            ## 数据集描述
            {datasetDescription}

            ## 测评集说明
            {benchmarkDescription}

            ## 超参 / 任务配置
            {taskConfig}

            ## 测评集指标定义（可选，来自 benchmark.metrics_config；可能为空）
            {metricsConfigJson}

            ## 评测结果（每个被测模型一段；多模型时全部列出便于横向对比）
            {multiResultsJson}

            ## 子任务错误信息（失败分析依据；无错误则空）
            {subtaskErrors}

            请按系统提示的章节结构撰写报告。
            """;

    /** 报告构建所需的数据上下文（通用化：含任务类型、数据反推的指标清单、多模型结果、子任务错误等）。 */
    private record ReportContext(
            String taskName,
            String modelName,
            String modelVersion,
            String modelTaskType,
            String modelDescription,
            String benchmarkName,
            String benchmarkDescription,
            String benchmarkPromptTemplate,
            String metricsConfigJson,
            String datasetName,
            String datasetDescription,
            String datasetTaskType,
            String evalTime,
            Integer sampleCount,
            String metricKeys,
            String multiResultsJson,
            String subtaskErrors,
            String taskConfig
    ) {}

    /**
     * 从数据库收集报告所需的全部上下文数据（通用版，不依赖具体模型类型）。
     * <p>关键设计：
     * <ul>
     *   <li>模型元数据（modelName/modelDescription/taskType）经 Task#6 改造后的 ModelClient 直接返回，无需第二次查询。</li>
     *   <li>数据集元数据（datasetName/datasetDescription/taskType）经 Task#7 改造后的 DatasetClient 直接返回。</li>
     *   <li>指标清单（metricKeys）从所有 result 的 overallScores + categoryScores JSONB 顶层 key 反推（数据驱动，不硬编码 mAP/Top-1）。</li>
     *   <li>多模型评测：循环每个 result，全部塞进 multiResultsJson 供 LLM 横向对比。</li>
     *   <li>子任务 errorMessage + benchmark.metrics_config + benchmark.prompt_template 全部读出喂给 LLM。</li>
     * </ul>
     */
    private ReportContext buildContext(Long taskId, Long tenantId) {
        EvaluationTask task = taskMapper.selectByIdAndTenant(taskId, tenantId);
        if (task == null) throw new BusinessException(EvaluationErrorCode.EVALUATION_TASK_NOT_FOUND);

        List<EvaluationResult> results = resultMapper.selectByTaskId(taskId, tenantId);
        List<EvaluationSubtask> subtasks = subtaskMapper.selectByParentTaskId(taskId, tenantId);

        // —— benchmark（含 metricsConfig / promptTemplate 两个现成钩子）——
        String benchmarkName = "未知测评集";
        String benchmarkDesc = "";
        String metricsConfigJson = "";
        String benchmarkPromptTemplate = "";
        if (task.getBenchmarkId() != null) {
            var benchmark = benchmarkMapper.selectByIdAndTenant(task.getBenchmarkId(), tenantId);
            if (benchmark != null) {
                benchmarkName = benchmark.getName() != null ? benchmark.getName() : benchmarkName;
                benchmarkDesc = benchmark.getDescription() != null ? benchmark.getDescription() : "";
                metricsConfigJson = benchmark.getMetricsConfig() != null ? benchmark.getMetricsConfig() : "";
                benchmarkPromptTemplate = benchmark.getPromptTemplate() != null ? benchmark.getPromptTemplate() : "";
            }
        }

        // —— 每个被测模型一段：{modelName, modelVersion, overallScores, categoryScores} ——
        String modelName = "";
        String modelVersion = "";
        String modelDesc = "";
        String modelTaskType = null;   // 取首个非空任务类型
        List<Map<String, Object>> multiResults = new java.util.ArrayList<>();
        boolean first = true;
        for (EvaluationResult r : results) {
            Map<String, Object> overall = EvalJson.toMap(r.getOverallScores());
            Map<String, Object> category = EvalJson.toMap(r.getCategoryScores());
            String mn = "", mv = "", md = "";
            String mtt = null;
            try {
                var resp = modelClient.getVersionById(r.getModelVersionId());
                if (resp != null && resp.isSuccess() && resp.data() != null) {
                    Map<String, Object> v = resp.data();
                    mn = strOr(v.get("modelName"), "");
                    mv = strOr(v.get("version"), "");
                    md = strOr(v.get("modelDescription"), "");
                    mtt = strOr(v.get("taskType"), null);
                }
            } catch (Exception e) {
                log.warn("[Report] 获取模型信息失败 versionId={} : {}", r.getModelVersionId(), e.getMessage());
            }
            if (modelTaskType == null) modelTaskType = mtt;
            if (first) { modelName = mn; modelVersion = mv; modelDesc = md; first = false; }
            Map<String, Object> seg = new java.util.LinkedHashMap<>();
            seg.put("modelName", mn);
            seg.put("modelVersion", mv);
            seg.put("overallScores", overall);
            seg.put("categoryScores", category);
            multiResults.add(seg);
        }

        // —— 数据集元数据（JOIN 改造后 DatasetClient 一次返回 name/description/taskType/sampleCount）——
        String datasetName = "";
        String datasetDesc = "";
        String datasetTaskType = null;
        long datasetSampleCount = 0;   // dataset.sample_count 字段（JOIN 返回，可能未回写为 null）
        if (task.getBenchmarkId() != null) {
            var benchmark = benchmarkMapper.selectByIdAndTenant(task.getBenchmarkId(), tenantId);
            if (benchmark != null && benchmark.getDatasetVersionIds() != null && !benchmark.getDatasetVersionIds().isBlank()) {
                try {
                    java.util.List<Long> dsVersionIds = objectMapper.readValue(
                            benchmark.getDatasetVersionIds(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Long>>() {});
                    if (!dsVersionIds.isEmpty()) {
                        var dsResp = datasetClient.getVersionById(dsVersionIds.get(0));
                        if (dsResp != null && dsResp.isSuccess() && dsResp.data() != null) {
                            Map<String, Object> d = dsResp.data();
                            datasetName = strOr(d.get("datasetName"), "");
                            datasetDesc = strOr(d.get("datasetDescription"), "");
                            datasetTaskType = strOr(d.get("taskType"), null);
                            Object sc = d.get("sampleCount");
                            if (sc instanceof Number) datasetSampleCount = ((Number) sc).longValue();
                        }
                    }
                } catch (Exception e) {
                    log.debug("[Report] 获取数据集信息失败: {}", e.getMessage());
                }
            }
        }

        // 样本数：① dataset.sample_count 字段优先；② 为空则从描述文本正则提取（部分数据集把数量写在 description 里）；
        // ③ 仍无则用评测结果 sample_count（实际评测的样本量，可能为 0）
        Integer sampleCount = datasetSampleCount > 0 ? (int) datasetSampleCount : null;
        if (sampleCount == null) {
            Long extracted = extractSampleCount(datasetDesc);
            if (extracted != null) sampleCount = extracted.intValue();
        }
        if (sampleCount == null) {
            sampleCount = results.stream()
                    .mapToInt(r -> r.getSampleCount() != null ? r.getSampleCount() : 0).sum();
        }

        // 指标清单：从所有 result 的 overallScores + categoryScores JSONB 顶层 key 反推（数据驱动）
        String metricKeys = collectMetricKeys(results);

        // 多模型结果 JSON
        String multiResultsJson;
        try {
            multiResultsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(multiResults);
        } catch (Exception e) {
            multiResultsJson = multiResults.toString();
        }

        String subtaskErrors = buildSubtaskErrors(subtasks);
        String taskConfig = task.getConfig() != null ? task.getConfig() : "";

        return new ReportContext(
                task.getName(),
                modelName, modelVersion, modelTaskType, modelDesc,
                benchmarkName, benchmarkDesc, benchmarkPromptTemplate, metricsConfigJson,
                datasetName, datasetDesc, datasetTaskType,
                task.getCompletedAt() != null ? DT_FMT.format(task.getCompletedAt()) : DT_FMT.format(Instant.now()),
                sampleCount,
                metricKeys,
                multiResultsJson,
                subtaskErrors,
                taskConfig
        );
    }

    /** 收集所有 result 的 overallScores + categoryScores 顶层 JSON key（去重），作为指标清单喂给 LLM。 */
    private String collectMetricKeys(List<EvaluationResult> results) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (EvaluationResult r : results) {
            Map<String, Object> overall = EvalJson.toMap(r.getOverallScores());
            if (overall != null) keys.addAll(overall.keySet());
            Map<String, Object> category = EvalJson.toMap(r.getCategoryScores());
            if (category != null) keys.addAll(category.keySet());
        }
        return keys.isEmpty() ? "（无指标数据）" : String.join(", ", keys);
    }

    /**
     * 从数据集描述文本提取样本数（当 dataset.sample_count 未回写、数量写在 description 里的兜底）。
     * 匹配 "5000 张" / "5000 个样本" / "共 5000 条" 等，取最大值（描述里多个数字时，样本总数通常最大）。
     */
    private static Long extractSampleCount(String description) {
        if (description == null || description.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d+)\\s*(张图像|个样本|条样本|条数据|张|个|条|样本|帧)"
        ).matcher(description);
        long best = 0;
        while (m.find()) {
            try {
                long n = Long.parseLong(m.group(1));
                if (n > best) best = n;
            } catch (NumberFormatException ignored) {}
        }
        return best > 0 ? best : null;
    }

    /** 汇总子任务错误信息（失败分析依据）；无错误返回"（无错误）"。 */
    private String buildSubtaskErrors(List<EvaluationSubtask> subtasks) {
        if (subtasks == null || subtasks.isEmpty()) return "（无错误）";
        StringBuilder sb = new StringBuilder();
        for (EvaluationSubtask st : subtasks) {
            if (st.getErrorMessage() != null && !st.getErrorMessage().isBlank()) {
                sb.append("- modelVersionId=").append(st.getModelVersionId())
                  .append(" status=").append(st.getStatus())
                  .append(" error: ").append(st.getErrorMessage()).append("\n");
            }
        }
        return sb.length() == 0 ? "（无错误）" : sb.toString();
    }

    /** Object → 非空 String，空则返回 fallback。 */
    private static String strOr(Object v, String fallback) {
        if (v == null) return fallback;
        String s = v.toString().trim();
        return s.isBlank() ? fallback : s;
    }

    /**
     * 将系统模板 + 数据合成为最终用户提示词（通用占位符）。
     */
    private String buildUserPrompt(ReportContext ctx) {
        return USER_PROMPT_TEMPLATE
                .replace("{taskName}", nonNull(ctx.taskName()))
                .replace("{modelName}", nonNull(ctx.modelName()))
                .replace("{modelVersion}", nonNull(ctx.modelVersion()))
                .replace("{modelTaskType}", nonNull(ctx.modelTaskType()))
                .replace("{modelDescription}", nonNull(ctx.modelDescription()))
                .replace("{benchmarkName}", nonNull(ctx.benchmarkName()))
                .replace("{benchmarkDescription}", nonNull(ctx.benchmarkDescription()))
                .replace("{metricsConfigJson}", nonNull(ctx.metricsConfigJson()))
                .replace("{datasetName}", nonNull(ctx.datasetName()))
                .replace("{datasetDescription}", nonNull(ctx.datasetDescription()))
                .replace("{datasetTaskType}", nonNull(ctx.datasetTaskType()))
                .replace("{evalTime}", nonNull(ctx.evalTime()))
                .replace("{sampleCount}", String.valueOf(ctx.sampleCount()))
                .replace("{metricKeys}", nonNull(ctx.metricKeys()))
                .replace("{multiResultsJson}", nonNull(ctx.multiResultsJson()))
                .replace("{subtaskErrors}", nonNull(ctx.subtaskErrors()))
                .replace("{taskConfig}", nonNull(ctx.taskConfig()));
    }

    /**
     * 已删除 CV-only 的 buildClassMetricsTable（从 confusion_matrix 反推 P/R/F1）。
     * 通用化后改为把 categoryScores JSON 原样透传给 LLM（见 multiResultsJson），让 LLM 按 metricKeys 自行解读，
     * 避免硬编码计算机视觉路径、对时序等非 CV 任务无意义。
     */

    // ==================== LLM 调用 ====================

    /**
     * 调用 LLM API（支持两种模式）：
     * <ol>
     *   <li>OpenAI 兼容格式：URL 以 /v1/chat/completions 结尾或不含 /api/generate</li>
     *   <li>Ollama 原生格式：URL 包含 /api/generate</li>
     * </ol>
     */
    private String callLlm(String systemPrompt, String userPrompt) {
        if (llmUrl == null || llmUrl.isBlank()) {
            throw new IllegalStateException("LLM 服务未配置，请在 application.yml 中设置 aisys.llm.url");
        }

        boolean isOllama = llmUrl.contains("/api/generate");

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            String fullUrl;
            String requestBody;

            if (isOllama) {
                // ========== Ollama 原生 API ==========
                // URL: http://host:port/api/generate
                fullUrl = llmUrl;
                // 清理模型名首尾可能存在的多余引号（环境变量/配置误带）
                String cleanModel = (llmModel != null) ? llmModel.replaceAll("^\"+|\"+$", "") : "";
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", cleanModel);
                body.put("prompt", userPrompt);
                body.put("system", systemPrompt);
                body.put("stream", false);  // 非流式，一次性返回完整结果
                // Ollama options
                ObjectNode options = body.putObject("options");
                options.put("temperature", 0.3);
                options.put("num_predict", 8192);
                requestBody = objectMapper.writeValueAsString(body);
            } else {
                // ========== OpenAI Chat Completions 兼容格式 ==========
                // URL: http://host:port/v1/chat/completions (自动补全)
                fullUrl = llmUrl.endsWith("/v1/chat/completions") ? llmUrl : llmUrl + "/v1/chat/completions";
                String cleanModel = (llmModel != null) ? llmModel.replaceAll("^\"+|\"+$", "") : "";
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", cleanModel);
                body.put("temperature", 0.3);
                body.put("max_tokens", 8192);

                ObjectNode messages = body.putArray("messages").addObject();
                messages.put("role", "system");
                messages.put("content", systemPrompt);

                ObjectNode userMsg = body.withArray("messages").addObject();
                userMsg.put("role", "user");
                userMsg.put("content", userPrompt);

                requestBody = objectMapper.writeValueAsString(body);
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(llmTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));

            if (llmApiKey != null && !llmApiKey.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + llmApiKey);
            }

            log.info("[ReportGen] 调用 LLM url={} model={} mode={} timeout={}s", fullUrl, llmModel,
                    isOllama ? "ollama" : "openai", llmTimeoutSeconds);
            log.info("[ReportGen] 发送请求体: {}", truncate(requestBody, 500));
            HttpResponse<String> response = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            log.info("[ReportGen] LLM 响应 status={}", response.statusCode());
            if (response.statusCode() != 200) {
                throw new RuntimeException("LLM API 返回错误 HTTP " + response.statusCode()
                        + ": " + truncate(response.body(), 500));
            }

            // 解析响应
            String content;
            if (isOllama) {
                // Ollama /api/generate 响应: {"response": "...", "done": true}
                JsonNode root = objectMapper.readTree(response.body());
                content = root.path("response").asText("");
                if (content.isBlank()) {
                    throw new RuntimeException("Ollama 返回内容为空，原始响应: " + truncate(response.body(), 300));
                }
            } else {
                // OpenAI 响应: {"choices": [{"message": {"content": "..."}}]}
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode choices = root.path("choices");
                if (choices == null || !choices.isArray() || choices.isEmpty()) {
                    throw new RuntimeException("LLM 返回数据格式异常：choices 为空或不存在");
                }
                content = choices.get(0).path("message").path("content").asText("");
                if (content.isBlank()) {
                    throw new RuntimeException("LLM 返回内容为空");
                }
            }
            return content;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 LLM 失败: " + e.getMessage(), e);
        }
    }

    // ==================== Markdown → HTML ====================

    /**
     * Markdown 转 HTML（用于 Word 导出）。
     * 核心原则：先做结构转换，只对用户内容做局部转义，最后输出。
     * 生产环境建议集成 flexmark-java 库获得更完整的 Markdown 支持。
     */
    private String markdownToHtml(String md) {
        if (md == null || md.isBlank()) return "";

        // 1. 代码块：用 Matcher 提取并转义内部内容
        java.util.regex.Pattern codeBlockPattern = java.util.regex.Pattern.compile("```(\\w*)\\n([\\s\\S]*?)```");
        java.util.regex.Matcher codeMatcher = codeBlockPattern.matcher(md);
        StringBuffer codeSb = new StringBuffer();
        while (codeMatcher.find()) {
            String lang = codeMatcher.group(1);
            String code = codeMatcher.group(2).trim();
            codeMatcher.appendReplacement(codeSb, "<pre><code class=\"" + escapeHtml(lang) + "\">" + escapeHtml(code) + "</code></pre>");
        }
        codeMatcher.appendTail(codeSb);
        String html = codeSb.toString();

        // 2. 行内代码（用 Matcher 处理，确保 $1 正确引用捕获组）
        java.util.regex.Pattern inlineCodePattern = java.util.regex.Pattern.compile("`([^`]+)`");
        java.util.regex.Matcher inlineMatcher = inlineCodePattern.matcher(html);
        StringBuffer inlineSb = new StringBuffer();
        while (inlineMatcher.find()) {
            inlineMatcher.appendReplacement(inlineSb,
                    java.util.regex.Matcher.quoteReplacement("<code>" + escapeHtml(inlineMatcher.group(1)) + "</code>"));
        }
        inlineMatcher.appendTail(inlineSb);
        html = inlineSb.toString();

        // 3. 标题（必须在粗体之前处理，避免 ### 被误匹配）
        html = html.replaceAll("^#### (.+)$", "<h4>$1</h4>");
        html = html.replaceAll("^### (.+)$", "<h3>$1</h3>");
        html = html.replaceAll("^## (.+)$", "<h2>$1</h2>");
        html = html.replaceAll("^# (.+)$", "<h1>$1</h1>");

        // 4. 粗体 / 斜体
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        html = html.replaceAll("\\*(.+?)\\*", "<em>$1</em>");

        // 5. 分隔线
        html = html.replaceAll("^---$", "<hr/>");

        // 6. 表格：用 Matcher 处理每行
        java.util.regex.Pattern tableRowPattern = java.util.regex.Pattern.compile("^(\\|.+)\\|$", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher tableMatcher = tableRowPattern.matcher(html);
        StringBuffer tableSb = new StringBuffer();
        while (tableMatcher.find()) {
            String content = tableMatcher.group(1);
            String[] cells = content.split("\\|");
            StringBuilder rowSb = new StringBuilder();
            boolean isSeparator = true;
            for (String cell : cells) {
                String c = cell.trim();
                isSeparator = isSeparator && c.matches("^[-:]+$");
                if (!isSeparator) {
                    rowSb.append("<td>").append(escapeHtml(c)).append("</td>");
                }
            }
            String replacement = isSeparator ? "" : "<tr>" + rowSb + "</tr>";
            tableMatcher.appendReplacement(tableSb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        tableMatcher.appendTail(tableSb);
        html = tableSb.toString();

        // 7. 引用块
        html = html.replaceAll("^> (.+)$", "<blockquote>$1</blockquote>");

        // 8. 列表
        html = html.replaceAll("^- (.+)$", "<li>$1</li>");
        html = html.replaceAll("^\\d+\\. (.+)$", "<li>$1</li>");

        // 9. 段落换行（最后处理）
        html = html.replaceAll("\n\n+", "</p><p>");
        html = html.replaceAll("\n", "<br/>\n");

        return "<html><head><meta charset='utf-8'></head><body><p>" + html + "</p></body></html>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ==================== helpers ====================

    private EvaluationReportResponse toResponse(EvaluationReport r) {
        return new EvaluationReportResponse(
                r.getId(), r.getEvaluationTaskId(), r.getStatus(),
                r.getContentMd(), r.getContentHtml(),
                r.getLlmModel(), r.getPromptSummary(),
                r.getErrorMessage(), r.getGeneratedAt(), r.getCreatedAt());
    }

    private Long currentTenant() {
        Long t = UserContext.getTenantId();
        if (t == null) throw new BusinessException(EvaluationErrorCode.EVALUATION_INTERNAL_ERROR);
        return t;
    }

    private static String nonNull(String s) { return s != null ? s : ""; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
