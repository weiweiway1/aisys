<script setup lang="ts">
import { h, onMounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox, ElTag, ElButton } from "element-plus";
import dayjs from "dayjs";
import type { FormInstance, FormRules } from "element-plus";
import {
  getDatasetList,
  createDataset,
  updateDataset,
  deleteDataset,
  type DatasetItem,
  type DatasetPayload,
  type DatasetStatus
} from "@/api/dataset";
import {
  DatasetFormatUtil,
  TASK_TYPE_OPTIONS,
  taskTypeLabel
} from "@/utils/datasetFormats";

defineOptions({ name: "DatasetList" });

const router = useRouter();

const loading = ref(false);
const dataList = ref<DatasetItem[]>([]);
const total = ref(0);

const query = reactive({
  page: 1,
  size: 10,
  keyword: "",
  taskType: "",
  status: "" as "" | DatasetStatus
});

const pagination = reactive({
  total: 0,
  currentPage: 1,
  pageSize: 20,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const statusTagType = (status?: DatasetStatus) => {
  switch (status) {
    case "ready": return "success";
    case "active": return "success";
    case "deprecated": return "warning";
    case "draft": return "info";
    default: return "info";
  }
};

const statusLabel = (status?: DatasetStatus) => {
  switch (status) {
    case "ready": return "就绪";
    case "active": return "活跃";
    case "deprecated": return "已废弃";
    case "draft": return "草稿";
    default: return status ? String(status) : "-";
  }
};

const formatNumber = (val?: number) =>
  typeof val === "number" ? val.toLocaleString() : "-";

const columns: TableColumnList = [
  { label: "名称", prop: "name", minWidth: 180, showOverflowTooltip: true },
  {
    label: "任务类型",
    prop: "taskType",
    width: 110,
    align: "center",
    cellRenderer: ({ row }) => h(ElTag, { type: "info", effect: "plain" }, () => taskTypeLabel(row.taskType))
  },
  {
    label: "格式",
    prop: "format",
    width: 150,
    showOverflowTooltip: true,
    cellRenderer: ({ row }) => DatasetFormatUtil.formatLabel(row.format) || "-"
  },
  {
    label: "状态",
    width: 90,
    align: "center",
    cellRenderer: ({ row }) => h(ElTag, { type: statusTagType(row.status) }, () => statusLabel(row.status))
  },
  {
    label: "标签",
    minWidth: 140,
    showOverflowTooltip: true,
    cellRenderer: ({ row }) => {
      const tags = row.tags;
      if (!tags || !tags.length) return "-";
      return tags.map((t: string, i: number) => h(ElTag, { key: i, size: "small", class: "mr-1" }, () => t));
    }
  },
  { label: "样本数", width: 110, align: "right", cellRenderer: ({ row }) => formatNumber(row.sampleCount) },
  { label: "创建时间", width: 170, cellRenderer: ({ row }) => row.createdAt ? dayjs(row.createdAt).format("YYYY-MM-DD HH:mm:ss") : "-" },
  {
    label: "操作",
    width: 200,
    fixed: "right",
    cellRenderer: ({ row }) =>
      h("div", { class: "flex items-center gap-2" }, [
        h(ElButton, { link: true, type: "primary", size: "small", onClick: () => goDetail(row) }, () => "详情"),
        h(ElButton, { link: true, type: "primary", size: "small", onClick: () => openEdit(row) }, () => "编辑"),
        h(ElButton, { link: true, type: "danger", size: "small", onClick: () => handleDelete(row) }, () => "删除")
      ])
  }
];

const fetchList = async () => {
  loading.value = true;
  try {
    const res: any = await getDatasetList({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      taskType: query.taskType || undefined,
      status: query.status || undefined
    });
    if (res?.code === 0) {
      const d = res.data ?? {};
      dataList.value = (d.items ?? []) as DatasetItem[];
      total.value = d.total ?? 0;
      pagination.total = total.value;
    } else {
      ElMessage.error(res?.message || "获取列表失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "获取列表失败");
  } finally {
    loading.value = false;
  }
};

const handleSearch = () => { query.page = 1; fetchList(); };
const handleReset = () => { query.keyword = ""; query.taskType = ""; query.status = ""; query.page = 1; fetchList(); };
const onSizeChange = (s: number) => { query.size = s; query.page = 1; fetchList(); };
const onCurrentChange = (p: number) => { query.page = p; fetchList(); };
const goDetail = (row: DatasetItem) => router.push(`/dataset/detail/${row.id}`);

const handleDelete = async (row: DatasetItem) => {
  try { await ElMessageBox.confirm(`确定删除「${row.name}」？`, "删除确认", { type: "warning" }); } catch { return; }
  try {
    const res: any = await deleteDataset(row.id);
    if (res?.code === 0) { ElMessage.success("删除成功"); fetchList(); }
    else ElMessage.error(res?.message || "删除失败");
  } catch (e: any) { ElMessage.error(e?.message || "删除失败"); }
};

// 新建/编辑弹窗
const dialogVisible = ref(false);
const dialogTitle = ref("新建数据集");
const submitLoading = ref(false);
const formRef = ref<FormInstance>();
const editingId = ref<number | string | null>(null);

const defaultForm = (): DatasetPayload => ({
  name: "", taskType: "", format: "", tags: [], description: ""
});
const form = reactive<DatasetPayload>(defaultForm());

// 格式选项随任务类型级联（与原项目 DatasetFormat.sourceFormats 对齐）
const formatOptions = ref<{ value: string; label: string }[]>([]);
watch(() => form.taskType, (tt) => {
  formatOptions.value = DatasetFormatUtil.getSourceFormats(tt);
  // 切换任务类型后，若当前格式不在新选项内则重置
  if (form.format && !formatOptions.value.some(o => o.value === form.format)) {
    form.format = "";
  }
}, { immediate: true });

const rules: FormRules = {
  name: [{ required: true, message: "请输入数据集名称", trigger: "blur" }],
  taskType: [{ required: true, message: "请选择任务类型", trigger: "change" }],
  format: [{ required: true, message: "请选择格式", trigger: "change" }]
};

const openCreate = () => {
  editingId.value = null;
  dialogTitle.value = "新建数据集";
  Object.assign(form, defaultForm());
  formatOptions.value = DatasetFormatUtil.getSourceFormats(form.taskType);
  dialogVisible.value = true;
};

const openEdit = (row: DatasetItem) => {
  editingId.value = row.id;
  dialogTitle.value = "编辑数据集";
  Object.assign(form, defaultForm(), {
    name: row.name ?? "",
    taskType: row.taskType || "",
    format: row.format || "",
    tags: row.tags || [],
    description: row.description || ""
  });
  formatOptions.value = DatasetFormatUtil.getSourceFormats(form.taskType);
  dialogVisible.value = true;
};

const handleSubmit = async () => {
  if (!formRef.value) return;
  await formRef.value.validate(async valid => {
    if (!valid) return;
    submitLoading.value = true;
    try {
      const payload: DatasetPayload = {
        name: form.name,
        taskType: form.taskType || undefined,
        format: form.format || undefined,
        tags: form.tags,
        description: form.description
      };
      const res: any = editingId.value
        ? await updateDataset(editingId.value, payload)
        : await createDataset(payload);
      if (res?.code === 0) {
        ElMessage.success(editingId.value ? "更新成功" : "创建成功");
        dialogVisible.value = false;
        fetchList();
      } else {
        ElMessage.error(res?.message || "操作失败");
      }
    } catch (e: any) {
      ElMessage.error(e?.message || "操作失败");
    } finally {
      submitLoading.value = false;
    }
  });
};

onMounted(() => { fetchList(); });
</script>

<template>
  <div class="dataset-list-container">
    <el-form :inline="true" class="search-form" @submit.prevent>
      <el-form-item label="关键词">
        <el-input v-model="query.keyword" placeholder="数据集名称" clearable style="width:200px" @keyup.enter="handleSearch" />
      </el-form-item>
      <el-form-item label="任务类型">
        <el-select v-model="query.taskType" placeholder="全部" clearable style="width:140px">
          <el-option v-for="opt in TASK_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="query.status" placeholder="全部" clearable style="width:120px">
          <el-option label="就绪" value="ready" />
          <el-option label="活跃" value="active" />
          <el-option label="草稿" value="draft" />
          <el-option label="已废弃" value="deprecated" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
        <el-button v-perms="['dataset:create']" type="success" @click="openCreate">新建数据集</el-button>
      </el-form-item>
    </el-form>

    <pure-table row-key="id" border stripe :loading="loading" :data="dataList" :columns="columns"
      :pagination="pagination" @page-size-change="onSizeChange" @page-current-change="onCurrentChange">
      <template #empty><span>暂无数据</span></template>
    </pure-table>

    <!-- 新建/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入数据集名称" />
        </el-form-item>
        <el-form-item label="任务类型" prop="taskType">
          <el-select v-model="form.taskType" placeholder="请选择任务类型" style="width:100%">
            <el-option v-for="opt in TASK_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="格式" prop="format">
          <el-select v-model="form.format" :placeholder="form.taskType ? '请选择格式' : '请先选择任务类型'" style="width:100%" :disabled="!form.taskType">
            <el-option v-for="opt in formatOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签">
          <el-select v-model="form.tags" multiple filterable allow-create default-first-option
            placeholder="输入标签后回车" style="width:100%" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.dataset-list-container { padding: 16px; }
</style>
