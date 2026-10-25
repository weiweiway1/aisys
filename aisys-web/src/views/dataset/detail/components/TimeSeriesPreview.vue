<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from "vue";
import { init, type ECharts, type EChartsOption } from "echarts";
import { getDatasetVersionPreview, getTimeSeriesSample, type TimeSeriesSample, type SignalChannel } from "@/api/dataset";

const props = defineProps<{
  datasetId: string | number;
  versionId: string | number;
  description?: string;
  statistics?: any;
}>();
type SampleRow = { id: number; file: string; path: string; label: string; split: string; sizeBytes: number };
const rows = ref<SampleRow[]>([]);
const total = ref(0);
const query = reactive({ page: 1, size: 12, split: "", label: "", keyword: "" });
const listLoading = ref(false);
const signalLoading = ref(false);
const error = ref("");
const selected = ref<TimeSeriesSample | null>(null);
const pinned = ref<TimeSeriesSample | null>(null);
const selectedId = ref<number | null>(null);
const channelName = ref("");
const mode = ref<"raw" | "standardized">("raw");
const tab = ref("wave");
const dataPage = ref(1);
const sampleRate = ref<number | undefined>();
const chartRef = ref<HTMLDivElement>();
let chart: ECharts | undefined;
let chartResizeObserver: ResizeObserver | undefined;
let listRequest = 0;
let signalRequest = 0;
let disposed = false;
const labelName = (label: string) => ({ normal: "正常", fault: "故障" }[label] ?? (label === "-" ? "未标注" : label));
const splitName = (split: string) => ({ train: "训练集", val: "验证集", test: "测试集" }[split] ?? "未划分");
const format = (n: number | null | undefined, digits = 5) => n == null || !Number.isFinite(n) ? "—" : n.toLocaleString(undefined, { maximumFractionDigits: digits });
const channel = computed(() => selected.value?.channels.find(c => c.name === channelName.value));
const labelOptions = computed(() => Object.entries(props.statistics?.classDistribution ?? {}).map(([value, count]) => ({ value, label: `${labelName(value)} · ${Number(count).toLocaleString()}` })));
const duration = computed(() => selected.value?.axisKind === "index" && sampleRate.value ? selected.value.pointCount / sampleRate.value : null);
const axisTitle = computed(() => selected.value?.axisKind === "time" ? selected.value.axisName : sampleRate.value ? "相对时间 (s)" : "采样点序号");
const signalKpis = computed(() => {
  const c = channel.value;
  if (!c) return [];
  return [
    { label: "采样点数", value: format(selected.value?.pointCount, 0) },
    { label: "均值", value: format(c.mean) }, { label: "标准差", value: format(c.std) },
    { label: "最小值", value: format(c.min) }, { label: "最大值", value: format(c.max) },
    { label: "RMS", value: format(c.rms) }, { label: "缺失 / 无效点", value: format(c.missingCount, 0) }
  ];
});
const tableRows = computed(() => {
  const s = selected.value;
  if (!s) return [];
  const start = (dataPage.value - 1) * 100;
  return Array.from({ length: Math.max(0, Math.min(100, s.pointCount - start)) }, (_, k) => {
    const i = start + k;
    return { index: i, axis: s.axis[i], values: s.channels.map(c => c.values[i]) };
  });
});
const comparisonMissingChannel = computed(() => pinned.value && selected.value && pinned.value.id !== selected.value.id && !pinned.value.channels.some(c => c.name === channelName.value));

