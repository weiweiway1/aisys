<script setup lang="ts">
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useDark, useECharts } from "@pureadmin/utils";
import {
  getTaskDetail,
  getTaskMetrics,
  getTaskLogs,
  type TrainingTask,
  type TrainingMetricPoint,
  type TrainingLog
} from "@/api/training";

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
  const sorted = [...points].sort((a, b) => a.step - b.step);
  const steps = sorted.map(p => p.step);
  const loss = sorted.map(p => p.metrics?.loss ?? null);
  const lr = sorted.map(p => p.metrics?.lr ?? null);
  const accuracy = sorted.map(p => p.metrics?.accuracy ?? null);
  return {
    title: {
      text: "训练指标",
      left: "center"
    },
    tooltip: {
      trigger: "axis"
    },
    legend: {
      data: ["loss", "lr", "accuracy"],
      top: 30
    },
    grid: {
      top: 70,
      left: 60,
      right: 40,
      bottom: 50
    },
    xAxis: {
      type: "category",
      name: "step",
      data: steps
    },
    yAxis: {
      type: "value"
    },
    series: [
      {
        name: "loss",
        type: "line",
        smooth: true,
        symbol: "circle",
        symbolSize: 6,
        data: loss
      },
      {
        name: "lr",
        type: "line",
        smooth: true,
        symbol: "circle",
        symbolSize: 6,
        data: lr
      },
      {
        name: "accuracy",
        type: "line",
        smooth: true,
        symbol: "circle",
        symbolSize: 6,
        data: accuracy
      }
    ]
  };
}

/** 示例数据，后端无指标时展示 */
function buildSampleOption() {
  const steps = Array.from({ length: 20 }, (_, i) => i + 1);
  const loss = steps.map(s => Math.max(0.05, 2.5 * Math.exp(-s / 6)));
  const lr = steps.map(s => 0.01 * (1 - s / 40));
  const accuracy = steps.map(s => Math.min(0.99, 0.3 + s * 0.03));
  return {
    title: { text: "训练指标（示例数据）", left: "center" },
    tooltip: { trigger: "axis" },
    legend: { data: ["loss", "lr", "accuracy"], top: 30 },
    grid: { top: 70, left: 60, right: 40, bottom: 50 },
    xAxis: { type: "category", name: "step", data: steps },
    yAxis: { type: "value" },
    series: [
      { name: "loss", type: "line", smooth: true, data: loss },
      { name: "lr", type: "line", smooth: true, data: lr },
      { name: "accuracy", type: "line", smooth: true, data: accuracy }
    ]
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
        : buildSampleOption(),
      { notMerge: true }
    );
  } catch (e: any) {
    await nextTick();
    setOptions(buildSampleOption(), { notMerge: true });
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
      logs.value = (res.data.items ?? []) as TrainingLog[];
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

let timer: any = null;
function startPolling() {
  stopPolling();
  timer = setInterval(() => {
    loadDetail();
    loadMetrics();
    loadLogs();
  }, 10000);
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
            <span class="text-gray-600">{{ task.createdAt || "-" }}</span>
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
              v-for="(log, idx) in logs"
              :key="idx"
              class="log-line"
            >
              <span class="log-ts">{{ log.loggedAt || log.ts }}</span>
              <el-tag
                size="small"
                :type="logType(log.level) as any"
                effect="plain"
                class="log-level"
              >
                {{ (log.level || "info").toUpperCase() }}
              </el-tag>
              <span class="log-msg">{{ log.message }}</span>
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
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 2px 0;
  word-break: break-all;
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
}

.log-empty {
  color: var(--el-text-color-secondary);
  text-align: center;
  padding: 40px 0;
}
</style>
