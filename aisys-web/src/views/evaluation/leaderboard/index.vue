<script setup lang="ts">
import { h, ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from "vue";
import { ElMessage, ElTag, ElEmpty, ElTabs, ElTabPane } from "element-plus";
import echarts from "@/plugins/echarts";
import { getLeaderboard, getBenchmarkList, getBenchmarkMetrics } from "@/api/evaluation";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";

/** echarts.init 返回的实例类型 */
type EChartsInstance = ReturnType<typeof echarts.init>;

defineOptions({
  name: "EvaluationLeaderboard"
});

// ======================== 基础状态 ========================
const loading = ref(false);
const benchmarkId = ref<number | undefined>(undefined);
const benchmarkOptions = ref<Array<{ id: number; label: string }>>([]);
const sortBy = ref("");

/** 内置兜底指标 */
const FALLBACK_METRIC_OPTIONS = [
  { value: "top1_acc", label: "Top-1 Accuracy" },
  { value: "top5_acc", label: "Top-5 Accuracy" },
  { value: "accuracy", label: "Accuracy" },
  { value: "mAP50", label: "mAP50" },
  { value: "mAP50-95", label: "mAP50-95" },
  { value: "precision", label: "Precision" },
  { value: "recall", label: "Recall" },
  { value: "f1_score", label: "F1-Score" }
];
const metricOptions = ref<Array<{ value: string; label: string }>>([]);
const leaderboard = ref<any[]>([]);

/** 收集所有条目中出现过的 overallScores 维度 */
const scoreDimensions = ref<string[]>([]);

// ======================== 视图模式 ========================
/** 当前视图模式：overview=总览(排行榜), compare=对比 */
const viewMode = ref<"overview" | "compare">("overview");

// ======================== 对比模式状态（问题1：支持多选对比）========================
const compareModelA = ref<number | undefined>(undefined);
const compareModelB = ref<number | undefined>(undefined);
/** 模型选项列表（用于下拉选择） */
const modelSelectOptions = computed(() =>
  leaderboard.value.map((row, idx) => ({
    value: row.modelVersionId,
    label: getModelDisplayName(row),
    index: idx,
    rank: idx + 1
  }))
);

/** 获取两个选中模型的行数据 */
const selectedModels = computed(() => {
  const rowA = leaderboard.value.find(r => r.modelVersionId === compareModelA.value);
  const rowB = leaderboard.value.find(r => r.modelVersionId === compareModelB.value);
  return [rowA, rowB].filter(Boolean);
});

/** 切换到对比模式时自动填充默认选择 */
watch(viewMode, (mode) => {
  if (mode === "compare" && leaderboard.value.length >= 2) {
    if (!compareModelA.value && leaderboard.value[0]) {
      compareModelA.value = leaderboard.value[0].modelVersionId;
    }
    if (!compareModelB.value && leaderboard.value[1]) {
      compareModelB.value = leaderboard.value[1].modelVersionId;
    }
    nextTick(() => renderCompareCharts());
  }
});

/** 对比模型切换时刷新图表 */
const onCompareModelChange = () => {
  nextTick(() => renderCompareCharts());
};

// ======================== 指标分类工具函数 ========================
/** 判断是否为速度类指标 */
function isSpeedMetric(dim: string): boolean {
  return dim.startsWith("speed_") || ["latency", "throughput", "fps"].includes(dim);
}

/** ★ 指标分类 */
const accuracyMetrics = computed(() =>
  scoreDimensions.value.filter(d => !isSpeedMetric(d) && d !== "confusion_matrix")
);
const speedMetrics = computed(() => scoreDimensions.value.filter(isSpeedMetric));
const hasConfusionMatrix = computed(() =>
  scoreDimensions.value.includes("confusion_matrix")
);

/** 获取指标的中文名称 */
function getMetricLabel(dim: string): string {
  const labels: Record<string, string> = {
    top1_acc: "Top-1 准确率",
    top5_acc: "Top-5 准确率",
    accuracy: "准确率",
    precision: "精确率",
    recall: "召回率",
    f1_score: "F1 值",
    mAP50: "mAP@0.5",
    "mAP50-95": "mAP@0.5:0.95",
    specificity: "特异度",
    auc_roc: "AUC-ROC",
    speed_inference: "推理耗时 (ms)",
    speed_preprocess: "预处理耗时 (ms)",
    speed_postprocess: "后处理耗时 (ms)"
  };
  return labels[dim] || dim;
}

/** ★ 获取模型显示名称（问题2：确保格式为"模型名+版本号"） */
function getModelDisplayName(row: any): string {
  const name = (row.modelName || "").trim();
  const ver = (row.modelVersion || "").trim();
  if (name && ver) return `${name}-${ver}`;
  if (name) return name;
  if (ver) return `v${ver}`;
  // 最终兜底：用 ID + 排名标识，避免同名混淆
  const rank = (leaderboard.value.findIndex(r => r.modelVersionId === row.modelVersionId) + 1);
  return `模型 #${row.modelVersionId ?? "?"} (排名${rank})`;
}

const collectDimensions = (rows: any[]) => {
  const set = new Set<string>();
  rows.forEach(row => {
    const scores = row?.overallScores ?? {};
    if (scores && typeof scores === "object") {
      Object.keys(scores).forEach(k => set.add(k));
    }
  });
  const arr = Array.from(set);
  arr.sort((a, b) => {
    if (a === sortBy.value) return -1;
    if (b === sortBy.value) return 1;
    return a.localeCompare(b);
  });
  scoreDimensions.value = arr;
};

const getScore = (row: any, dim: string): number => {
  if (row?.overallScores && typeof row.overallScores[dim] === "number") {
    return row.overallScores[dim];
  }
  return typeof row?.[dim] === "number" ? row[dim] : 0;
};

// ======================== 混淆矩阵计算（问题5：每个模型独立计算）========================
interface ClassMetrics {
  className: string;
  precision: number;
  recall: number;
  f1: number;
  support: number;
}
interface ModelDerivedMetrics {
  classMetrics: ClassMetrics[];
  avgMetrics: { precision: number; recall: number; f1: number; accuracy: number };
}

/** 为每个模型独立存储从其混淆矩阵计算的派生指标 */
const modelDerivedMetricsMap = ref<Map<number, ModelDerivedMetrics>>(new Map());

function computeClassMetricsFromConfusionMatrix(matrix: number[][]): ModelDerivedMetrics {
  if (!matrix || !matrix.length) {
    return { classMetrics: [], avgMetrics: { precision: 0, recall: 0, f1: 0, accuracy: 0 } };
  }

  const nClasses = matrix.length;
  const metrics: ClassMetrics[] = [];

  for (let i = 0; i < nClasses; i++) {
    const tp = matrix[i][i] || 0;
    let fp = 0;
    for (let r = 0; r < nClasses; r++) {
      if (r !== i) fp += matrix[r][i] || 0;
    }
    let fn = 0;
    for (let c = 0; c < nClasses; c++) {
      if (c !== i) fn += matrix[i][c] || 0;
    }

    const precision = tp + fp > 0 ? tp / (tp + fp) : 0;
    const recall = tp + fn > 0 ? tp / (tp + fn) : 0;
    const f1 = precision + recall > 0 ? 2 * (precision * recall) / (precision + recall) : 0;

    metrics.push({ className: `类别 ${i}`, precision, recall, f1, support: tp + fn });
  }

  let totalSamples = 0, totalCorrect = 0, sumP = 0, sumR = 0, sumF = 0;
  for (let i = 0; i < nClasses; i++) {
    for (let j = 0; j < nClasses; j++) {
      totalSamples += matrix[i][j] || 0;
      if (i === j) totalCorrect += matrix[i][j] || 0;
    }
    sumP += metrics[i].precision;
    sumR += metrics[i].recall;
    sumF += metrics[i].f1;
  }

  return {
    classMetrics: metrics,
    avgMetrics: {
      precision: sumP / nClasses,
      recall: sumR / nClasses,
      f1: sumF / nClasses,
      accuracy: totalSamples > 0 ? totalCorrect / totalSamples : 0
    }
  };
}

/** 获取某模型的派生指标 */
function getDerivedMetrics(modelVersionId: number): ModelDerivedMetrics {
  return modelDerivedMetricsMap.value.get(modelVersionId) ?? { classMetrics: [], avgMetrics: { precision: 0, recall: 0, f1: 0, accuracy: 0 } };
}

/** 获取第一个有数据的模型的派生指标（用于总览模式） */
const overviewDerivedMetrics = computed(() => {
  if (!leaderboard.value.length) return { classMetrics: [] as ClassMetrics[], avgMetrics: { precision: 0, recall: 0, f1: 0, accuracy: 0 } };
  // 找第一个有混淆矩阵的模型
  for (const row of leaderboard.value) {
    const cm = row.overallScores?.confusion_matrix;
    if (Array.isArray(cm) && cm.length) {
      return getDerivedMetrics(row.modelVersionId);
    }
  }
  return { classMetrics: [], avgMetrics: { precision: 0, recall: 0, f1: 0, accuracy: 0 } };
});

// ====================== 动态表格列 ======================
const columns = computed<TableColumnList>(() => {
  const dimCols: TableColumnList = scoreDimensions.value.map(dim => ({
    label: getMetricLabel(dim),
    prop: dim,
    width: 140,
    align: "center",
    cellRenderer: ({ row }: any) => {
      const val = getScore(row, dim);
      if (dim === "confusion_matrix") {
        return h("span", { style: "color: #409eff; cursor: pointer" }, () => "查看详情");
      }
      const formatted = formatMetricValue(dim, val);
      return dim === sortBy.value
        ? h(ElTag, { type: "success", effect: "plain" }, () => formatted)
        : h("span", null, formatted);
    }
  }));

  // 问题5修复：每行使用自己模型的派生指标
  const extraCols: TableColumnList = [];
  if (hasConfusionMatrix.value) {
    ["precision", "recall", "f1_score"].forEach(m => {
      if (!scoreDimensions.value.includes(m)) {
        extraCols.push({
          label: getMetricLabel(m),
          prop: m,
          width: 120,
          align: "center",
          cellRenderer: ({ row }: any) => {
            const derived = getDerivedMetrics(row.modelVersionId);
            const key = m.replace("f1_score", "f1") as keyof typeof derived.avgMetrics;
            const val = derived.avgMetrics[key];
            // 如果该模型没有混淆矩阵数据，显示 "-" 而非错误的值
            if (derived.classMetrics.length === 0) return "-";
            return typeof val === "number" ? val.toFixed(4) : "-";
          }
        });
      }
    });
  }

  return [
    {
      label: "排名",
      type: "index",
      width: 80,
      align: "center",
      cellRenderer: ({ $index }: any) =>
        $index < 3
          ? h(ElTag, { type: "warning", effect: "dark" }, () => String($index + 1))
          : h("span", null, String($index + 1))
    },
    {
      label: "模型版本",
      minWidth: 200,
      showOverflowTooltip: true,
      cellRenderer: ({ row }: any) => h("span", null, () => getModelDisplayName(row))
    },
    ...dimCols,
    ...extraCols,
    {
      label: "排序分",
      width: 120,
      align: "center",
      cellRenderer: ({ row }: any) => row.sortScore == null ? "-" : String(row.sortScore)
    }
  ];
});

/** 格式化指标值显示 */
function formatMetricValue(dim: string, val: number): string {
  if (isSpeedMetric(dim)) {
    return val >= 1 ? `${val.toFixed(2)} ms` : `${(val * 1000).toFixed(2)} µs`;
  }
  if (val <= 1) return `${(val * 100).toFixed(2)}%`;
  return val.toFixed(4);
}

// ===================== 数据加载 =====================
const loadBenchmarks = async () => {
  try {
    const res: any = await getBenchmarkList({ size: 100 });
    const items = res?.data?.items ?? (Array.isArray(res?.data) ? res.data : []);
    benchmarkOptions.value = items.map((b: any) => ({
      id: b.id,
      label: b.name ?? `评测集 #${b.id}`
    }));
    if (!benchmarkId.value && benchmarkOptions.value.length) {
      benchmarkId.value = benchmarkOptions.value[0].id;
    }
  } catch {
    benchmarkOptions.value = [];
  }
};

const loadMetricOptions = async () => {
  if (!benchmarkId.value) {
    metricOptions.value = [...FALLBACK_METRIC_OPTIONS];
    return;
  }
  try {
    const res: any = await getBenchmarkMetrics(benchmarkId.value);
    if (res?.code === 0 && res.data?.supportedMetrics && Array.isArray(res.data.supportedMetrics)) {
      metricOptions.value = res.data.supportedMetrics;
      if (!sortBy.value && res.data.defaultSortMetric) sortBy.value = res.data.defaultSortMetric;
      else if (!sortBy.value && metricOptions.value.length > 0) sortBy.value = metricOptions.value[0].value;
    } else {
      metricOptions.value = [...FALLBACK_METRIC_OPTIONS];
      if (!sortBy.value && metricOptions.value.length > 0) sortBy.value = metricOptions.value[0].value;
    }
  } catch {
    metricOptions.value = [...FALLBACK_METRIC_OPTIONS];
    if (!sortBy.value && metricOptions.value.length > 0) sortBy.value = metricOptions.value[0].value;
  }
};

const getList = async () => {
  if (!benchmarkId.value) {
    leaderboard.value = [];
    scoreDimensions.value = [];
    return;
  }
  loading.value = true;
  try {
    const res = await getLeaderboard({ benchmarkId: benchmarkId.value, sortBy: sortBy.value });
    if (res?.code === 0) {
      const payload = res?.data;
      let rows: any[] = [];
      if (Array.isArray(payload)) rows = payload;
      else if (Array.isArray(payload?.items)) rows = payload.items;
      else if (Array.isArray(payload?.rows)) rows = payload.rows;

      leaderboard.value = rows;
      collectDimensions(rows);

      // 问题5修复：为每个模型独立计算派生指标
      const newMap = new Map<number, ModelDerivedMetrics>();
      for (const row of rows) {
        const cm = row.overallScores?.confusion_matrix;
        if (Array.isArray(cm) && cm.length) {
          newMap.set(row.modelVersionId, computeClassMetricsFromConfusionMatrix(cm));
        }
      }
      modelDerivedMetricsMap.value = newMap;

      await nextTick();
      if (viewMode.value === "overview") {
        renderOverviewCharts();
      } else {
        renderCompareCharts();
      }
    } else {
      ElMessage.error(res?.message ?? "获取排行榜失败");
      leaderboard.value = [];
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "获取排行榜失败");
  } finally {
    loading.value = false;
  }
};

const onBenchmarkChange = () => {
  sortBy.value = "";
  loadMetricOptions().then(() => getList());
};
const onMetricChange = () => {
  collectDimensions(leaderboard.value);
  getList();
};

// ======================== 总览模式图表 ========================
const radarChartRef = ref<HTMLDivElement | null>(null);
let radarChart: EChartsInstance | null = null;
const barChartRef = ref<HTMLDivElement | null>(null);
let barChart: EChartsInstance | null = null;
const heatmapRef = ref<HTMLDivElement | null>(null);
let heatmapChart: EChartsInstance | null = null;

/** 渲染总览模式的图表 */
const renderOverviewCharts = () => {
  renderRadar(leaderboard.value.slice(0, 5), radarChartRef, radarChart, "Top 5 模型性能对比（雷达图）");
  renderBarChart(leaderboard.value.slice(0, 10), barChartRef, barChart);
  // 热力图：取排名第一的模型（有混淆矩阵的那个）
  let targetRow = leaderboard.value[0];
  if (targetRow && !targetRow.overallScores?.confusion_matrix) {
    targetRow = leaderboard.value.find(r => r.overallScores?.confusion_matrix) ?? targetRow;
  }
  renderHeatmap(targetRow, heatmapRef, heatmapChart);
};

// ======================== 对比模式图表 ========================
const compareRadarRef = ref<HTMLDivElement | null>(null);
let compareRadarChart: EChartsInstance | null = null;
const compareBarRef = ref<HTMLDivElement | null>(null);
let compareBarChart: EChartsInstance | null = null;
const compareHeatmapARef = ref<HTMLDivElement | null>(null);
let compareHeatmapAChart: EChartsInstance | null = null;
const compareHeatmapBRef = ref<HTMLDivElement | null>(null);
let compareHeatmapBChart: EChartsInstance | null = null;

/** 渲染对比模式的图表 */
const renderCompareCharts = () => {
  const models = selectedModels.value;
  renderRadar(models, compareRadarRef, compareRadarChart, "模型性能对比（雷达图）");
  renderBarChart(models, compareBarRef, compareBarChart);

  // 分别渲染两个模型的热力图
  renderHeatmap(models[0], compareHeatmapARef, compareHeatmapAChart, "A");
  renderHeatmap(models[1], compareHeatmapBRef, compareHeatmapBChart, "B");
};

// ====================== 雷达图（问题3：保持雷达图）======================
function renderRadar(
  dataRows: any[],
  containerRef: Ref<HTMLDivElement | null>,
  chartInst: EChartsInstance | null,
  titleText: string
) {
  if (!containerRef.value) return;
  if (!chartInst) chartInst = echarts.init(containerRef.value);
  // 更新外部引用
  if (containerRef === radarChartRef) radarChart = chartInst;
  else if (containerRef === compareRadarRef) compareRadarChart = chartInst;

  // 只使用准确率类指标
  const dims = accuracyMetrics.value.length > 0 ? accuracyMetrics.value.slice(0, 8) : [sortBy.value];

  if (dims.length === 0 || dataRows.length === 0) {
    chartInst.setOption({
      title: { text: titleText, left: "center" },
      graphic: [{ type: "text", left: "center", top: "middle", style: { text: "暂无准确率类指标数据", fill: "#999", fontSize: 14 } }]
    }, true);
    return;
  }

  let dataMax = 0;
  dataRows.forEach(row => dims.forEach(d => (dataMax = Math.max(dataMax, getScore(row, d)))));
  const axisMax = dataMax > 1 ? Math.ceil(dataMax) : 1;

  const indicator = dims.map(d => ({ name: getMetricLabel(d), max: axisMax }));
  const series = dataRows.map(row => ({
    name: getModelDisplayName(row),
    value: dims.map(d => getScore(row, d))
  }));

  chartInst.setOption({
    title: { text: titleText, left: "center", textStyle: { fontSize: 14 } },
    tooltip: { trigger: "item", formatter: (params: any) => {
      const d = params.data;
      let html = `<strong>${d.name}</strong><br/>`;
      dims.forEach((dim, i) => html += `${getMetricLabel(dim)}: ${formatMetricValue(dim, d.value[i])}<br/>`);
      return html;
    }},
    legend: { bottom: 0, type: "scroll" },
    radar: { indicator, radius: "65%", shape: "polygon" },
    series: [{ type: "radar", data: series, emphasis: { focus: "self" } }]
  }, true);
}

// ====================== 速度柱状图 ======================
function renderBarChart(
  dataRows: any[],
  containerRef: Ref<HTMLDivElement | null>,
  chartInst: EChartsInstance | null
) {
  if (!containerRef.value) return;
  if (!chartInst) chartInst = echarts.init(containerRef.value);
  if (containerRef === barChartRef) barChart = chartInst;
  else if (containerRef === compareBarRef) compareBarChart = chartInst;

  const dims = speedMetrics.value;
  if (dims.length === 0 || dataRows.length === 0) {
    chartInst.setOption({
      title: { text: "推理速度对比（柱状图）", left: "center" },
      graphic: [{ type: "text", left: "center", top: "middle", style: { text: "暂无速度指标数据", fill: "#999", fontSize: 14 } }]
    }, true);
    return;
  }

  const series = dims.map(d => ({
    name: getMetricLabel(d),
    type: "bar" as const,
    data: dataRows.map(row => { const v = getScore(row, d); return v < 1 ? v * 1000 : v; })
  }));

  chartInst.setOption({
    title: { text: "推理速度对比（柱状图）", left: "center", textStyle: { fontSize: 14 } },
    tooltip: { trigger: "axis", axisPointer: { type: "shadow" }, formatter: (params: any) => {
      const model = params[0]?.axisValue ?? "";
      let html = `<strong>${model}</strong><br/>`;
      params.forEach((p: any) => html += `${p.seriesName}: ${p.value.toFixed(3)} ms<br/>`);
      return html;
    }},
    legend: { bottom: 0 },
    grid: { left: 100, right: 30, top: 50, bottom: 60, containLabel: false },
    xAxis: { type: "category", data: dataRows.map(row => getModelDisplayName(row)), axisLabel: { rotate: 25, fontSize: 11 } },
    yAxis: { type: "value", name: "时间 (ms)", axisLabel: { formatter: "{value} ms" } },
    series
  }, true);
}

// ====================== 混淆矩阵热力图（问题4：优化配色）======================
function renderHeatmap(
  row: any | undefined,
  containerRef: Ref<HTMLDivElement | null>,
  chartInst: EChartsInstance | null,
  suffix?: string
) {
  if (!containerRef.value) return;
  if (!chartInst) chartInst = echarts.init(containerRef.value);

  // 更新引用
  if (containerRef === heatmapRef) heatmapChart = chartInst;
  else if (containerRef === compareHeatmapARef) compareHeatmapAChart = chartInst;
  else if (containerRef === compareHeatmapBRef) compareHeatmapBChart = chartInst;

  const matrix: number[][] = row?.overallScores?.confusion_matrix;
  if (!matrix || !matrix.length) {
    const modelName = row ? getModelDisplayName(row) : "";
    const label = suffix ? ` - 模型${suffix}` : (modelName ? ` - ${modelName}` : "");
    chartInst.setOption({
      title: { text: `混淆矩阵热力图${label}`, left: "center" },
      graphic: [{ type: "text", left: "center", top: "middle", style: { text: "该模型无混淆矩阵数据", fill: "#999", fontSize: 14 } }]
    }, true);
    return;
  }

  const n = matrix.length;
  const xLabels = Array.from({ length: n }, (_, i) => `预测-${i}`);
  const yLabels = Array.from({ length: n }, (_, i) => `真实-${i}`);

  // 展平为 ECharts 需要的格式
  const data: [number, number, number][] = [];
  for (let i = 0; i < n; i++) {
    for (let j = 0; j < n; j++) {
      data.push([j, n - 1 - i, matrix[i][j]]);
    }
  }

  const maxVal = Math.max(...matrix.flat(), 1); // 至少为1避免除零
  const displayName = getModelDisplayName(row);
  const label = suffix ? ` - 模型${suffix}: ${displayName}` : (displayName ? ` - ${displayName}` : "");

  // 问题4修复：使用高对比度配色方案 — 类似 seaborn 的 Blues 或自定义高对比度渐变
  chartInst.setOption({
    title: { text: `混淆矩阵热力图${label}`, left: "center", textStyle: { fontSize: 13 } },
    tooltip: { position: "top", formatter: (params: any) => {
      const [x, y, val] = params.data;
      return `真实类别: ${yLabels[n - 1 - y]}<br/>预测类别: ${xLabels[x]}<br/>样本数: ${val}`;
    }},
    grid: { left: 80, right: 20, top: 45, bottom: 70, containLabel: false },
    xAxis: { type: "category", data: xLabels, splitArea: { show: true }, axisLabel: { color: "#333" } },
    yAxis: { type: "category", data: yLabels.reverse(), splitArea: { show: true }, axisLabel: { color: "#333" } },
    visualMap: {
      min: 0, max: maxVal, calculable: true,
      orient: "horizontal", left: "center", bottom: 5,
      // ★ 高对比度配色方案：从浅色到深蓝，中间过渡明显
      inRange: {
        color: [
          "#fff5f0", // 极低值 — 近白微橙
          "#fee0d2", // 低值
          "#fcbaad", // 中低
          "#fb8072", // 中等
          "#e64b35", // 中高 — 珊瑚红
          "#c51b7d", // 高 — 品红
          "#7a0177"  // 极高 — 深紫
        ]
      },
      textStyle: { color: "#666" }
    },
    series: [{
      type: "heatmap",
      data: data,
      // 标签文字颜色根据背景自适应
      label: {
        show: true,
        formatter: (p: any) => p.data[2],
        color: (params: any) => {
          const v = params.data[2];
          // 值较大时用白色文字，较小时用深色
          return v > maxVal * 0.5 ? "#fff" : "#333";
        },
        fontWeight: "bold",
        fontSize: 12
      },
      emphasis: { itemStyle: { shadowBlur: 8, shadowColor: "rgba(0,0,0,0.4)" } },
      itemStyle: { borderWidth: 1, borderColor: "#eee", borderRadius: 2 }
    }]
  }, true);
}

// ====================== 分类指标汇总卡片 ======================
const summaryCards = computed(() => {
  if (viewMode.value === "compare") {
    // 对比模式：显示两个模型的汇总卡片
    const cards: Array<{ title: string; value: string; color: string }> = [];
    const [modelA, modelB] = selectedModels.value;
    if (modelA) {
      const derived = getDerivedMetrics(modelA.modelVersionId);
      cards.push({
        title: `模型 A - ${getModelDisplayName(modelA)}`,
        value: derived.avgMetrics.accuracy > 0 ? `${(derived.avgMetrics.accuracy * 100).toFixed(2)}%` : "-",
        color: "#1890ff"
      });
    }
    if (modelB) {
      const derived = getDerivedMetrics(modelB.modelVersionId);
      cards.push({
        title: `模型 B - ${getModelDisplayName(modelB)}`,
        value: derived.avgMetrics.accuracy > 0 ? `${(derived.avgMetrics.accuracy * 100).toFixed(2)}%` : "-",
        color: "#52c41a"
      });
    }
    return cards;
  }

  // 总览模式：显示第一个模型的汇总
  if (leaderboard.value.length === 0) return [];
  const firstRow = leaderboard.value[0];
  const scores = firstRow.overallScores ?? {};
  const derived = overviewDerivedMetrics.value;

  const cards: Array<{ title: string; value: string; color: string }> = [];

  const acc = scores.top1_acc ?? scores.accuracy ?? derived.avgMetrics.accuracy;
  if (acc != null) cards.push({ title: "总体准确率", value: `${(acc * 100).toFixed(2)}%`, color: "#1890ff" });

  const prec = scores.precision ?? derived.avgMetrics.precision;
  if (prec != null) cards.push({ title: "宏平均精确率", value: prec.toFixed(4), color: "#52c41a" });

  const rec = scores.recall ?? derived.avgMetrics.recall;
  if (rec != null) cards.push({ title: "宏平均召回率", value: rec.toFixed(4), color: "#faad14" });

  const f1 = scores.f1_score ?? derived.avgMetrics.f1;
  if (f1 != null) cards.push({ title: "宏平均 F1 值", value: f1.toFixed(4), color: "#eb2f96" });

  return cards;
});

// ====================== 当前显示的分类指标详情表 ======================
const currentClassMetrics = computed(() => {
  if (viewMode.value === "compare") {
    // 对比模式：返回两个模型的详情合并展示
    const [modelA, modelB] = selectedModels.value;
    const derivedA = modelA ? getDerivedMetrics(modelA.modelVersionId) : null;
    const derivedB = modelB ? getDerivedMetrics(modelB.modelVersionId) : null;

    // 如果两者都有，以 A 的类别列表为基础，添加 B 的数据
    if (derivedA && derivedB && derivedA.classMetrics.length) {
      return derivedA.classMetrics.map((cm, idx) => ({
        className: cm.className,
        precision_A: cm.precision,
        recall_A: cm.recall,
        f1_A: cm.f1,
        precision_B: derivedB.classMetrics[idx]?.precision ?? 0,
        recall_B: derivedB.classMetrics[idx]?.recall ?? 0,
        f1_B: derivedB.classMetrics[idx]?.f1 ?? 0,
        support: cm.support
      }));
    }
    return derivedA?.classMetrics ?? derivedB?.classMetrics ?? [];
  }

  // 总览模式
  return overviewDerivedMetrics.value.classMetrics;
});

const resizeCharts = () => {
  radarChart?.resize(); barChart?.resize(); heatmapChart?.resize();
  compareRadarChart?.resize(); compareBarChart?.resize();
  compareHeatmapAChart?.resize(); compareHeatmapBChart?.resize();
};

onMounted(async () => {
  await loadBenchmarks();
  await loadMetricOptions();
  await getList();
  window.addEventListener("resize", resizeCharts);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", resizeCharts);
  radarChart?.dispose(); barChart?.dispose(); heatmapChart?.dispose();
  compareRadarChart?.dispose(); compareBarChart?.dispose();
  compareHeatmapAChart?.dispose(); compareHeatmapBChart?.dispose();
});
</script>

<template>
  <div>
    <!-- 筛选区域 -->
    <div class="w-full flex justify-between mb-4">
      <div class="flex items-center flex-wrap gap-2">
        <span class="text-sm">评测集：</span>
        <el-select v-model="benchmarkId" placeholder="选择评测集" filterable style="width: 220px" @change="onBenchmarkChange">
          <el-option v-for="b in benchmarkOptions" :key="b.id" :label="b.label" :value="b.id" />
        </el-select>
        <span class="ml-4 text-sm">排序指标：</span>
        <el-select v-model="sortBy" style="width: 180px" @change="onMetricChange">
          <el-option v-for="m in metricOptions" :key="m.value" :value="m.value" :label="m.label" />
        </el-select>
        <el-button type="primary" :icon="useRenderIcon(Refresh)" @click="getList">查询</el-button>
      </div>
    </div>

    <!-- ★ 指标汇总卡片 -->
    <el-row :gutter="16" class="mb-4" v-if="summaryCards.length">
      <el-col :xs="12" :sm="6" v-for="(card, idx) in summaryCards" :key="idx">
        <el-card shadow="hover" class="text-center" :body-style="{ padding: '16px' }">
          <div class="text-sm text-gray-500">{{ card.title }}</div>
          <div class="text-xl font-bold mt-1" :style="{ color: card.color }">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ★ Tab 切换：总览 vs 对比（问题1：扩展性） -->
    <el-card shadow="never" class="mb-4">
      <el-tabs v-model="viewMode" @tab-change="(name: any) => viewMode = name">
        <el-tab-pane label="📊 排行榜总览" name="overview">
          <!-- 图表区域 -->
          <el-row :gutter="16" class="mb-4">
            <el-col :xs="24" :lg="12">
              <div ref="radarChartRef" v-loading="loading" style="width: 100%; height: 380px" />
            </el-col>
            <el-col :xs="24" :lg="12">
              <div ref="barChartRef" v-loading="loading" style="width: 100%; height: 380px" />
            </el-col>
          </el-row>

          <!-- 混淆矩阵热力图 -->
          <div ref="heatmapRef" v-loading="loading" style="width: 100%; height: 400px" />

          <!-- 分类指标详情表 -->
          <div v-if="currentClassMetrics.length" class="mt-4">
            <h4 class="font-semibold mb-2">各类别 Precision / Recall / F1 详情</h4>
            <el-table :data="currentClassMetrics" border stripe size="small" style="width: 100%">
              <el-table-column prop="className" label="类别" width="90" align="center" />
              <el-table-column prop="precision" label="Precision" align="center" width="110">
                <template #default="{ row }">{{ row.precision.toFixed(4) }}</template>
              </el-table-column>
              <el-table-column prop="recall" label="Recall" align="center" width="110">
                <template #default="{ row }">{{ row.recall.toFixed(4) }}</template>
              </el-table-column>
              <el-table-column prop="f1" label="F1-Score" align="center" width="110">
                <template #default="{ row }">{{ row.f1.toFixed(4) }}</template>
              </el-table-column>
              <el-table-column prop="support" label="Support" align="center" width="90" />
            </el-table>
            <div class="mt-2 text-right text-sm text-gray-500">
              宏平均 — P: {{ overviewDerivedMetrics.avgMetrics.precision.toFixed(4) }},
              R: {{ overviewDerivedMetrics.avgMetrics.recall.toFixed(4) }},
              F1: {{ overviewDerivedMetrics.avgMetrics.f1.toFixed(4) }},
              Acc: {{ (overviewDerivedMetrics.avgMetrics.accuracy * 100).toFixed(2) }}%
            </div>
          </div>

          <!-- 排行榜主表格 -->
          <div class="mt-4">
            <pure-table row-key="modelVersionId" :loading="loading" :data="leaderboard" :columns="columns">
              <template #empty>
                <el-empty :description="benchmarkId ? '该评测集暂无排行榜数据' : '请先选择评测集'" />
              </template>
            </pure-table>
          </div>
        </el-tab-pane>

        <!-- ★ 对比模式（问题1：多选对比） -->
        <el-tab-pane label="⚖️ 模型对比" name="compare">
          <div class="flex items-center gap-4 mb-4 p-3 bg-gray-50 rounded">
            <span class="font-semibold">选择对比模型：</span>
            <span class="text-sm text-gray-600">模型 A：</span>
            <el-select v-model="compareModelA" placeholder="选择模型 A" filterable style="width: 280px" @change="onCompareModelChange">
              <el-option v-for="opt in modelSelectOptions" :key="opt.value" :label="`#${opt.rank} ${opt.label}`" :value="opt.value" />
            </el-select>
            <span class="text-sm text-gray-600 ml-2">模型 B：</span>
            <el-select v-model="compareModelB" placeholder="选择模型 B" filterable style="width: 280px" @change="onCompareModelChange">
              <el-option v-for="opt in modelSelectOptions" :key="opt.value" :label="`#${opt.rank} ${opt.label}`" :value="opt.value" />
            </el-select>
          </div>

          <!-- 对比图表区域 -->
          <el-row :gutter="16" class="mb-4">
            <el-col :xs="24" :lg="12">
              <div ref="compareRadarRef" v-loading="loading" style="width: 100%; height: 380px" />
            </el-col>
            <el-col :xs="24" :lg="12">
              <div ref="compareBarRef" v-loading="loading" style="width: 100%; height: 380px" />
            </el-col>
          </el-row>

          <!-- 并排热力图对比 -->
          <el-row :gutter="16" class="mb-4">
            <el-col :xs="24" :md="12">
              <div ref="compareHeatmapARef" v-loading="loading" style="width: 100%; height: 380px" />
            </el-col>
            <el-col :xs="24" :md="12">
              <div ref="compareHeatmapBRef" v-loading="loading" style="width: 100%; height: 380px" />
            </el-col>
          </el-row>

          <!-- 对比指标详情表 -->
          <div v-if="selectedModels.length >= 2" class="mt-4">
            <h4 class="font-semibold mb-2">
              各类别 Precision / Recall / F1 对比
              <span class="ml-2 text-xs font-normal text-gray-500">
                ({{ getModelDisplayName(selectedModels[0]) }} vs {{ getModelDisplayName(selectedModels[1]) }})</span>
            </h4>
            <el-table :data="currentClassMetrics" border stripe size="small" style="width: 100%">
              <el-table-column prop="className" label="类别" width="80" align="center" />
              <el-table-column label="模型 A" align="center">
                <el-table-column prop="precision_A" label="Precision" width="105" align="center">
                  <template #default="{ row }">{{ row.precision_A.toFixed(4) }}</template>
                </el-table-column>
                <el-table-column prop="recall_A" label="Recall" width="105" align="center">
                  <template #default="{ row }">{{ row.recall_A.toFixed(4) }}</template>
                </el-table-column>
                <el-table-column prop="f1_A" label="F1" width="100" align="center">
                  <template #default="{ row }">{{ row.f1_A.toFixed(4) }}</template>
                </el-table-column>
              </el-table-column>
              <el-table-column label="模型 B" align="center">
                <el-table-column prop="precision_B" label="Precision" width="105" align="center">
                  <template #default="{ row }">{{ row.precision_B.toFixed(4) }}</template>
                </el-table-column>
                <el-table-column prop="recall_B" label="Recall" width="105" align="center">
                  <template #default="{ row }">{{ row.recall_B.toFixed(4) }}</template>
                </el-table-column>
                <el-table-column prop="f1_B" label="F1" width="100" align="center">
                  <template #default="{ row }">{{ row.f1_B.toFixed(4) }}</template>
                </el-table-column>
              </el-table-column>
              <el-table-column prop="support" label="Support" width="85" align="center" />
            </el-table>
          </div>
          <div v-else class="text-center py-8 text-gray-400">
            请选择两个模型进行对比分析
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>
