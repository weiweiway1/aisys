package com.aisys.evaluation.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aisys.common.core.context.UserContext;
import com.aisys.common.core.exception.BusinessException;
import com.aisys.evaluation.dto.EvaluationReportDtos.EvaluationReportResponse;
import com.aisys.evaluation.entity.EvaluationReport;
import com.aisys.evaluation.entity.EvaluationResult;
import com.aisys.evaluation.entity.EvaluationTask;
import com.aisys.evaluation.mapper.BenchmarkMapper;
import com.aisys.evaluation.mapper.EvaluationReportMapper;
import com.aisys.evaluation.mapper.EvaluationResultMapper;
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
    private final BenchmarkMapper benchmarkMapper;
    private final com.aisys.evaluation.client.ModelClient modelClient;
    private final com.aisys.evaluation.client.DatasetClient datasetClient;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public EvaluationReportServiceImpl(EvaluationReportMapper reportMapper,
                                       EvaluationTaskMapper taskMapper,
                                       EvaluationResultMapper resultMapper,
                                       BenchmarkMapper benchmarkMapper,
                                       com.aisys.evaluation.client.ModelClient modelClient,
                                       com.aisys.evaluation.client.DatasetClient datasetClient,
                                       org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.reportMapper = reportMapper;
        this.taskMapper = taskMapper;
        this.resultMapper = resultMapper;
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
                String systemPrompt = SYSTEM_PROMPT_TEMPLATE;

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
     * 系统级提示词模板（固定不变，与项目特点结合）。
     * 面向 AI 模型评测场景：分类/检测任务的指标分析。
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = """
            你是一位资深的AI模型评测分析师，专精于计算机视觉模型（目标检测、图像分类）的性能评估。
            
            ## 你的角色
            - 基于客观数据进行专业分析，不夸大、不贬低
            - 用数据说话，每个结论都要引用具体数值
            - 输出中文
            
            ## 报告结构要求
            请严格按以下章节输出：
            
            # {taskName} — 模型评测分析报告
            
            ## 一、评测概况
            - 任务名称、模型信息、测评集、评测时间
            - 一句话总体评价
            
            ## 二、核心指标解读
            逐项分析以下指标（有值才写）：
            - **准确率类**：Top-1 Accuracy / Top-5 Accuracy / mAP 等
            - **精确率与召回率**：整体及各类别表现
            - **F1-Score**：综合性能
            - **混淆矩阵分析**：指出最容易混淆的类别对，分析原因
            - **推理速度**：预处理 / 推理 / 后处理耗时分析
            
            ## 三、类别级详细分析
            如果有各类别 Precision/Recall/F1 数据，按表格列出：
            | 类别 | 样本数 | Precision | Recall | F1-Score |
            
            ## 四、优势与不足
            ### 优势（3~5条）
            ### 不足与风险（2~4条）
            
            五、改进建议
            给出 3~5 条具体可行的优化方向（如数据增强、超参调整、模型选型等）
            
            ## 六、结论与部署建议
            - 是否适合当前场景部署？
            - 与同类模型对比的相对位置
            - 最终推荐意见
            
            ---
            *本报告由 AISys 评测平台自动生成*
            """;

    /**
     * 运行时数据上下文（动态合成，注入具体评测数据）。
     */
    private static final String USER_PROMPT_TEMPLATE = """
            ## 评测基本信息
            - **任务名称**: {taskName}
            - **模型**: {modelName} {modelVersion}
            - **测评集**: {benchmarkName} ({benchmarkDescription})
            - **评测时间**: {evalTime}
            - **样本总数**: {sampleCount}
            
            ## 数据集描述
            {datasetDescription}
            
            ## 模型描述
            {modelDescription}
            
            ## 原始评测结果（JSON）
            ```json
            {metricsJson}
            ```
            
            ## 各类别指标详情
            {classMetricsTable}
            
            请根据以上数据撰写完整的评测分析报告。
            """;

    /** 报告构建所需的数据上下文。 */
    private record ReportContext(
            String taskName,
            String modelName,
            String modelVersion,
            String benchmarkName,
            String benchmarkDescription,
            String datasetDescription,
            String modelDescription,
            String evalTime,
            Integer sampleCount,
            String metricsJson,
            String classMetricsTable
    ) {}

    /**
     * 从数据库收集报告所需的全部上下文数据。
     */
    private ReportContext buildContext(Long taskId, Long tenantId) {
        EvaluationTask task = taskMapper.selectByIdAndTenant(taskId, tenantId);
        if (task == null) throw new BusinessException(EvaluationErrorCode.EVALUATION_TASK_NOT_FOUND);

        // 评测结果
        List<EvaluationResult> results = resultMapper.selectByTaskId(taskId, tenantId);
        Map<String, Object> overallScores = results.isEmpty()
                ? Map.of()
                : EvalJson.toMap(results.get(0).getOverallScores());

        // 模型信息
        String modelName = "未知模型";
        String modelVersion = "";
        String modelDesc = "暂无模型描述";
        if (!results.isEmpty()) {
            Long mvid = results.get(0).getModelVersionId();
            log.info("[Report] 查询模型信息 modelVersionId={}", mvid);
            try {
                var resp = modelClient.getVersionById(mvid);
                log.info("[Report] modelClient 响应 isSuccess={} data={}",
                        resp != null ? resp.isSuccess() : "null",
                        resp != null && resp.data() != null ? "有数据" : "null");
                if (resp != null && resp.isSuccess() && resp.data() != null) {
                    Map<String, Object> v = resp.data();
                    modelName = (String) v.getOrDefault("modelName", v.getOrDefault("name", "未知模型"));
                    modelVersion = (String) v.getOrDefault("version", "");
                    modelDesc = (String) v.getOrDefault("description", "暂无模型描述");
                }
            } catch (Exception e) {
                log.warn("[Report] 获取模型信息失败: {}", e.getMessage());
            }
        } else {
            log.warn("[Report] 无评测结果，跳过模型/数据集信息查询");
        }

        // 测评集信息
        String benchmarkName = "未知测评集";
        String benchmarkDesc = "";
        String datasetDesc = "暂无数据集描述";
        var benchmark = benchmarkMapper.selectByIdAndTenant(task.getBenchmarkId(), tenantId);
        if (benchmark != null) {
            benchmarkName = benchmark.getName();
            benchmarkDesc = benchmark.getDescription() != null ? benchmark.getDescription() : "";

            // 通过 DatasetClient 获取数据集描述
            if (benchmark.getDatasetVersionIds() != null && !benchmark.getDatasetVersionIds().isBlank()) {
                try {
                    java.util.List<Long> dsVersionIds = objectMapper.readValue(
                            benchmark.getDatasetVersionIds(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Long>>() {});
                    if (!dsVersionIds.isEmpty()) {
                        var dsResp = datasetClient.getVersionById(dsVersionIds.get(0));
                        if (dsResp != null && dsResp.isSuccess() && dsResp.data() != null) {
                            Map<String, Object> dsInfo = dsResp.data();
                            Object desc = dsInfo.get("description");
                            if (desc != null && !desc.toString().isBlank()) {
                                datasetDesc = desc.toString();
                            } else {
                                Object name = dsInfo.get("name");
                                if (name != null && !name.toString().isBlank()) {
                                    datasetDesc = name.toString();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[Report] 获取数据集描述失败: {}", e.getMessage());
                }
            }
        }

        // 构建各类别指标表
        String classTable = buildClassMetricsTable(overallScores);

        // metrics JSON
        String metricsJson;
        try {
            metricsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(overallScores);
        } catch (Exception e) {
            metricsJson = overallScores.toString();
        }

        return new ReportContext(
                task.getName(),
                modelName, modelVersion,
                benchmarkName, benchmarkDesc,
                datasetDesc, modelDesc,
                task.getCompletedAt() != null ? DT_FMT.format(task.getCompletedAt()) : DT_FMT.format(Instant.now()),
                results.isEmpty() ? 0 : results.stream()
                        .mapToInt(r -> r.getSampleCount() != null ? r.getSampleCount() : 0).sum(),
                metricsJson,
                classTable
        );
    }

    /**
     * 将系统模板 + 数据合成为最终用户提示词。
     */
    private String buildUserPrompt(ReportContext ctx) {
        return USER_PROMPT_TEMPLATE
                .replace("{taskName}", ctx.taskName())
                .replace("{modelName}", ctx.modelName())
                .replace("{modelVersion}", ctx.modelVersion())
                .replace("{benchmarkName}", ctx.benchmarkName())
                .replace("{benchmarkDescription}", nonNull(ctx.benchmarkDescription()))
                .replace("{datasetDescription}", nonNull(ctx.datasetDescription()))
                .replace("{modelDescription}", nonNull(ctx.modelDescription()))
                .replace("{evalTime}", ctx.evalTime())
                .replace("{sampleCount}", String.valueOf(ctx.sampleCount()))
                .replace("{metricsJson}", ctx.metricsJson())
                .replace("{classMetricsTable}", nonNull(ctx.classMetricsTable()));
    }

    /**
     * 从 confusion_matrix 构建各类别 P/R/F1 表格（Markdown 格式）。
     */
    private String buildClassMetricsTable(Map<String, Object> scores) {
        Object cmObj = scores.get("confusion_matrix");
        if (!(cmObj instanceof List<?> matrix)) return "无混淆矩阵数据";

        StringBuilder sb = new StringBuilder();
        sb.append("| 类别 | 样本数 | Precision | Recall | F1-Score |\n");
        sb.append("|------|--------|-----------|--------|----------|\n");

        int n = matrix.size();
        for (int i = 0; i < n; i++) {
            List<?> row = (List<?>) matrix.get(i);
            double tp = ((Number) row.get(i)).doubleValue();  // 对角线
            double fp = 0, fn = 0;
            for (int j = 0; j < n; j++) {
                if (i != j) fp += ((Number) row.get(j)).doubleValue();  // 行方向非对角 = FP
            }
            for (int j = 0; j < n; j++) {
                if (i != j) fn += ((Number) ((List<?>) matrix.get(j)).get(i)).doubleValue();  // 列方向非对角 = FN
            }
            double prec = (tp + fp) > 0 ? tp / (tp + fp) : 0;
            double rec = (tp + fn) > 0 ? tp / (tp + fn) : 0;
            double f1 = (prec + rec) > 0 ? 2 * prec * rec / (prec + rec) : 0;
            double support = 0;
            for (int j = 0; j < n; j++) support += ((Number) row.get(j)).doubleValue();

            sb.append(String.format("| 类%d | %.0f | %.4f | %.4f | %.4f |\n",
                    i, support, prec, rec, f1));
        }
        return sb.toString();
    }

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
                options.put("num_predict", 4096);
                requestBody = objectMapper.writeValueAsString(body);
            } else {
                // ========== OpenAI Chat Completions 兼容格式 ==========
                // URL: http://host:port/v1/chat/completions (自动补全)
                fullUrl = llmUrl.endsWith("/v1/chat/completions") ? llmUrl : llmUrl + "/v1/chat/completions";
                String cleanModel = (llmModel != null) ? llmModel.replaceAll("^\"+|\"+$", "") : "";
                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", cleanModel);
                body.put("temperature", 0.3);
                body.put("max_tokens", 4096);

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
