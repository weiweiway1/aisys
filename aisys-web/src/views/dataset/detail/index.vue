<script setup lang="ts">
import {
  computed,
  onBeforeUnmount,
  onMounted,
  nextTick,
  reactive,
  ref
} from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { useECharts } from "@pureadmin/utils";
import {
  getDatasetDetail,
  updateDataset,
  getDatasetVersions,
  getDatasetVersionPreview,
  getDatasetVersionStatistics,
  getSampleImageBlob,
  uploadDatasetVersion,
  createDatasetVersion,
  completeDatasetVersion,
  deleteDatasetVersion,
  type DatasetItem,
  type DatasetVersion,
  type DatasetPreviewData
} from "@/api/dataset";
import {
  initiateUploadTask,
  uploadTaskChunk,
  completeUploadTask,
  UPLOAD_CHUNK_SIZE
} from "@/api/storage";
import { getToken, formatToken } from "@/utils/auth";
import { DatasetFormatUtil, taskTypeLabel } from "@/utils/datasetFormats";

defineOptions({ name: "DatasetDetail" });

const route = useRoute();
const router = useRouter();

const datasetId = computed(() => String(route.params.id));

const loading = ref(false);
const detail = ref<DatasetItem | null>(null);

const versions = ref<DatasetVersion[]>([]);
const versionsLoading = ref(false);
const currentVersionId = ref<string | number>("");

const previewLoading = ref(false);
const previewData = ref<DatasetPreviewData | null>(null);
const previewQuery = reactive({ page: 1, size: 10 });
const previewTotal = ref(0);

const statistics = ref<any>(null);

// 预览图片：后端 row.url 是占位路径，前端用鉴权 blob 取真实图片并转 objectURL；用完需 revoke 防泄漏。
const previewImageUrls = ref<Record<number, string>>({});
// 图片数据集标志：从预览响应原始行是否含 url 判定（独立于后续被清空/覆盖的 row.url）。
const isImageDataset = ref(false);

// 统计饼图（划分 / 图像尺寸 / 目标尺寸）
const splitChartRef = ref<HTMLDivElement>();
const imageChartRef = ref<HTMLDivElement>();
const objectChartRef = ref<HTMLDivElement>();
const { setOptions: setSplitChart } = useECharts(splitChartRef);
const { setOptions: setImageChart } = useECharts(imageChartRef);
const { setOptions: setObjectChart } = useECharts(objectChartRef);

const formatNumber = (val?: number) =>
  typeof val === "number" ? val.toLocaleString() : "-";

