<script setup lang="ts">
import { h, ref, reactive, onMounted } from "vue";
import { ElMessage, ElTag, ElButton, ElEmpty } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import {
  getEvaluationTaskList,
  createEvaluationTask,
  startEvaluationTask,
  getBenchmarkList,
  getEvaluationReport,
  rerunEvaluationTask
} from "@/api/evaluation";
import { getModelList, getModelVersions } from "@/api/model";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import dayjs from "dayjs";
import AddFill from "~icons/ri/add-circle-line";
import Refresh from "~icons/ep/refresh";
import VideoPlay from "~icons/ep/video-play";
import Document from "~icons/ri/file-text-line";
import { useRouter } from "vue-router";

defineOptions({
  name: "EvaluationTasks" });

const router = useRouter();
const loading = ref(false);
const dataList = ref<any[]>([]);
const total = ref(0);

const query = reactive({
  page: 1,
  size: 10
});

const pagination = reactive<PaginationProps>({
  total: 0,
  currentPage: 1,
  pageSize: 10,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

/** 状态映射：与后端 EvaluationConstants 对齐（PENDING/RUNNING/COMPLETED/COMPLETED_WITH_ERRORS/FAILED/CANCELED/STOPPED） */
const statusMap: Record<string, { type: string; label: string }> = {
  PENDING: { type: "info", label: "待运行" },
  RUNNING: { type: "warning", label: "运行中" },
  COMPLETED: { type: "success", label: "已完成" },
  COMPLETED_WITH_ERRORS: { type: "warning", label: "部分完成" },
  FAILED: { type: "danger", label: "失败" },
  CANCELED: { type: "info", label: "已取消" },
  CANCELLED: { type: "info", label: "已取消" },
  STOPPED: { type: "info", label: "已停止" },
  QUEUED: { type: "info", label: "排队中" },
  SUCCESS: { type: "success", label: "已完成" }
};
const statusOf = (s?: string) =>
  (s && statusMap[s.toUpperCase()]) || { type: "info", label: s || "-" };

/** 判断任务是否处于终态（可查看报告 / 可重新测试） */
const isTerminalStatus = (s?: string): boolean => {
  if (!s) return false;
  const upper = s.toUpperCase();
  return ["COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELED", "CANCELLED", "STOPPED"].includes(upper);
};

/** 判断任务是否可以查看报告（已完成类状态） */
const canViewReport = (s?: string): boolean => {
  if (!s) return false;
  const upper = s.toUpperCase();
  return ["COMPLETED", "COMPLETED_WITH_ERRORS"].includes(upper);
};

const startLoading = ref<number | null>(null);

const columns: TableColumnList = [
  { label: "序号", type: "index", width: 60, align: "center" },
  {
    label: "任务名称",
    prop: "name",
    minWidth: 160,
    showOverflowTooltip: true
  },
  {
    label: "评测集",
    prop: "benchmarkName",
    minWidth: 140,
    showOverflowTooltip: true,
    cellRenderer: ({ row }) =>
      row.benchmarkName ?? (row.benchmarkId ? `评测集 #${row.benchmarkId}` : "-")
  },
  {
    label: "模型版本",
    minWidth: 160,
    showOverflowTooltip: true,
    cellRenderer: ({ row }) => {
      if (!Array.isArray(row.modelVersionIds) || !row.modelVersionIds.length) return "-";
      const map = versionLabelMap();
      return row.modelVersionIds
        .map((id: number) => map.get(id) || `#${id}`)
        .join(", ");
    }
  },
  {
    label: "状态",
    prop: "status",
    width: 110,
    align: "center",
    cellRenderer: ({ row }) => {
      const s = statusOf(row.status);
      return h(
        ElTag,
        { type: s.type, effect: "plain" },
        () => s.label
      );
    }
  },
  {
    label: "创建时间",
    prop: "createdAt",
    width: 180,
    align: "center",
    formatter: ({ createdAt }: any) =>
      createdAt ? dayjs(createdAt).format("YYYY-MM-DD HH:mm:ss") : "-"
  },
  {
    label: "操作",
    width: 240,
    align: "center",
    fixed: "right",
    cellRenderer: ({ row }) =>
      h("div", { style: "display:flex;gap:4px;justify-content:center;flex-wrap:wrap" }, [
        // 查看报告（已完成/部分完成 才显示）
        canViewReport(row.status)
          ? h(ElButton, {
              link: true,
              type: "primary",
              size: "small",
              icon: useRenderIcon(Document),
              onClick: () => router.push(`/evaluation/report/${row.id}`)
            }, () => "报告")
          : null,
        // 启动 / 重新测试 按钮
        h(
          ElButton,
          {
            link: true,
            type: isTerminalStatus(row.status) ? "warning" : "primary",
            size: "small",
            loading: startLoading.value === row.id,
            disabled:
              row.status?.toUpperCase() === "RUNNING" ||
              row.status?.toUpperCase() === "PENDING" ||
              row.status?.toUpperCase() === "QUEUED",
            icon: useRenderIcon(VideoPlay),
            onClick: () => handleStart(row)
          }, () =>
            isTerminalStatus(row.status) ? "重测" :
            row.status?.toUpperCase() === "RUNNING" ? "运行中" : "启动"
          )
      ])
  }
];

const getList = async () => {
  loading.value = true;
  try {
    const res = await getEvaluationTaskList({
      page: query.page,
      size: query.size
    });
    if (res?.code === 0 && res.data) {
      dataList.value = res.data.items ?? [];
      total.value = res.data.total ?? 0;
      pagination.total = total.value;
      pagination.currentPage = query.page;
      pagination.pageSize = query.size;
    } else {
      ElMessage.error(res?.message ?? "获取评测任务列表失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "获取评测任务列表失败");
  } finally {
    loading.value = false;
  }
};

const onSizeChange = (size: number) => {
  query.size = size;
  query.page = 1;
  getList();
};

const onCurrentChange = (page: number) => {
  query.page = page;
  getList();
};

/* ---------------- 下拉数据：评测集 / 模型版本 ---------------- */
const benchmarkOptions = ref<Array<{ id: number; label: string }>>([]);
const modelVersionOptions = ref<Array<{ id: number; label: string }>>([]);

/** 版本 ID → "模型名-版本号" 显示名映射（用于列表渲染） */
const versionLabelMap = () => {
  const map = new Map<number, string>();
  for (const opt of modelVersionOptions.value) {
    map.set(opt.id, opt.label);
  }
  return map;
};

async function loadBenchmarks() {
  try {
    const res: any = await getBenchmarkList({ size: 100 });
    const items = res?.data?.items ?? (Array.isArray(res?.data) ? res.data : []);
    benchmarkOptions.value = items.map((b: any) => ({
      id: b.id,
      label: b.name ?? `评测集 #${b.id}`
    }));
  } catch (e) {
    benchmarkOptions.value = [];
  }
}

async function loadModelVersions() {
  try {
    const res: any = await getModelList({ size: 100 });
    const models = res?.data?.items ?? (Array.isArray(res?.data) ? res.data : []);
    const flat: Array<{ id: number; label: string }> = [];
    for (const m of models) {
      const vr: any = await getModelVersions(m.id);
      const versions = vr?.data?.items ?? (Array.isArray(vr?.data) ? vr.data : []);
      for (const v of versions) {
        if (v.status === "ready") {
          flat.push({ id: v.id, label: `${m.name}-${v.version}` });
        }
      }
    }
    modelVersionOptions.value = flat;
  } catch (e) {
    modelVersionOptions.value = [];
  }
}

/* ---------------- 新建任务弹窗 ---------------- */
const dialogVisible = ref(false);
const submitLoading = ref(false);
const formRef = ref<FormInstance>();
const form = reactive({
  name: "",
  benchmarkId: undefined as number | undefined,
  modelVersionIds: [] as number[]
});

const rules: FormRules = {
  name: [{ required: true, message: "请输入任务名称", trigger: "blur" }],
  benchmarkId: [{ required: true, message: "请选择评测集", trigger: "change" }],
  modelVersionIds: [
  {
      required: true,
      type: "array",
      min: 1,
      message: "请至少选择一个模型版本",
      trigger: "change"
    }
  ]
};

const resetForm = () => {
  form.name = "";
  form.benchmarkId = undefined;
  form.modelVersionIds = [];
  formRef.value?.clearValidate();
};

const openDialog = async () => {
  resetForm();
  dialogVisible.value = true;
  // 并行加载下拉数据（已加载则刷新）
  await Promise.all([loadBenchmarks(), loadModelVersions()]);
};

const handleSubmit = async (formEl: FormInstance | undefined) => {
  if (!formEl) return;
  await formEl.validate(async valid => {
    if (!valid) return;
    submitLoading.value = true;
    try {
      const res = await createEvaluationTask({
        name: form.name,
        benchmarkId: form.benchmarkId as number,
        modelVersionIds: form.modelVersionIds
      });
      if (res?.code === 0) {
        ElMessage.success("新建评测任务成功");
        dialogVisible.value = false;
        getList();
      } else {
        ElMessage.error(res?.message ?? "新建评测任务失败");
      }
    } catch (e: any) {
      ElMessage.error(e?.message ?? "新建评测任务失败");
    } finally {
      submitLoading.value = false;
    }
  });
};

/* ---------------- 启动 / 重新测试 任务 ---------------- */
const handleStart = (row: any) => {
  if (!row?.id) return;
  startLoading.value = row.id;

  // 终态任务（已完成/失败/取消/停止）→ 调 rerun；其他 → 调 start
  const apiCall = isTerminalStatus(row.status)
    ? rerunEvaluationTask(row.id)
    : startEvaluationTask(row.id);
  const actionName = isTerminalStatus(row.status) ? "重新测试" : "启动";

  apiCall
    .then(res => {
      if (res?.code === 0) {
        ElMessage.success(`评测任务${actionName}成功`);
        getList();
      } else {
        ElMessage.error(res?.message ?? `评测任务${actionName}失败`);
      }
    })
    .catch((e: any) => {
      ElMessage.error(e?.message ?? `评测任务${actionName}失败`);
    })
    .finally(() => {
      startLoading.value = null;
    });
};

onMounted(() => {
  getList();
  // 预加载下拉数据（用于表格列中的名称渲染）
  Promise.all([loadBenchmarks(), loadModelVersions()]);
});
</script>

<template>
  <div>
    <div class="w-full flex justify-between mb-4">
      <div class="flex">
        <el-button
          type="primary"
          :icon="useRenderIcon(AddFill)"
          @click="openDialog"
        >
          新建评测任务
        </el-button>
        <el-button :icon="useRenderIcon(Refresh)" @click="getList">
          刷新
        </el-button>
      </div>
    </div>

    <pure-table
      row-key="id"
      :loading="loading"
      :data="dataList"
      :columns="columns"
      :pagination="pagination"
      @page-size-change="onSizeChange"
      @page-current-change="onCurrentChange"
    >
      <template #empty>
        <el-empty description="暂无评测任务数据" />
      </template>
    </pure-table>

    <!-- 新建评测任务弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      title="新建评测任务"
      :width="600"
      draggable
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="110px"
      >
        <el-form-item label="任务名称" prop="name">
          <el-input
            v-model="form.name"
            style="width: 420px"
            placeholder="请输入任务名称"
            clearable
          />
        </el-form-item>
        <el-form-item label="评测集" prop="benchmarkId">
          <el-select
            v-model="form.benchmarkId"
            placeholder="选择评测集（含测试数据集）"
            filterable
            style="width: 420px"
          >
            <el-option
              v-for="b in benchmarkOptions"
              :key="b.id"
              :label="b.label"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="模型版本" prop="modelVersionIds">
          <el-select
            v-model="form.modelVersionIds"
            multiple
            filterable
            placeholder="选择一个或多个 ready 的模型版本参与评测"
            style="width: 420px"
          >
            <el-option
              v-for="m in modelVersionOptions"
              :key="m.id"
              :label="m.label"
              :value="m.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitLoading"
          @click="handleSubmit(formRef)"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
