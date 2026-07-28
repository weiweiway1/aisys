<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElButton, ElTag, ElSkeleton } from "element-plus";
import {
  getEvaluationReport,
  getEvaluationTaskResults,
  regenerateEvaluationReport
} from "@/api/evaluation";
import ArrowLeft from "~icons/ep/arrow-left";
import Refresh from "~icons/ep/refresh";
import Download from "~icons/ep/download";
import Document from "~icons/ri/file-text-line";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";

defineOptions({ name: "EvaluationReport" });

const route = useRoute();
const router = useRouter();
const taskId = computed(() => Number(route.params.id));

// ---- 状态 ----
const loading = ref(true);
const generating = ref(false);
const report = ref<any>(null);       // 报告数据
const results = ref<any[]>([]);       // 评测结果（用于展示指标概览）
const rawMd = ref("");               // Markdown 原文（用于渲染）
let pollTimer: ReturnType<typeof setTimeout> | null = null;   // 轮询定时器

/** 状态映射 */
const statusConfig: Record<string, { type: string; label: string; color: string }> = {
  pending: { type: "info", label: "等待生成", color: "#909399" },
  generating: { type: "warning", label: "正在生成中...", color: "#E6A23C" },
  completed: { type: "success", label: "已完成", color: "#67C23A" },
  failed: { type: "danger", label: "生成失败", color: "#F56C6C" }
};

const currentStatus = computed(() => report.value?.status ?? "pending");
const statusInfo = computed(() => statusConfig[currentStatus.value] || statusConfig.pending);

// ---- 加载数据 ----
async function loadData() {
  if (!taskId.value) return;
  loading.value = true;

  try {
    // 并行加载报告和评测结果
    const [reportRes, resultsRes] = await Promise.all([
      getEvaluationReport(taskId.value),
      getEvaluationTaskResults(taskId.value)
    ]);

    if (reportRes?.code === 0 && reportRes.data) {
      report.value = reportRes.data;
      rawMd.value = reportRes.data.contentMd || "";
      // 如果状态是生成中，5 秒后自动轮询
      if (reportRes.data.status === "generating") {
        clearPollTimer();
        pollTimer = setTimeout(loadData, 5000);
      }
    }

    if (resultsRes?.code === 0 && resultsRes.data) {
      results.value = resultsRes.data;
    }
  } catch (e: any) {
    console.error("加载报告失败:", e);
  } finally {
    loading.value = false;
  }
}

/** 清除轮询定时器 */
function clearPollTimer() {
  if (pollTimer != null) {
    clearTimeout(pollTimer);
    pollTimer = null;
  }
}