const formatBytes = (b?: number) => {
  if (b == null || Number.isNaN(b)) return "-";
  if (b === 0) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(u.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
  return `${(b / Math.pow(1024, i)).toFixed(2)} ${u[i]}`;
};

/** 版本状态 → 中文文案 + tag 类型 */
const versionStatusMeta = (s?: string) => {
  switch ((s || "").toUpperCase()) {
    case "READY":
      return { type: "success", text: "就绪" };
    case "CREATING":
      return { type: "warning", text: "创建中" };
    case "CANCELLED":
    case "DELETED":
      return { type: "info", text: "已取消" };
    default:
      return { type: "info", text: s || "-" };
  }
};

/**
 * 下载格式：原始文件（标注其实际存储格式，而非误导性的固定 "JSONL"）
 * + 该任务类型支持的目标转换格式（剔除与原格式相同的项，避免"YOLO 数据集 → YOLO"这种无意义选项）。
 * 注：转换格式（csv/coco/yolo/voc）由后端 FormatConverters 实现，输入需为 jsonl；对原生归档存储的版本
 * 仅"原始文件"能保证正确，故转换项仅在该任务类型支持时列出。
 */
const downloadFormat = ref("raw");
const formatOptions = computed(() => {
  const fmt = detail.value?.format;
  const rawLabel = fmt
    ? `原始文件（${DatasetFormatUtil.formatLabel(fmt)}）`
    : "原始文件";
  const ownFmt = (fmt || "").toLowerCase();
  const targets = DatasetFormatUtil.getTargetFormats(
    detail.value?.taskType
  ).filter(o => o.value.toLowerCase() !== ownFmt);
  return [{ label: rawLabel, value: "raw" }, ...targets];
});

const fetchDetail = async () => {
  loading.value = true;
  try {
    const res: any = await getDatasetDetail(datasetId.value);
    if (res?.code === 0) {
      detail.value = (res.data ?? null) as DatasetItem | null;
    } else {
      ElMessage.error(res?.message || "获取详情失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "获取详情失败");
  } finally {
    loading.value = false;
  }
};

const fetchVersions = async () => {
  versionsLoading.value = true;
  try {
    const res: any = await getDatasetVersions(datasetId.value);
    if (res?.code === 0) {
      const d = res.data;
      versions.value = (Array.isArray(d) ? d : (d?.items ?? d?.list ?? [])) as DatasetVersion[];
      if (versions.value.length > 0 && !currentVersionId.value) {
        currentVersionId.value = versions.value[0].id;
        fetchPreview();
        fetchStatistics();
      }
    } else {
      ElMessage.error(res?.message || "获取版本失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "获取版本失败");
  } finally {
    versionsLoading.value = false;
  }
};

const revokePreviewImages = () => {
  Object.values(previewImageUrls.value).forEach(u => {
    if (u) URL.revokeObjectURL(u);
  });
  previewImageUrls.value = {};
};

const fetchPreview = async () => {
  if (!currentVersionId.value) {
    previewData.value = null;
    isImageDataset.value = false;
    return;
  }
  revokePreviewImages();
  isImageDataset.value = false;
  previewLoading.value = true;
  try {
    const res: any = await getDatasetVersionPreview(
      datasetId.value,
      currentVersionId.value,
      { page: previewQuery.page, size: previewQuery.size }
    );
    if (res?.code === 0) {
      const d = res.data ?? {};
      const rawCols: any[] = d.columns ?? [];
      const cols = rawCols.map((c: any) =>
        typeof c === "string" ? { field: c, label: c } : c
      );
      const rows: any[] = d.rows ?? [];
      // 先据「原始行是否含 url」判定是否图片数据集
      isImageDataset.value = rows.some((r: any) => r && r.url != null);
      // 清空占位 path（避免 <el-image> 用未鉴权 path 加载出 401 闪一下），稍后用 blob 覆盖
      if (isImageDataset.value) {
        rows.forEach((r: any) => {
          if (r) r.url = null;
        });
      }
      previewData.value = {
        columns: cols,
        rows,
        page: d.page,
        size: d.size,
        total: d.total
      } as DatasetPreviewData;
      previewTotal.value = typeof d.total === "number" ? d.total : (d.rows?.length ?? 0);
      // 逐行用鉴权 blob 取真实图片（row.id 为全局 1 基下标 → 端点 0 基 = id-1）
      // 经 previewData.value.rows[pageIdx] 走响应式代理赋值，确保 <el-image> 重渲染
      if (isImageDataset.value) {
        await Promise.all(
          rows.map(async (row, pageIdx) => {
            const globalIdx = Number(row.id) - 1;
            const blobUrl = await getSampleImageBlob(
              datasetId.value,
              currentVersionId.value,
              globalIdx
            );
            if (blobUrl) {
              previewImageUrls.value[globalIdx] = blobUrl;
              if (previewData.value?.rows?.[pageIdx]) {
                previewData.value.rows[pageIdx].url = blobUrl;
              }
            }
          })
        );
      }
    } else {
      ElMessage.error(res?.message || "获取预览失败");
      previewData.value = null;
      isImageDataset.value = false;
    }
  } catch (e: any) {
    previewData.value = null;
    isImageDataset.value = false;
  } finally {
    previewLoading.value = false;
  }
};

const PIE_COLORS = [
  "#5470c6",
  "#91cc75",
  "#fac858",
  "#ee6666",
  "#73c0de",
  "#3ba272",
  "#fc8452",
  "#9a60b4"
];
const pieOption = (dist: any) => {
  const data = Object.entries(dist || {}).map(([name, value]) => ({
    name,
    value: value as number
  }));
  return {
    tooltip: { trigger: "item", formatter: "{b}: {c} ({d}%)" },
    color: PIE_COLORS,
    series: [
      {
        type: "pie",
        radius: ["40%", "70%"],
        center: ["50%", "55%"],
        label: { formatter: "{b}\n{d}%", fontSize: 12 },
        data
      }
    ]
  };
};
const hasEntries = (d: any) => d && typeof d === "object" && Object.keys(d).length > 0;

const renderStatsCharts = () => {
  const s = statistics.value;
  if (!s) return;
  if (hasEntries(s.splitDistribution)) setSplitChart(pieOption(s.splitDistribution));
  if (hasEntries(s.imageSizeDistribution)) setImageChart(pieOption(s.imageSizeDistribution));
  if (hasEntries(s.objectSizeDistribution)) setObjectChart(pieOption(s.objectSizeDistribution));
};

const fetchStatistics = async () => {
  if (!currentVersionId.value) {
    statistics.value = null;
    return;
  }
  try {
    const res: any = await getDatasetVersionStatistics(
      datasetId.value,
      currentVersionId.value
    );
    if (res?.code === 0) {
      statistics.value = res.data;
      await nextTick();
      renderStatsCharts();
    }
  } catch (e) {
    statistics.value = null;
  }
};

onBeforeUnmount(() => {
  revokePreviewImages();
});

const handleVersionChange = () => {
  previewQuery.page = 1;
  fetchPreview();
  fetchStatistics();
};

const handlePreviewPageChange = (page: number) => {
  previewQuery.page = page;
  fetchPreview();
};
const handlePreviewSizeChange = (size: number) => {
  previewQuery.size = size;
  previewQuery.page = 1;
  fetchPreview();
};

const goBack = () => router.push("/dataset/list");

/* ----------------------------- 编辑数据集 ----------------------------- */
const editVisible = ref(false);
const editForm = reactive({ name: "", description: "", license: "" });
const editSaving = ref(false);
const openEdit = () => {
  editForm.name = detail.value?.name ?? "";
  editForm.description = detail.value?.description ?? "";
  editForm.license = (detail.value as any)?.license ?? "";
  editVisible.value = true;
};
const submitEdit = async () => {
  editSaving.value = true;
  try {
    // 仅提交可编辑字段（name/description）；taskType/format/type 不在编辑表单中，不回传以免覆盖
    const res: any = await updateDataset(datasetId.value, {
      name: editForm.name,
      description: editForm.description
    } as any);
    if (res?.code === 0) {
      ElMessage.success("更新成功");
      editVisible.value = false;
      fetchDetail();
    } else {
      ElMessage.error(res?.message ?? "更新失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "更新失败");
  } finally {
    editSaving.value = false;
  }
};

/* ----------------------------- 上传新版本 ----------------------------- */
const uploadVisible = ref(false);
const uploadForm = reactive({ version: "", description: "", file: null as File | null });
const uploading = ref(false);
const openUpload = () => {
  uploadForm.version = "";
  uploadForm.description = "";
  uploadForm.file = null;
  uploadVisible.value = true;
};
const onFileChange = (file: any) => {
  uploadForm.file = file.raw ?? file;
};
const uploadProgress = ref(0);
const uploadStatusText = ref("");

const submitUpload = async () => {
  if (!uploadForm.version.trim()) {
    ElMessage.warning("请输入版本号");
    return;
  }
  if (!uploadForm.file) {
    ElMessage.warning("请选择文件");
    return;
  }
  const file = uploadForm.file as File;
  const version = uploadForm.version.trim();
  uploading.value = true;
  uploadProgress.value = 0;
  uploadStatusText.value = "准备上传...";

  try {
    if (file.size > UPLOAD_CHUNK_SIZE) {
      // 大文件：分片上传（浏览器→存储后端→存储池）
      // 1. 创建版本（status=creating，返回 storagePath）
      const init: any = await createDatasetVersion(datasetId.value, { version, description: uploadForm.description });
      if (!init || init.code !== 0) throw new Error(init?.message || "创建版本失败");
      const versionId = init.data.id;
      const storagePath = init.data.storagePath;

      // 2. 发起分片上传任务（经存储后端）
      uploadStatusText.value = "发起分片上传任务...";
      const task: any = await initiateUploadTask({
        poolId: 1, path: storagePath, fileName: file.name, size: file.size, chunkSize: UPLOAD_CHUNK_SIZE
      });
      if (!task || task.code !== 0) throw new Error(task?.message || "发起上传任务失败");
      const taskId = task.data.taskId;
      const totalChunks = task.data.totalChunks;

      // 3. 并发上传分片
      const loaded = new Array(totalChunks).fill(0);
      const updateProgress = () => {
        const sum = loaded.reduce((a, b) => a + b, 0);
        uploadProgress.value = Math.min(100, Math.round((sum / file.size) * 100));
      };
      let nextIdx = 0;
      let completed = 0;
      uploadStatusText.value = `上传中 0/${totalChunks}`;
      const worker = async () => {
        while (true) {
          const idx = nextIdx++;
          if (idx >= totalChunks) break;
          const start = idx * UPLOAD_CHUNK_SIZE;
          const end = Math.min(start + UPLOAD_CHUNK_SIZE, file.size);
          const blob = file.slice(start, end);
          const res: any = await uploadTaskChunk(taskId, idx + 1, blob, l => {
            loaded[idx] = l;
            updateProgress();
          });
          if (!res || res.code !== 0) throw new Error(res?.message || `第 ${idx + 1} 片上传失败`);
          loaded[idx] = end - start;
          updateProgress();
          completed++;
          uploadStatusText.value = `上传中 ${completed}/${totalChunks}`;
        }
      };
      await Promise.all(Array.from({ length: Math.min(3, totalChunks) }, () => worker()));

      // 4. 完成分片上传（后端组装入池）
      uploadStatusText.value = "后端写入存储池中...";
      const done: any = await completeUploadTask(taskId);
      if (!done || done.code !== 0) throw new Error(done?.message || "组装失败");

      // 5. 标记版本就绪（回写行数/列信息）
      uploadStatusText.value = "处理版本统计...";
      const complete: any = await completeDatasetVersion(datasetId.value, versionId);
      if (!complete || complete.code !== 0) throw new Error(complete?.message || "版本完成失败");

      uploadProgress.value = 100;
      uploadStatusText.value = "已完成";
    } else {
      // 小文件：单次 multipart
      uploadStatusText.value = "上传中...";
      const form = new FormData();
      form.append("version", version);
      form.append("description", uploadForm.description);
      form.append("file", file);
      const res: any = await uploadDatasetVersion(datasetId.value, form);
      if (res?.code !== 0) throw new Error(res?.message || "上传失败");
      uploadProgress.value = 100;
      uploadStatusText.value = "已完成";
    }
    ElMessage.success("上传成功");
    uploadVisible.value = false;
    fetchVersions();
  } catch (e: any) {
    ElMessage.error(e?.message || "上传失败");
  } finally {
    uploading.value = false;
  }
};

/* ----------------------------- 下载 / 删除版本 ----------------------------- */
const downloadingId = ref<any>(null);
const deleteVersion = async (row: DatasetVersion) => {
  try {
    await ElMessageBox.confirm(`确认删除版本「${row.version}」吗？`, "提示", {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    const res: any = await deleteDatasetVersion(datasetId.value, row.id);
    if (res?.code === 0) {
      ElMessage.success("已删除");
      if (currentVersionId.value === row.id) currentVersionId.value = "";
      fetchVersions();
    } else {
      ElMessage.error(res?.message ?? "删除失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "删除失败");
  }
};

const downloadVersion = async (row: DatasetVersion) => {
  downloadingId.value = row.id;
  try {
    const t = getToken();
    const headers: Record<string, string> = t?.accessToken
      ? { Authorization: formatToken(t.accessToken) }
      : {};
    const fmt = downloadFormat.value && downloadFormat.value !== "raw"
      ? `?targetFormat=${encodeURIComponent(downloadFormat.value)}` : "";
    const resp = await fetch(
      `/api/v1/datasets/${datasetId.value}/versions/${row.id}/download${fmt}`,
      { headers }
    );
    if (!resp.ok) throw new Error("下载失败: " + resp.status);
    const blob = await resp.blob();
    const disposition = resp.headers.get("Content-Disposition") || "";
    const m = disposition.match(/filename="?([^"]+)"?/);
    const filename = m ? m[1] : `dataset-${datasetId.value}-v${row.version}`;
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  } catch (e: any) {
    ElMessage.error(e?.message ?? "下载失败");
  } finally {
    downloadingId.value = null;
  }
};

const columnList = computed(() => {
  const ci = statistics.value?.columnInfo;
  if (Array.isArray(ci)) {
    return ci.map((c: any) => (typeof c === "string" ? c : c?.name ?? JSON.stringify(c)));
  }
  return [];
});

/** 关键指标 KPI 卡片列表（只含有值的维度，按重要性排序） */
const kpiList = computed<{ label: string; value: string; unit?: string }[]>(() => {
  const s = statistics.value;
  if (!s) return [];
  const items: { label: string; value: string; unit?: string }[] = [];
  if (s.sampleCount != null) items.push({ label: "样本数", value: formatNumber(s.sampleCount) });
  if (s.totalSize != null) items.push({ label: "总大小", value: formatBytes(s.totalSize) });
  if (s.classesCount != null) items.push({ label: "类别数", value: formatNumber(s.classesCount) });
  if (s.totalAnnotations != null) items.push({ label: "标注总数", value: formatNumber(s.totalAnnotations) });
  if (s.avgAnnotationsPerSample != null)
    items.push({ label: "每样本平均标注", value: Number(s.avgAnnotationsPerSample).toFixed(2) });
  if (s.fileCount != null) items.push({ label: "文件数", value: formatNumber(s.fileCount) });
  return items;
});

const classDistList = computed(() => {
  const dist = statistics.value?.classDistribution;
  if (!dist || typeof dist !== "object") return [];
  const entries = Object.entries(dist).map(([label, count]) => ({
    label,
    count: count as number
  }));
  const total = entries.reduce((s, e) => s + e.count, 0);
  return entries
    .map(e => ({ ...e, percent: total > 0 ? Math.round((e.count / total) * 100) : 0 }))
    .sort((a, b) => b.count - a.count);
});

/** 类别条颜色：按占比分段着色，增强分布可读性 */
const classColor = (percent: number) => {
  if (percent >= 30) return "#5470c6";
  if (percent >= 15) return "#5ab1ef";
  if (percent >= 5) return "#fac858";
  return "#91cc75";
};

onMounted(() => {
  fetchDetail();
  fetchVersions();
});
</script>

<template>
  <div class="dataset-detail-container" v-loading="loading">
    <div class="header">
      <el-button @click="goBack">返回</el-button>
      <span class="title">{{ detail?.name ?? "数据集详情" }}</span>
      <el-button link type="primary" @click="openEdit">编辑</el-button>
    </div>

    <el-descriptions :column="3" border class="info-block">
      <el-descriptions-item label="名称">{{ detail?.name ?? "-" }}</el-descriptions-item>
      <el-descriptions-item label="任务类型">{{ taskTypeLabel(detail?.taskType) }}</el-descriptions-item>
      <el-descriptions-item label="格式">{{ DatasetFormatUtil.formatLabel(detail?.format) }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ detail?.status ?? "-" }}</el-descriptions-item>
      <el-descriptions-item label="样本数">{{ formatNumber(detail?.sampleCount) }}</el-descriptions-item>
      <el-descriptions-item label="创建时间">{{ detail?.createdAt ?? "-" }}</el-descriptions-item>
      <el-descriptions-item label="描述" :span="3">{{ detail?.description ?? "-" }}</el-descriptions-item>
    </el-descriptions>

    <!-- 版本列表 -->
    <div class="section">
      <div class="section-title" style="justify-content: space-between">
        <span>版本列表</span>
        <div class="flex items-center gap-2">
          <span class="text-xs text-gray-500">下载格式</span>
          <el-select v-model="downloadFormat" size="small" style="width: 140px">
            <el-option v-for="o in formatOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-button v-perms="['dataset:create']" type="primary" size="small" @click="openUpload">
            上传新版本
          </el-button>
        </div>
      </div>
      <el-table
        v-loading="versionsLoading"
        :data="versions"
        border
        stripe
        style="width: 100%"
        @row-click="(row: any) => { currentVersionId = row.id; handleVersionChange(); }"
      >
        <el-table-column label="版本号" prop="version" min-width="120" />
        <el-table-column label="状态" prop="status" width="110">
          <template #default="{ row }">
            <el-tag :type="versionStatusMeta(row.status).type" size="small">
              {{ versionStatusMeta(row.status).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="行数" width="120" align="right">
          <template #default="{ row }">{{ formatNumber(row.rowCount) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" prop="createdAt" width="180" />
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small"
              @click.stop="currentVersionId = row.id; handleVersionChange()">预览</el-button>
            <el-button
              link type="primary" size="small" :loading="downloadingId === row.id"
              :disabled="row.status !== 'ready'"
              @click.stop="downloadVersion(row)">下载</el-button>
            <el-button v-perms="['dataset:delete']" link type="danger" size="small"
              @click.stop="deleteVersion(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><span>暂无版本（点击「上传新版本」）</span></template>
      </el-table>
    </div>

    <!-- 预览（图片网格 / 表格） -->
    <div class="section">
      <div class="section-title">
        数据预览
        <span v-if="currentVersionId" class="version-tag">版本：{{ currentVersionId }}</span>
      </div>

      <!-- 图片网格（检测/分类） -->
      <div v-if="isImageDataset" v-loading="previewLoading" class="image-grid">
        <div v-for="row in previewData?.rows ?? []" :key="row.id" class="image-card">
          <el-image
            v-if="row.url"
            :src="row.url"
            fit="cover"
            :preview-src-list="[row.url]"
            preview-teleported
            style="width: 100%; height: 150px"
          />
          <div v-else class="image-placeholder">无图片</div>
          <div class="image-meta">
            <div class="image-file" :title="row.file">{{ row.file }}</div>
            <div class="image-sub">
              {{ row.width }}×{{ row.height }}
              <span v-if="row.annotations !== undefined">· {{ row.annotations }} 标注</span>
              <span v-if="row.split">· {{ row.split }}</span>
            </div>
          </div>
        </div>
        <el-empty v-if="!previewLoading && !(previewData?.rows?.length)" description="暂无预览数据" />
      </div>

      <!-- 表格（时序/表格类） -->
      <el-table v-else v-loading="previewLoading" :data="previewData?.rows ?? []" border stripe
        style="width: 100%" max-height="420">
        <el-table-column v-for="col in previewData?.columns ?? []" :key="col.field"
          :prop="col.field" :label="col.label || col.field" min-width="140" show-overflow-tooltip />
        <template #empty><span>暂无预览数据</span></template>
      </el-table>
      <div class="pagination-wrapper" v-if="previewTotal > 0">
        <el-pagination v-model:current-page="previewQuery.page" v-model:page-size="previewQuery.size"
          :total="previewTotal" :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper" background
          @current-change="handlePreviewPageChange" @size-change="handlePreviewSizeChange" />
      </div>
    </div>

    <!-- 统计信息（多维，KPI 卡片 + 图表 + 分布） -->
    <div class="section stats-section">
      <div class="section-title">
        统计信息
        <span v-if="currentVersionId" class="version-tag">版本：{{ currentVersionId }}</span>
      </div>

      <el-empty v-if="!statistics" description="暂无统计信息" :image-size="80" />

      <template v-else>
        <!-- KPI 卡片 -->
        <div v-if="kpiList.length" class="kpi-grid">
          <div v-for="(kpi, i) in kpiList" :key="i" class="kpi-card">
            <div class="kpi-value">{{ kpi.value }}</div>
            <div class="kpi-label">{{ kpi.label }}</div>
          </div>
        </div>

        <!-- 饼图：划分 / 图像尺寸 / 目标尺寸 -->
        <div v-if="hasEntries(statistics?.splitDistribution) || hasEntries(statistics?.imageSizeDistribution) || hasEntries(statistics?.objectSizeDistribution)" class="charts-row">
          <div v-if="hasEntries(statistics?.splitDistribution)" class="chart-block">
            <p class="chart-title">数据集划分</p>
            <div ref="splitChartRef" class="chart-canvas" />
          </div>
          <div v-if="hasEntries(statistics?.imageSizeDistribution)" class="chart-block">
            <p class="chart-title">图像尺寸分布</p>
            <div ref="imageChartRef" class="chart-canvas" />
          </div>
          <div v-if="hasEntries(statistics?.objectSizeDistribution)" class="chart-block">
            <p class="chart-title">目标大小分布</p>
            <div ref="objectChartRef" class="chart-canvas" />
          </div>
        </div>

        <!-- 类别分布 + 其他信息（双栏卡片） -->
        <div class="stats-detail-row">
          <div v-if="classDistList.length" class="stats-card">
            <p class="card-title">类别分布（Top {{ classDistList.length }}）</p>
            <div class="class-list">
              <div v-for="item in classDistList" :key="item.label" class="class-item">
                <div class="class-head">
                  <span class="class-name" :title="item.label">{{ item.label }}</span>
                  <span class="class-count">{{ item.count }} · {{ item.percent }}%</span>
                </div>
                <el-progress
                  :percentage="item.percent"
                  :stroke-width="8"
                  :show-text="false"
                  :color="classColor(item.percent)"
                />
              </div>
            </div>
          </div>

          <div class="stats-card">
            <p class="card-title">其他信息</p>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="行数">{{ formatNumber(statistics?.rowCount) }}</el-descriptions-item>
              <el-descriptions-item label="文件大小">{{ formatBytes(statistics?.fileSize) }}</el-descriptions-item>
              <el-descriptions-item label="列数">{{ columnList.length }}</el-descriptions-item>
              <el-descriptions-item label="校验值">
                <span v-if="statistics?.checksum" class="checksum">{{ statistics.checksum }}</span>
                <span v-else>-</span>
              </el-descriptions-item>
            </el-descriptions>
            <div v-if="columnList.length" class="column-tags">
              <span class="column-tags-label">列字段：</span>
              <el-tag v-for="(c, i) in columnList" :key="i" size="small" class="mr-1 mb-1">{{ c }}</el-tag>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- 编辑数据集 -->
    <el-dialog v-model="editVisible" title="编辑数据集" width="520px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="submitEdit">确认</el-button>
      </template>
    </el-dialog>

    <!-- 上传新版本 -->
    <el-dialog v-model="uploadVisible" title="上传新版本" width="520px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="版本号">
          <el-input v-model="uploadForm.version" placeholder="例如 v1.0" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="uploadForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="文件">
          <el-upload :auto-upload="false" :limit="1" :on-change="onFileChange" :show-file-list="true">
            <el-button type="primary">选择文件（JSONL）</el-button>
          </el-upload>
        </el-form-item>
      </el-form>
      <!-- 上传进度 -->
      <div v-if="uploading || uploadProgress > 0" class="mb-4">
        <el-progress
          :percentage="uploadProgress"
          :status="uploadProgress >= 100 ? 'success' : undefined"
          :stroke-width="14"
        />
        <p class="text-xs text-gray-500 mt-1">{{ uploadStatusText }}（{{ uploadProgress }}%）</p>
        <p class="text-xs text-gray-400">大文件（>50MB）自动启用分片上传</p>
      </div>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dataset-detail-container { padding: 16px; }
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.header .title { font-size: 18px; font-weight: 600; }
.info-block { margin-bottom: 20px; }
.section { margin-top: 20px; }
.section-title { font-size: 15px; font-weight: 600; margin-bottom: 10px; display: flex; align-items: center; gap: 8px; }
.version-tag { font-size: 12px; font-weight: 400; color: var(--el-text-color-secondary); }
.pagination-wrapper { display: flex; justify-content: flex-end; margin-top: 12px; }
.mr-1 { margin-right: 4px; }
.mb-1 { margin-bottom: 4px; }

/* 图片预览网格 */
.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
  min-height: 60px;
}
.image-card {
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 6px;
  overflow: hidden;
  background: var(--el-bg-color, #fff);
}
.image-placeholder {
  width: 100%;
  height: 150px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-fill-color-light, #f5f7fa);
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
}
.image-meta { padding: 6px 8px; }
.image-file {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.image-sub { font-size: 11px; color: var(--el-text-color-secondary, #909399); margin-top: 2px; }

/* 统计图表 */
.charts-row {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  margin-top: 16px;
}
.chart-block {
  flex: 1 1 280px;
  min-width: 240px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  padding: 14px;
  background: var(--el-bg-color, #fff);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}
.chart-canvas { height: 280px; }
.chart-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; text-align: center; }

/* KPI 卡片 */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}
.kpi-card {
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  padding: 14px 16px;
  background: linear-gradient(135deg, var(--el-bg-color, #fff) 0%, var(--el-fill-color-light, #f7f9fc) 100%);
  position: relative;
  overflow: hidden;
}
.kpi-card::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: #409eff;
}
.kpi-value {
  font-size: 22px;
  font-weight: 700;
  color: var(--el-text-color-primary, #303133);
  line-height: 1.2;
  word-break: break-all;
}
.kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  margin-top: 4px;
}

/* 统计：双栏卡片 */
.stats-detail-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 16px;
}
.stats-card {
  flex: 1 1 320px;
  min-width: 280px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 8px;
  padding: 14px;
  background: var(--el-bg-color, #fff);
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 12px 0;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
}
.class-list { display: flex; flex-direction: column; gap: 10px; max-height: 320px; overflow-y: auto; }
.class-item { font-size: 12px; }
.class-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}
.class-name {
  max-width: 70%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.class-count { color: var(--el-text-color-secondary, #909399); }
.column-tags { margin-top: 10px; font-size: 12px; }
.column-tags-label { color: var(--el-text-color-secondary, #909399); }
.checksum {
  font-family: "Consolas", monospace;
  font-size: 11px;
  word-break: break-all;
  color: var(--el-text-color-secondary, #909399);
}
</style>
