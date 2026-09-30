<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { Document, Download } from "@element-plus/icons-vue";
import { useDark, useECharts } from "@pureadmin/utils";
import dayjs from "dayjs";
import {
  getTaskDetail,
  getTaskMetrics,
  getTaskLogs,
  type TrainingTask,
  type TrainingMetricPoint,
  type TrainingLog,
  type Checkpoint
} from "@/api/training";
import { downloadFile } from "@/api/storage";

defineOptions({ name: "TrainingMonitor" });

const route = useRoute();
const router = useRouter();
const taskId = computed(() => String(route.params.id ?? ""));

const { isDark } = useDark();
const theme = computed(() => (isDark.value ? "dark" : "light"));

const loading = ref(false);
const task = ref<TrainingTask | null>(null);

const chartRef = ref();
const { setOptions } = useECharts(chartRef, {
  theme,
  renderer: "svg"
});

const statusTypeMap: Record<string, string> = {
  pending: "info",
  queued: "primary",
  running: "success",
  completed: "success",
  failed: "danger",
  cancelled: "warning",
  paused: "warning"
};
const statusTextMap: Record<string, string> = {
  pending: "等待中",
  queued: "排队中",
  running: "运行中",
  completed: "已完成",
  failed: "已失败",
  cancelled: "已取消",
  paused: "已暂停"
};

/** 日志等级 -> tag type */
const logLevelType: Record<string, string> = {
  debug: "info",
  info: "",
  warn: "warning",
  warning: "warning",
  error: "danger",
  fatal: "danger"
};

const logsLoading = ref(false);
const logs = ref<TrainingLog[]>([]);
const logQuery = reactive({ page: 1, size: 200 });
const logTotal = ref(0);
const logBoxRef = ref<HTMLDivElement | null>(null);
const ansiPattern = /\x1b\[[;?0-9]*[ -/]*[@-~]/g;

const normalizedLogs = computed(() =>
  logs.value.map(log => ({
    ...log,
    displayTime: formatLogTime(log.loggedAt ?? log.ts),
    displayMessage: sanitizeLogMessage(log.message)
  }))
);


function sanitizeLogMessage(message?: string) {
  return String(message ?? "")
    .replace(ansiPattern, "")
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "");
}

function formatLogTime(value?: string | number) {
  if (value == null || value === "") return "-";
  const date = typeof value === "number" ? new Date(value) : new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleTimeString("zh-CN", { hour12: false });
}

function isDownloadableCheckpoint(cp: Checkpoint) {
  return Boolean(cp.storagePath) && !cp.storagePath.startsWith("internal://");
}

async function loadDetail() {
  try {
    const res = await getTaskDetail(taskId.value);
    if (res.code === 0) {
      task.value = res.data ?? null;
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "加载任务详情失败");
  }
}

function buildChartOption(points: TrainingMetricPoint[]) {
  // 排序：有 step 按 step，step 为空按 ts 时间字符串；都没有保持原序
  const sorted = [...points].sort((a, b) => {
    const sa = a.step ?? null;
    const sb = b.step ?? null;
    if (sa != null && sb != null) return sa - sb;
    if (sa != null) return -1;
    if (sb != null) return 1;
    return String(a.ts ?? "").localeCompare(String(b.ts ?? ""));
  });
  // x 轴：任一点有 step 就用 step，否则用时间（formatLogTime 已定义在下面，hoist 后可用）
  const hasStep = sorted.some(p => p.step != null);
  const xData = sorted.map(p =>
    hasStep ? String(p.step ?? "") : formatLogTime(p.ts)
  );
  // 动态收集所有 metric key 作为 series（不硬编码 loss/lr/accuracy，
  // 容器 emit 什么就画什么：top1_acc / mAP50 / val_loss / ...）
  const keySet = new Set<string>();
  sorted.forEach(p => {
    if (p.metrics) Object.keys(p.metrics).forEach(k => keySet.add(k));
  });
  const keys = Array.from(keySet);
  const series = keys.map(k => ({
    name: k,
    type: "line",
    smooth: true,
    symbol: "circle",
    symbolSize: 6,
    data: sorted.map(p =>
      p.metrics && p.metrics[k] != null ? p.metrics[k] : null
    )
  }));
  return {
    title: { text: "训练指标", left: "center" },
    tooltip: { trigger: "axis" },
    legend: { data: keys, top: 40 },
    grid: { top: 95, left: 60, right: 40, bottom: 50 },
    xAxis: { type: "category", name: hasStep ? "step" : "时间", data: xData },
    yAxis: { type: "value" },
    series
  };
}