async function selectSample(row: SampleRow) {
  const request = ++signalRequest;
  selectedId.value = row.id;
  selected.value = null;
  signalLoading.value = true;
  error.value = "";
  const version = props.versionId;
  try {
    const result = await getTimeSeriesSample(props.datasetId, version, row.id - 1);
    if (request !== signalRequest || version !== props.versionId || disposed) return;
    if (result.code !== 0) throw new Error(result.message || "读取波形失败");
    selected.value = result.data;
    if (!result.data.channels.some(c => c.name === channelName.value)) channelName.value = result.data.channels[0]?.name ?? "";
    dataPage.value = 1;
    await renderWave();
  } catch (e: any) {
    if (request === signalRequest && !disposed) error.value = e?.message || "读取波形失败";
  } finally {
    if (request === signalRequest && !disposed) signalLoading.value = false;
  }
}
async function loadList() {
  const request = ++listRequest;
  signalRequest++;
  selected.value = null;
  selectedId.value = null;
  listLoading.value = true;
  signalLoading.value = false;
  error.value = "";
  const version = props.versionId;
  try {
    const result: any = await getDatasetVersionPreview(props.datasetId, version, { ...query });
    if (request !== listRequest || version !== props.versionId || disposed) return;
    if (result.code !== 0) throw new Error(result.message || "读取样本列表失败");
    rows.value = result.data?.rows ?? [];
    total.value = result.data?.total ?? 0;
    if (rows.value.length) await selectSample(rows.value[0]);
  } catch (e: any) {
    if (request === listRequest && !disposed) { error.value = e?.message || "读取样本列表失败"; rows.value = []; total.value = 0; }
  } finally {
    if (request === listRequest && !disposed) listLoading.value = false;
  }
}
function applyFilters() { query.page = 1; void loadList(); }
function toggleComparison() {
  pinned.value = pinned.value?.id === selected.value?.id ? null : selected.value;
}
async function renderWave() {
  await nextTick();
  const s = selected.value;
  const currentChannel = channel.value;
  if (!s || !currentChannel || tab.value !== "wave" || disposed || !chartRef.value) return;
  const makeSeries = (sample: TimeSeriesSample, c: SignalChannel, color: string, compare: boolean) => ({
    name: `${compare ? "对比 · " : ""}${sample.file}`,
    type: "line" as const,
    showSymbol: false,
    connectNulls: false,
    animation: false,
    lineStyle: { width: 1.5, color },
    itemStyle: { color },
    data: c.values.map((v, i) => [
      sample.axisKind === "index" && sampleRate.value ? (sample.axis[i] - sample.axis[0]) / sampleRate.value : sample.axis[i],
      v == null ? null : mode.value === "standardized" && c.mean != null && c.std != null ? c.std === 0 ? 0 : (v - c.mean) / c.std : v
    ])
  });
  const series = [makeSeries(s, currentChannel, "#3478f6", false)];
  const comparison = pinned.value;
  const comparisonChannel = comparison?.channels.find(c => c.name === channelName.value);
  if (comparison && comparison.id !== s.id && comparisonChannel) series.push(makeSeries(comparison, comparisonChannel, "#f59e0b", true));
  const options: EChartsOption = {
    animation: false,
    color: ["#3478f6", "#f59e0b"],
    tooltip: { trigger: "axis", confine: true, valueFormatter: value => value == null ? "缺失" : String(value) },
    legend: { bottom: 0, type: "scroll", textStyle: { color: "#64748b", fontSize: 11 } },
    grid: { left: 62, right: 28, top: 38, bottom: 92 },
    xAxis: { type: "value", name: axisTitle.value, nameLocation: "middle", nameGap: 30, axisLabel: { color: "#64748b" }, splitLine: { show: false } },
    yAxis: { type: "value", scale: true, name: mode.value === "raw" ? channelName.value : "标准化值", nameTextStyle: { color: "#64748b" }, splitLine: { lineStyle: { color: "#edf1f7" } } },
    dataZoom: [{ type: "inside", start: 0, end: 100 }, { type: "slider", start: 0, end: 100, bottom: 35, height: 20, borderColor: "#e2e8f0" }],
    series
  };
  if (!chart || chart.getDom() !== chartRef.value) {
    chartResizeObserver?.disconnect();
    chart?.dispose();
    chart = init(chartRef.value);
    chartResizeObserver = new ResizeObserver(() => chart?.resize());
    chartResizeObserver.observe(chartRef.value);
  }
  chart.setOption(options, { notMerge: true });
  chart.resize();
}
watch([channelName, sampleRate, mode, tab, pinned], () => void renderWave());
watch(() => [props.datasetId, props.versionId], () => {
  query.page = 1; query.split = ""; query.label = ""; query.keyword = "";
  pinned.value = null;
  sampleRate.value = Number(props.description?.match(/([\d.]+)\s*(?:Hz|赫兹)/i)?.[1]) || undefined;
  void loadList();
}, { immediate: true });
onBeforeUnmount(() => {
  disposed = true; listRequest++; signalRequest++;
  chartResizeObserver?.disconnect(); chart?.dispose();
});
</script>