// ---- 重新生成 ----
async function handleRegenerate() {
  if (!taskId.value) return;
  generating.value = true;
  try {
    const res = await regenerateEvaluationReport(taskId.value, true);
    if (res?.code === 0) {
      ElMessage.success("已触发报告生成，请稍候刷新");
      // 进入 generating 状态，开始轮询
      rawMd.value = "";
      report.value = { ...report.value, status: "generating" };
      clearPollTimer();
      pollTimer = setTimeout(loadData, 3000);
    } else {
      ElMessage.error(res?.message || "触发失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "触发失败");
  } finally {
    generating.value = false;
  }
}

// ---- 下载 ----
function handleDownloadMd() {
  window.open(`/api/v1/evaluation/tasks/${taskId.value}/report/download/md`, "_blank");
}

function handleDownloadWord() {
  window.open(`/api/v1/evaluation/tasks/${taskId.value}/report/download/word`, "_blank");
}

// ---- 返回列表 ----
function goBack() {
  router.push("/evaluation/tasks");
}

/** 格式化指标键名：top1_acc → Top-1 准确率 */
function formatMetricKey(key: string): string {
  const map: Record<string, string> = {
    top1_acc: "Top-1 准确率",
    top5_acc: "Top-5 准确率",
    precision: "精确率",
    recall: "召回率",
    f1_score: "F1 值",
    mAP50: "mAP@50",
    mAP50_95: "mAP@50-95",
    speed_inference: "推理速度",
    speed_preprocess: "预处理速度",
    speed_postprocess: "后处理速度"
  };
  return map[key] || key.replace(/_/g, " ");
}

/** 格式化指标值：百分比 / 毫秒等 */
function formatMetricValue(key: string, val: number): string {
  if (key.startsWith("speed")) {
    return val >= 1 ? `${val.toFixed(2)} ms` : `${(val * 1000).toFixed(1)} µs`;
  }
  if (["top1_acc", "top5_acc", "accuracy", "precision", "recall", "f1_score", "mAP50"].includes(key)) {
    return `${(val * 100).toFixed(2)}%`;
  }
  return typeof val === "number" ? val.toFixed(4) : String(val);
}

/**
 * 转义 HTML 特殊字符（用于嵌入 HTML 的用户内容）。
 */
function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/** 简单的 Markdown → HTML 渲染（无需额外依赖） */
function renderMarkdown(md: string): string {
  if (!md) return "";
  let html = md
    // 代码块（提前提取，避免后续正则干扰内部内容）
    .replace(/```(\w*)\n([\s\S]*?)```/g, (_m, lang, code) => {
      return `<pre><code class="${lang}">${escapeHtml(code.trim())}</code></pre>`;
    })
    // 行内代码
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    // 标题
    .replace(/^#### (.+)$/gm, "<h4>$1</h4>")
    .replace(/^### (.+)$/gm, "<h3>$1</h3>")
    .replace(/^## (.+)$/gm, "<h2>$1</h2>")
    .replace(/^# (.+)$/gm, "<h1>$1</h1>")
    // 粗体 + 斜体
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/\*(.+?)\*/g, "<em>$1</em>")
    // 分隔线
    .replace(/^---$/gm, "<hr/>")
    // 表格（简单处理）
    .replace(/^\|(.+)\|$/gm, (_match, content) => {
      const cells = content.split("|").map(c => c.trim());
      if (cells.every(c => /^[-:]+$/.test(c))) return ""; // 分隔行跳过
      const tag = _match.startsWith("| ") ? "td" : "th";
      return `<tr>${cells.map(c => `<${tag}>${c}</${tag}>`).join("")}</tr>`;
    })
    // 无序列表 / 有序列表
    .replace(/^- (.+)$/gm, "<li>$1</li>")
    .replace(/^\d+\. (.+)$/gm, "<li>$1</li>")
    // 引用块
    .replace(/^> (.+)$/gm, "<blockquote>$1</blockquote>")
    // 段落换行（最后处理）
    .replace(/\n\n+/g, "</p><p>")
    .replace(/\n/g, "<br/>");

  return `<p>${html}</p>`;
}

onMounted(() => {
  loadData();
});

// 组件卸载时清除定时器，防止内存泄漏
onUnmounted(() => {
  clearPollTimer();
});
</script>

<template>
  <div class="report-page">
    <!-- 头部 -->
    <div class="header">
      <div class="header-left">
        <el-button :icon="useRenderIcon(ArrowLeft)" text @click="goBack">返回任务列表</el-button>
        <h2 class="title">评测分析报告</h2>
        <ElTag v-if="report" :type="(statusInfo as any).type" effect="dark">
          {{ statusInfo.label }}
        </ElTag>
      </div>
      <div class="header-actions">
        <el-button
          v-if="currentStatus !== 'generating'"
          :loading="generating"
          :icon="useRenderIcon(Refresh)"
          @click="handleRegenerate"
        >
          {{ currentStatus === "failed" ? "重新生成" : "重新生成" }}
        </el-button>
        <el-button
          v-if="currentStatus === 'completed' && rawMd"
          :icon="useRenderIcon(Document)"
          @click="handleDownloadMd"
        >
          下载 Markdown
        </el-button>
        <el-button
          v-if="currentStatus === 'completed' && rawMd"
          type="primary"
          :icon="useRenderIcon(Download)"
          @click="handleDownloadWord"
        >
          下载 Word
        </el-button>
      </div>
    </div>

    <!-- 骨架屏 -->
    <el-skeleton v-if="loading" :rows="12" animated />

    <!-- 无报告提示 -->
    <div v-else-if="!report || !rawMd" class="empty-state">
      <div class="empty-icon">📋</div>
      <h3>{{ currentStatus === "pending" ? "暂无评测报告" : "报告生成失败" }}</h3>
      <p v-if="currentStatus === 'pending'" class="empty-desc">
        评测完成后系统将自动生成分析报告。您也可以手动触发生成。
      </p>
      <p v-else-if="currentStatus === 'failed'" class="empty-desc error">
        {{ report?.errorMessage || "未知错误" }}
      </p>
      <el-button type="primary" :loading="generating" @click="handleRegenerate">
        立即生成报告
      </el-button>
    </div>

    <!-- 报告内容（Markdown 渲染） -->
    <div v-else class="report-content">
      <!-- 指标概览卡片 -->
      <div v-if="results.length > 0" class="metrics-summary">
        <h4>📊 评测数据概览</h4>
        <div class="metric-cards">
          <div
            v-for="(result, idx) in results"
            :key="idx"
            class="metric-card"
          >
            <span class="model-name">
              {{ result.modelName || `模型 #${result.modelVersionId}` }}
            </span>
            <div class="metrics-grid" v-if="result.overallScores">
              <div
                v-for="[key, val] of Object.entries(result.overallScores)
                  .filter(([,v]) => typeof v === 'number' && !Array.isArray(v))
                  .slice(0, 6)"
                :key="key"
                class="metric-item"
              >
                <span class="key">{{ formatMetricKey(key) }}</span>
                <span class="val">{{ formatMetricValue(key, val) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Markdown 正文 -->
      <div class="md-body" v-html="renderMarkdown(rawMd)"></div>

      <!-- 报告元信息 -->
      <div class="meta-info">
        <span v-if="report.llmModel">LLM 模型: {{ report.llmModel }}</span>
        <span v-if="report.generatedAt">
          生成时间: {{ new Date(report.generatedAt).toLocaleString() }}
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.report-page {
  padding: 20px;
  max-width: 1100px;
  margin: 0 auto;
}
.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  flex-wrap: wrap;
  gap: 12px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.header-left .title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}
.header-actions {
  display: flex;
  gap: 8px;
}

/* 空状态 */
.empty-state {
  text-align: center;
  padding: 80px 20px;
}
.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}
.empty-desc {
  color: #909399;
  margin-bottom: 20px;
  max-width: 400px;
  margin-left: auto;
  margin-right: auto;
}
.empty-desc.error {
  color: #F56C6C;
}

/* 指标概览 */
.metrics-summary {
  background: #f8f9fa;
  border-radius: 10px;
  padding: 18px 22px;
  margin-bottom: 24px;
}
.metrics-summary h4 {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
}
.metric-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
}
.metric-card {
  background: white;
  border-radius: 8px;
  padding: 14px 18px;
  box-shadow: 0 1px 4px rgba(0,0,0,.06);
}
.model-name {
  font-weight: 600;
  color: #303133;
  font-size: 14px;
  display: block;
  margin-bottom: 10px;
}
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.metric-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 4px;
  border-radius: 6px;
  background: #fafbfc;
}
.metric-item .key {
  font-size: 11px;
  color: #909399;
  text-transform: uppercase;
}
.metric-item .val {
  font-size: 16px;
  font-weight: 700;
  color: #409eff;
}

/* Markdown 内容 */
.md-body {
  line-height: 1.8;
  color: #303133;
  font-size: 15px;
  word-wrap: break-word;
}
.md-body :deep(h1) {
  font-size: 26px; margin: 28px 0 16px; padding-bottom: 10px;
  border-bottom: 2px solid #e4e7ed;
}
.md-body :deep(h2) {
  font-size: 21px; margin: 24px 0 12px; color: #1a1a1a;
  border-left: 4px solid #409eff; padding-left: 12px;
}
.md-body :deep(h3) {
  font-size: 17px; margin: 20px 0 10px; color: #333;
}
.md-body :deep(p) { margin: 8px 0; }
.md-body :deep(strong) { color: #1a1a1a; }
.md-body :deep(table) {
  width: 100%; border-collapse: collapse; margin: 16px 0;
  font-size: 14px;
}
.md-body :deep(th), .md-body :deep(td) {
  border: 1px solid #ebeef5; padding: 9px 12px; text-align: left;
}
.md-body :deep(th) {
  background: #f5f7fa; font-weight: 600; color: #606266;
}
.md-body :deep(tr:nth-child(even)) { background: #fafbfc; }
.md-body :deep(code) {
  background: #f2f3f5; padding: 2px 6px; border-radius: 4px;
  font-family: 'Fira Code', monospace; font-size: 13px; color: #e74c3c;
}
.md-body :deep(pre) {
  background: #1e1e1e; color: #d4d4d4; padding: 16px; border-radius: 8px;
  overflow-x: auto; margin: 16px 0;
}
.md-body :deep(pre code) {
  background: none; color: inherit; padding: 0;
}
.md-body :deep(hr) { border: none; border-top: 1px dashed #dcdfe6; margin: 24px 0; }
.md-body :deep(blockquote) {
  border-left: 4px solid #409eff; margin: 12px 0; padding: 8px 16px;
  background: #ecf5ff; color: #606266;
}
.md-body :deep(ul), .md-body :deep(ol) { padding-left: 24px; margin: 8px 0; }
.md-body :deep(li) { margin: 4px 0; }

/* 元信息 */
.meta-info {
  margin-top: 32px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
  font-size: 13px;
  color: #909399;
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}
</style>