/** 无指标数据时显示空图（不回退示例数据，避免误导用户以为没接通） */
function buildEmptyOption() {
  return {
    title: {
      text: "暂无指标数据",
      left: "center",
      top: "middle",
      textStyle: { color: "#999", fontSize: 14 }
    },
    grid: { top: 70, left: 60, right: 40, bottom: 50 },
    xAxis: { type: "category", data: [] },
    yAxis: { type: "value" },
    series: []
  };
}

async function loadMetrics() {
  try {
    const res = await getTaskMetrics(taskId.value);
    // 后端返回 ApiResponse<PageResult<MetricPoint>>：res.data = {items,total,...}（对象，非数组）
    const rd: any = (res as any)?.data;
    const points: TrainingMetricPoint[] = Array.isArray(rd)
      ? rd
      : (rd?.items ?? []);
    await nextTick();
    setOptions(
      Array.isArray(points) && points.length > 0
        ? buildChartOption(points)
        : buildEmptyOption(),
      { notMerge: true }
    );
  } catch (e: any) {
    await nextTick();
    setOptions(buildEmptyOption(), { notMerge: true });
  }
}

async function loadLogs() {
  logsLoading.value = true;
  try {
    const res = await getTaskLogs(taskId.value, {
      page: logQuery.page,
      size: logQuery.size
    });
    if (res.code === 0 && res.data) {
      // 后端 ORDER BY logged_at DESC 返回最新 N 条；反转为 ASC 渲染，最新在底部，配合 scrollToBottom。
      logs.value = ((res.data.items ?? []) as TrainingLog[]).slice().reverse();
      logTotal.value = res.data.total ?? 0;
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "加载日志失败");
  } finally {
    logsLoading.value = false;
    await nextTick();
    scrollToBottom();
  }
}

function scrollToBottom() {
  if (logBoxRef.value) {
    logBoxRef.value.scrollTop = logBoxRef.value.scrollHeight;
  }
}

function logType(level: string) {
  return logLevelType[(level || "").toLowerCase()] ?? "";
}

function reload() {
  loading.value = true;
  Promise.all([loadDetail(), loadMetrics(), loadLogs()]).finally(() => {
    loading.value = false;
  });
}

function isTerminalStatus(status?: string) {
  return status === "completed" || status === "failed" || status === "cancelled";
}

let timer: any = null;
function startPolling() {
  stopPolling();
  // running 态 3s 轮询（训练实时性强，10s 太慢看不到 epoch 进度）；任务到终态后停止轮询
  timer = setInterval(() => {
    Promise.all([loadDetail(), loadMetrics(), loadLogs()]).then(() => {
      if (task.value && isTerminalStatus(task.value.status)) {
        stopPolling();
      }
    });
  }, 3000);
}
function stopPolling() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

onMounted(() => {
  reload();
  startPolling();
});

onBeforeUnmount(() => {
  stopPolling();
});

function goBack() {
  router.push("/training/list");
}

/** 下载权重文件 */
function handleDownload(cp: Checkpoint) {
  if (!isDownloadableCheckpoint(cp)) {
    ElMessage.warning("该检查点没有可下载的文件路径");
    return;
  }
  const fileName = cp.storagePath.split("/").pop() || "weights.pt";
  ElMessage.info(`正在下载: ${fileName}`);
  downloadFile({ path: cp.storagePath });
}
</script>