<template>
  <div class="signal-preview">
    <div class="signal-toolbar">
      <div class="signal-heading"><span class="signal-icon">∿</span><div><strong>时序信号预览</strong><p>选择样本查看波形，滚轮缩放或拖动底部滑块浏览细节</p></div></div>
      <div class="signal-filters">
        <el-select v-model="query.split" placeholder="全部划分" clearable @change="applyFilters" style="width:120px">
          <el-option label="训练集" value="train" /><el-option label="验证集" value="val" /><el-option label="测试集" value="test" />
        </el-select>
        <el-select v-model="query.label" placeholder="全部类别" clearable @change="applyFilters" style="width:145px">
          <el-option v-for="o in labelOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="搜索样本文件" clearable style="width:180px" @keyup.enter="applyFilters" @clear="applyFilters" />
        <el-button @click="applyFilters">搜索</el-button>
      </div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="signal-error"><el-button link type="primary" @click="loadList">重试</el-button></el-alert>
    <div class="signal-layout">
      <aside class="sample-browser" v-loading="listLoading">
        <div class="sample-browser-title"><strong>样本列表</strong><span>{{ total.toLocaleString() }} 个样本</span></div>
        <div class="sample-scroll">
          <button v-for="row in rows" :key="row.id" type="button" class="sample-option" :class="{ active: row.id === selectedId }" @click="selectSample(row)">
            <span class="sample-file" :title="row.path">{{ row.file }}</span>
            <span class="sample-tags"><el-tag size="small" :type="row.label === 'normal' ? 'success' : row.label === 'fault' ? 'danger' : 'info'">{{ labelName(row.label) }}</el-tag><span>{{ splitName(row.split) }}</span><span class="sample-number">#{{ row.id }}</span></span>
          </button>
          <el-empty v-if="!rows.length && !listLoading" description="没有匹配的样本" :image-size="60" />
        </div>
        <div class="sample-pagination"><el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="prev, pager, next" :pager-count="5" size="small" @current-change="loadList" /></div>
      </aside>
      <main class="signal-main" v-loading="signalLoading">
        <template v-if="selected">
          <div class="signal-sample-header"><div><strong>{{ selected.file }}</strong><div class="selected-path">{{ selected.path }}</div></div><el-button size="small" :type="pinned?.id === selected.id ? 'warning' : 'default'" @click="toggleComparison">{{ pinned?.id === selected.id ? '取消对比' : '设为对比样本' }}</el-button></div>
          <div class="signal-controls">
            <label>通道 <el-select v-model="channelName" style="width:140px" size="small"><el-option v-for="c in selected.channels" :key="c.name" :label="c.name" :value="c.name" /></el-select></label>
            <label v-if="selected.axisKind === 'index'">采样率 <el-input-number v-model="sampleRate" :min="0.001" :max="1000000000" :controls="false" size="small" style="width:100px" placeholder="可选" /> Hz</label>
            <el-radio-group v-model="mode" size="small"><el-radio-button value="raw">原始值</el-radio-button><el-radio-button value="standardized">标准化</el-radio-button></el-radio-group>
            <el-button size="small" @click="renderWave">复位视图</el-button>
          </div>
          <div v-if="pinned" class="comparison-note"><span>对比样本：{{ pinned.file }} · {{ labelName(pinned.label) }}</span><el-button link type="warning" size="small" @click="pinned = null">移除</el-button></div>
          <el-alert v-if="comparisonMissingChannel" title="对比样本没有同名通道，请切换通道或对比样本。" type="info" :closable="false" />
          <el-alert v-for="warning in selected.warnings" :key="warning" :title="warning" type="warning" :closable="false" />
          <el-tabs v-model="tab" class="signal-tabs">
            <el-tab-pane label="波形预览" name="wave"><div ref="chartRef" class="wave-chart" /></el-tab-pane>
            <el-tab-pane label="原始采样数据" name="data">
              <el-table :data="tableRows" height="330" size="small" stripe>
                <el-table-column label="序号" prop="index" width="80" />
                <el-table-column :label="selected.axisName" width="130"><template #default="{ row }">{{ format(row.axis) }}</template></el-table-column>
                <el-table-column v-for="(c, i) in selected.channels" :key="c.name" :label="c.name" min-width="140"><template #default="{ row }">{{ format(row.values[i], 8) }}</template></el-table-column>
              </el-table>
              <el-pagination v-model:current-page="dataPage" :page-size="100" :total="selected.pointCount" layout="total, prev, pager, next" size="small" class="raw-pagination" />
            </el-tab-pane>
          </el-tabs>
          <div class="signal-metadata"><span>类别 <b>{{ labelName(selected.label) }}</b></span><span>划分 <b>{{ splitName(selected.split) }}</b></span><span>通道 <b>{{ selected.channels.length }}</b></span><span v-if="duration != null">窗口时长 <b>{{ format(duration, 3) }} s</b></span><span v-if="!sampleRate && selected.axisKind === 'index'">未设置采样率，横轴使用采样点序号</span></div>
          <div class="signal-stats-caption">当前通道 · 原始信号统计</div>
          <div class="signal-kpis"><div v-for="item in signalKpis" :key="item.label"><strong>{{ item.value }}</strong><span>{{ item.label }}</span></div></div>
        </template>
        <el-empty v-else :description="signalLoading ? '正在读取信号…' : '选择左侧样本查看波形'" :image-size="90" />
      </main>
    </div>
  </div>
