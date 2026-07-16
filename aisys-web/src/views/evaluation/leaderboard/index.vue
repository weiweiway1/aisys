<script setup lang="ts">
import { h, ref, computed, onMounted, onBeforeUnmount, nextTick } from "vue";
import { ElMessage, ElTag, ElEmpty } from "element-plus";
import echarts from "@/plugins/echarts";
import { getLeaderboard, getBenchmarkList } from "@/api/evaluation";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";

/** echarts.init 返回的实例类型 */
type EChartsInstance = ReturnType<typeof echarts.init>;

defineOptions({
  name: "EvaluationLeaderboard"
});

const loading = ref(false);
const benchmarkId = ref<number | undefined>(undefined);
const benchmarkOptions = ref<Array<{ id: number; label: string }>>([]);
/** 排序/雷达指标：目标检测平台实际产出的分数维度（默认 mAP50） */
const sortBy = ref("mAP50");
const metricOptions = [
  { value: "mAP50", label: "mAP50" },
  { value: "mAP5095", label: "mAP50-95" },
  { value: "precision", label: "precision" },
  { value: "recall", label: "recall" }
];
const leaderboard = ref<any[]>([]);

/** 收集所有条目中出现过的 overallScores 维度，用于表格动态列与雷达图指示器 */
const scoreDimensions = ref<string[]>([]);

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

/** 动态表格列：排名 + 模型 + 各维度分数 + 总分 */
const columns = computed<TableColumnList>(() => {
  const dimCols: TableColumnList = scoreDimensions.value.map(dim => ({
    label: dim,
    prop: dim,
    width: 120,
    align: "center",
    cellRenderer: ({ row }) =>
      dim === sortBy.value
        ? h(ElTag, { type: "success", effect: "plain" }, () =>
            String(getScore(row, dim))
          )
        : h("span", null, String(getScore(row, dim)))
  }));

  return [
    {
      label: "排名",
      type: "index",
      width: 80,
      align: "center",
      cellRenderer: ({ $index }) =>
        $index < 3
          ? h(
              ElTag,
              { type: "warning", effect: "dark" },
              () => String($index + 1)
            )
          : h("span", null, String($index + 1))
    },
    {
      label: "模型版本",
      minWidth: 160,
      showOverflowTooltip: true,
      cellRenderer: ({ row }) =>
        row.modelName ?? row.modelVersion ?? `模型版本 #${row.modelVersionId ?? "?"}`
    },
    ...dimCols,
    {
      label: "排序分",
      width: 120,
      align: "center",
      cellRenderer: ({ row }) => {
        const s = row.sortScore;
        return s == null ? "-" : String(s);
      }
    }
  ];
});

const loadBenchmarks = async () => {
  try {
    const res: any = await getBenchmarkList({ size: 100 });
    const items = res?.data?.items ?? (Array.isArray(res?.data) ? res.data : []);
    benchmarkOptions.value = items.map((b: any) => ({
      id: b.id,
      label: b.name ?? `评测集 #${b.id}`
    }));
    // 默认选第一个评测集，保证有 benchmarkId（避免后端空参）
    if (!benchmarkId.value && benchmarkOptions.value.length) {
      benchmarkId.value = benchmarkOptions.value[0].id;
    }
  } catch (e) {
    benchmarkOptions.value = [];
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
    const res = await getLeaderboard({
      benchmarkId: benchmarkId.value,
      sortBy: sortBy.value
    });
    if (res?.code === 0) {
      const payload = res?.data;
      let rows: any[] = [];
      if (Array.isArray(payload)) {
        rows = payload;
      } else if (Array.isArray(payload?.items)) {
        rows = payload.items;
      } else if (Array.isArray(payload?.rows)) {
        rows = payload.rows;
      }
      leaderboard.value = rows;
      collectDimensions(rows);
      await nextTick();
      renderRadar();
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
  getList();
};
const onMetricChange = () => {
  collectDimensions(leaderboard.value);
  getList();
};

/* ---------------- 雷达图 ---------------- */
const radarChartRef = ref<HTMLDivElement | null>(null);
let chart: EChartsInstance | null = null;

const renderRadar = () => {
  if (!radarChartRef.value) return;
  if (!chart) {
    chart = echarts.init(radarChartRef.value);
  }

  const top = leaderboard.value.slice(0, 5);
  const dims = scoreDimensions.value.length ? scoreDimensions.value : [sortBy.value];

  // 自适应 max：mAP/precision 类指标为 0~1，accuracy 类为 0~100
  let dataMax = 0;
  top.forEach(row => dims.forEach(d => (dataMax = Math.max(dataMax, getScore(row, d)))));
  const axisMax = dataMax > 1 ? Math.ceil(dataMax) : 1;

  const indicator = dims.map(d => ({ name: d, max: axisMax }));
  const series = top.map(row => ({
    name:
      row.modelName ?? row.modelVersion ?? `模型版本 #${row.modelVersionId ?? "?"}`,
    value: dims.map(d => getScore(row, d))
  }));

  const option = {
    title: { text: "Top 模型对比", left: "center", textStyle: { fontSize: 14 } },
    tooltip: { trigger: "item" },
    legend: { bottom: 0, type: "scroll" },
    radar: {
      indicator: indicator.length ? indicator : [{ name: "暂无数据", max: axisMax }],
      radius: "65%"
    },
    series: [{ type: "radar", data: series, emphasis: { focus: "self" } }]
  };
  chart.setOption(option, true);
};

const resizeChart = () => chart?.resize();

onMounted(async () => {
  await loadBenchmarks();
  await getList();
  window.addEventListener("resize", resizeChart);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", resizeChart);
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <div>
    <div class="w-full flex justify-between mb-4">
      <div class="flex items-center flex-wrap gap-2">
        <span class="text-sm">评测集：</span>
        <el-select
          v-model="benchmarkId"
          placeholder="选择评测集"
          filterable
          style="width: 220px"
          @change="onBenchmarkChange"
        >
          <el-option
            v-for="b in benchmarkOptions"
            :key="b.id"
            :label="b.label"
            :value="b.id"
          />
        </el-select>
        <span class="ml-4 text-sm">排序指标：</span>
        <el-select v-model="sortBy" style="width: 160px" @change="onMetricChange">
          <el-option
            v-for="m in metricOptions"
            :key="m.value"
            :value="m.value"
            :label="m.label"
          />
        </el-select>
        <el-button
          type="primary"
          :icon="useRenderIcon(Refresh)"
          @click="getList"
        >
          查询
        </el-button>
      </div>
    </div>

    <!-- 雷达图 -->
    <el-card shadow="never" class="mb-4">
      <div
        ref="radarChartRef"
        v-loading="loading"
        style="width: 100%; height: 360px"
      />
    </el-card>

    <!-- 排行榜表格 -->
    <pure-table
      row-key="modelVersionId"
      :loading="loading"
      :data="leaderboard"
      :columns="columns"
    >
      <template #empty>
        <el-empty :description="benchmarkId ? '该评测集暂无排行榜数据' : '请先选择评测集'" />
      </template>
    </pure-table>
  </div>
</template>