<template>
  <div v-loading="loading" class="p-5">
    <!-- 顶部状态条 -->
    <el-card shadow="never" class="mb-4">
      <div class="flex items-center justify-between flex-wrap gap-3">
        <div class="flex items-center gap-4 flex-wrap">
          <el-button link @click="goBack">返回</el-button>
          <h3 class="text-base font-bold m-0">
            {{ task?.name ?? "训练任务" }}
          </h3>
          <el-tag
            v-if="task"
            :type="statusTypeMap[task.status] ?? 'info'"
            effect="light"
          >
            {{ statusTextMap[task.status] ?? task.status }}
          </el-tag>
        </div>
        <div v-if="task" class="flex items-center gap-6 flex-wrap text-sm">
          <span>
            镜像：
            <span class="text-gray-600">{{ task.image }}</span>
          </span>
          <span>
            GPU：
            <span class="text-gray-600">
              {{ task.resourceSpec?.gpuCount ?? 0 }}
            </span>
          </span>
          <span>
            节点：
            <span class="text-gray-600">{{ task.node || "-" }}</span>
          </span>
          <span v-if="typeof task.progress === 'number'">
            进度：
            <span class="text-gray-600">{{ task.progress }}%</span>
          </span>
          <span>
            创建：
            <span class="text-gray-600">{{ task.createdAt ? dayjs(task.createdAt).format("YYYY-MM-DD HH:mm:ss") : "-" }}</span>
          </span>
        </div>
      </div>
      <div v-if="task" class="mt-3">
        <el-progress
          v-if="typeof task.progress === 'number'"
          :percentage="Math.min(Math.max(task.progress, 0), 100)"
          :status="
            task.status === 'failed'
              ? 'exception'
              : task.status === 'completed'
                ? 'success'
                : undefined
          "
        />
      </div>
    </el-card>

    <!-- 训练产物（任务完成且有检查点时显示） -->
    <el-card v-if="task?.status === 'completed' && task.checkpoints && task.checkpoints.length > 0" shadow="never" class="mb-4">
      <template #header>
        <div class="flex items-center justify-between">
          <span class="font-bold">训练产物</span>
          <el-tag type="success" size="small" effect="light">训练完成</el-tag>
        </div>
      </template>
      <div class="flex flex-col gap-3">
        <div
          v-for="cp in task.checkpoints"
          :key="cp.id"
          class="flex items-center justify-between p-3 rounded-lg bg-gray-50 dark:bg-gray-900/50"
        >
          <div class="flex items-center gap-3 min-w-0">
            <el-icon :size="24" class="text-blue-500 flex-shrink-0"><Document /></el-icon>
            <div class="min-w-0">
              <div class="font-medium text-sm truncate">
                {{ cp.storagePath ? cp.storagePath.split('/').pop() || cp.storagePath : '权重文件' }}
              </div>
              <div class="text-xs text-gray-400 mt-0.5">
                Step {{ cp.step ?? '-' }}
                <span v-if="cp.loss != null" class="ml-2">Loss: {{ typeof cp.loss === 'number' ? cp.loss.toFixed(4) : cp.loss }}</span>
                <span v-if="cp.createdAt" class="ml-2">{{ dayjs(cp.createdAt).format("YYYY-MM-DD HH:mm:ss") }}</span>
              </div>
            </div>
          </div>
          <el-button
            type="primary"
            size="small"
            @click="handleDownload(cp)"
            :disabled="!isDownloadableCheckpoint(cp)"
            class="flex-shrink-0 ml-3"
          >
            <template #icon><Download /></template>
            下载权重
          </el-button>
        </div>
      </div>
    </el-card>

    <el-row :gutter="16">
      <!-- 指标图表 -->
      <el-col :xs="24" :md="14" :lg="15">
        <el-card shadow="never" header="训练指标">
          <div ref="chartRef" style="width: 100%; height: 420px" />
        </el-card>
      </el-col>

      <!-- 训练日志 -->
      <el-col :xs="24" :md="10" :lg="9">
        <el-card shadow="never">
          <template #header>
            <div class="flex items-center justify-between">
              <span>训练日志</span>
              <el-button link type="primary" size="small" @click="loadLogs">
                刷新
              </el-button>
            </div>
          </template>
          <div
            ref="logBoxRef"
            v-loading="logsLoading"
            class="log-box"
          >
            <div
              v-for="(log, idx) in normalizedLogs"
              :key="idx"
              class="log-line"
            >
              <span class="log-ts">{{ log.displayTime }}</span>
              <el-tag
                size="small"
                :type="logType(log.level) as any"
                effect="plain"
                class="log-level"
              >
                {{ (log.level || "info").toUpperCase() }}
              </el-tag>
              <span class="log-msg">{{ log.displayMessage }}</span>
            </div>
            <div v-if="!logs.length && !logsLoading" class="log-empty">
              暂无日志
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
.log-box {
  height: 420px;
  overflow-y: auto;
  background-color: var(--el-fill-color-light);
  border-radius: 4px;
  padding: 8px;
  font-family: "JetBrains Mono", Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
}

.log-line {
  display: grid;
  grid-template-columns: max-content max-content minmax(0, 1fr);
  align-items: flex-start;
  gap: 8px;
  padding: 2px 0;
  word-break: normal;
}

.log-ts {
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.log-level {
  flex-shrink: 0;
  transform: translateY(1px);
}

.log-msg {
  flex: 1;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.log-empty {
  color: var(--el-text-color-secondary);
  text-align: center;
  padding: 40px 0;
}
</style>