</template>

<style scoped>
.signal-preview { color:#243247; }
.signal-toolbar { display:flex; justify-content:space-between; align-items:center; gap:16px; flex-wrap:wrap; margin-bottom:16px; }
.signal-heading { display:flex; align-items:center; gap:12px; }
.signal-heading strong { font-size:16px; }
.signal-heading p { margin:5px 0 0; font-size:12px; color:#8190a5; }
.signal-icon { width:40px; height:40px; display:grid; place-items:center; background:#edf4ff; color:#3478f6; border-radius:10px; font-size:30px; }
.signal-filters { display:flex; gap:8px; flex-wrap:wrap; }
.signal-layout { display:grid; grid-template-columns:265px minmax(0,1fr); border:1px solid #e5ebf3; border-radius:10px; overflow:hidden; background:#fff; }
.sample-browser { background:#f8fafc; border-right:1px solid #e5ebf3; min-width:0; }
.sample-browser-title { display:flex; align-items:center; justify-content:space-between; padding:18px 16px; font-size:13px; }
.sample-browser-title span { color:#8a96a8; font-size:11px; }
.sample-scroll { max-height:610px; overflow-y:auto; padding:0 10px 8px; }
.sample-option { width:100%; text-align:left; border:1px solid transparent; border-radius:7px; background:transparent; padding:12px 10px; margin:3px 0; cursor:pointer; transition:background .15s; }
.sample-option:hover { background:#edf3fb; }
.sample-option.active { background:#edf4ff; border-color:#c6dbff; box-shadow:inset 3px 0 #3478f6; }
.sample-file { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:12px; color:#34445c; margin-bottom:8px; }
.sample-option.active .sample-file { color:#2367db; font-weight:600; }
.sample-tags { display:flex; align-items:center; gap:8px; color:#8793a5; font-size:11px; }
.sample-number { margin-left:auto; }
.sample-pagination { display:flex; justify-content:center; padding:14px 4px; border-top:1px solid #e5ebf3; }
.signal-main { padding:20px 22px; min-width:0; min-height:600px; }
.signal-sample-header { display:flex; gap:12px; align-items:flex-start; justify-content:space-between; }
.signal-sample-header strong { font-size:16px; word-break:break-all; }
.selected-path { font-size:11px; color:#8a96a8; margin-top:5px; word-break:break-all; }
.signal-controls { display:flex; align-items:center; flex-wrap:wrap; gap:12px; margin-top:18px; }
.signal-controls label { display:flex; align-items:center; gap:7px; font-size:12px; color:#738198; }
.signal-tabs { margin-top:12px; }
.wave-chart { width:100%; height:350px; }
.signal-metadata { display:flex; flex-wrap:wrap; gap:18px; padding:12px 0 16px; border-bottom:1px solid #edf1f7; font-size:12px; color:#8a96a8; }
.signal-metadata b { color:#4b5b72; margin-left:6px; font-weight:500; }
.signal-stats-caption { margin:16px 0 12px; color:#7d8ba0; font-size:11px; }
.signal-kpis { display:grid; grid-template-columns:repeat(7,minmax(0,1fr)); gap:8px; }
.signal-kpis>div { padding:12px 10px; background:#f7f9fc; border:1px solid #edf1f7; border-radius:7px; }
.signal-kpis strong { font-size:15px; display:block; overflow-wrap:anywhere; color:#2b3b54; }
.signal-kpis span { font-size:10px; display:block; color:#8a96a8; margin-top:6px; }
.comparison-note { display:flex; align-items:center; justify-content:space-between; background:#fffbeb; border-radius:6px; padding:6px 10px; margin-top:12px; font-size:12px; color:#a97516; }
.signal-error { margin-bottom:12px; }
.raw-pagination { margin:12px 0; justify-content:flex-end; }
@media(max-width:1100px) { .signal-kpis { grid-template-columns:repeat(4,minmax(0,1fr)); } .signal-layout { grid-template-columns:230px minmax(0,1fr); } .signal-main { padding:16px; } }
@media(max-width:760px) { .signal-layout { grid-template-columns:1fr; } .sample-browser { border-right:none; border-bottom:1px solid #e5ebf3; } .sample-scroll { max-height:220px; } .signal-main { min-height:450px; } .signal-kpis { grid-template-columns:repeat(3,minmax(0,1fr)); } .wave-chart { height:300px; } }
</style>
