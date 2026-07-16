<script setup lang="ts">
import { h, ref, reactive, onMounted } from "vue";
import { useRouter } from "vue-router";
import {
  ElMessage,
  ElMessageBox,
  ElTag,
  ElButton,
  ElProgress
} from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import {
  getTaskList,
  createTask,
  startTask,
  stopTask,
  type TrainingTask,
  type CreateTaskPayload
} from "@/api/training";

defineOptions({ name: "TrainingList" });

const router = useRouter();

const loading = ref(false);
const dataList = ref<TrainingTask[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  size: 10,
  keyword: "",
  status: ""
});

const pagination = reactive({
  total: 0,
  currentPage: 1,
  pageSize: 20,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const statusOptions = [
  { label: "全部", value: "" },
  { label: "排队中", value: "queued" },
  { label: "运行中", value: "running" },
  { label: "已完成", value: "completed" },
  { label: "已失败", value: "failed" },
  { label: "已取消", value: "cancelled" }
];

/** 状态 -> tag type 映射（后端 TaskStatus 终态为 cancelled，非 stopped） */
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

function statusType(s: string) {
  return statusTypeMap[s] ?? "info";
}

function statusText(s: string) {
  return statusTextMap[s] ?? s;
}

const columns: TableColumnList = [
  {
    label: "名称",
    prop: "name",
    minWidth: 160,
    showOverflowTooltip: true
  },
  {
    label: "状态",
    width: 120,
    align: "center",
    cellRenderer: ({ row }) =>
      h(
        ElTag,
        {
          type: statusType(row.status),
          effect: "light",
          class: { "status-running": row.status === "running" }
        },
        () => statusText(row.status)
      )
  },
  {
    label: "进度",
    width: 160,
    align: "center",
    cellRenderer: ({ row }) =>
      typeof row.progress === "number"
        ? h(ElProgress, {
            percentage: Math.min(Math.max(row.progress, 0), 100),
            status:
              row.status === "failed"
                ? "exception"
                : row.status === "completed"
                  ? "success"
                  : undefined
          })
        : "-"
  },
  {
    label: "节点",
    prop: "node",
    width: 140,
    align: "center",
    cellRenderer: ({ row }) => row.node || "-"
  },
  {
    label: "GPU",
    prop: "resourceSpec.gpuCount",
    width: 90,
    align: "center",
    cellRenderer: ({ row }) => row.resourceSpec?.gpuCount ?? 0
  },
  {
    label: "创建时间",
    prop: "createdAt",
    width: 180,
    align: "center",
    cellRenderer: ({ row }) => row.createdAt || "-"
  },
  {
    label: "操作",
    width: 240,
    fixed: "right",
    align: "center",
    cellRenderer: ({ row }) =>
      h("div", { class: "flex items-center justify-center gap-2" }, [
        canStart(row.status)
          ? h(
              ElButton,
              {
                type: "primary",
                link: true,
                size: "small",
                onClick: () => handleStart(row)
              },
              () => "启动"
            )
          : null,
        canStop(row.status)
          ? h(
              ElButton,
              {
                type: "warning",
                link: true,
                size: "small",
                onClick: () => handleStop(row)
              },
              () => "停止"
            )
          : null,
        h(
          ElButton,
          {
            type: "info",
            link: true,
            size: "small",
            onClick: () => goMonitor(row)
          },
          () => "监控"
        )
      ])
  }
];

/** 新建任务弹窗 */
const dialogVisible = ref(false);
const submitLoading = ref(false);
const formRef = ref<FormInstance>();
const form = reactive<CreateTaskPayload>({
  name: "",
  image: "",
  command: "",
  resourceSpec: {
    gpuCount: 0,
    cpu: 4,
    memoryBytes: 8 * 1024 * 1024 * 1024
  },
  hyperparameters: {}
});

const hyperStr = ref("");
const rules: FormRules = {
  name: [{ required: true, message: "请输入任务名称", trigger: "blur" }],
  image: [{ required: true, message: "请输入镜像地址", trigger: "blur" }],
  command: [{ required: true, message: "请输入启动命令", trigger: "blur" }]
};

function resetForm() {
  form.name = "";
  form.image = "";
  form.command = "";
  form.resourceSpec = {
    gpuCount: 0,
    cpu: 4,
    memoryBytes: 8 * 1024 * 1024 * 1024
  };
  form.hyperparameters = {};
  hyperStr.value = "";
}

function openCreate() {
  // 跳转到专用创建页：容器化调度需要 containerSpec（imageName/imageTarRelPath/datasetRelPath），
  // 仅由 /training/create 通过选择 ready 模型版本 + 数据集版本组装；本页旧弹窗只能提交 image/command，
  // 创建出的任务无法被调度执行，故统一引导到创建页。
  router.push("/training/create");
}

function parseHyperparameters(): Record<string, string> {
  const result: Record<string, string> = {};
  hyperStr.value
    .split(/[\n,]/)
    .map(line => line.trim())
    .filter(Boolean)
    .forEach(line => {
      const idx = line.indexOf("=");
      if (idx > 0) {
        result[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
      }
    });
  return result;
}

async function submitCreate() {
  if (!formRef.value) return;
  await formRef.value.validate(async valid => {
    if (!valid) return;
    submitLoading.value = true;
    try {
      form.hyperparameters = parseHyperparameters();
      const res = await createTask({ ...form });
      if (res.code === 0) {
        ElMessage.success("创建成功");
        dialogVisible.value = false;
        onSearch();
      } else {
        ElMessage.error(res.message || "创建失败");
      }
    } catch (e: any) {
      ElMessage.error(e?.message || "创建失败");
    } finally {
      submitLoading.value = false;
    }
  });
}

async function handleStart(row: TrainingTask) {
  try {
    const res = await startTask(row.id);
    if (res.code === 0) {
      ElMessage.success("已启动");
      onSearch();
    } else {
      ElMessage.error(res.message || "启动失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "启动失败");
  }
}

async function handleStop(row: TrainingTask) {
  try {
    await ElMessageBox.confirm(`确认停止任务「${row.name}」吗？`, "提示", {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    const res = await stopTask(row.id);
    if (res.code === 0) {
      ElMessage.success("已停止");
      onSearch();
    } else {
      ElMessage.error(res.message || "停止失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "停止失败");
  }
}

function goMonitor(row: TrainingTask) {
  router.push(`/training/monitor/${row.id}`);
}

async function onSearch() {
  loading.value = true;
  try {
    const res = await getTaskList({
      page: query.page,
      size: query.size,
      keyword: query.keyword,
      status: query.status
    });
    if (res.code === 0 && res.data) {
      dataList.value = res.data.items ?? [];
      total.value = res.data.total ?? 0;
      pagination.total = total.value;
      pagination.currentPage = query.page;
      pagination.pageSize = query.size;
    } else {
      ElMessage.error(res.message || "查询失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "查询失败");
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  query.page = 1;
  onSearch();
}

function resetQuery() {
  query.keyword = "";
  query.status = "";
  query.page = 1;
  onSearch();
}

function onSizeChange(val: number) {
  query.size = val;
  query.page = 1;
  onSearch();
}

function onCurrentChange(val: number) {
  query.page = val;
  onSearch();
}

function canStart(status: string) {
  return ["queued", "pending", "cancelled", "failed", "paused"].includes(status);
}

function canStop(status: string) {
  return ["running", "queued", "pending"].includes(status);
}

onMounted(() => {
  onSearch();
});
</script>

<template>
  <div class="p-5">
    <el-card shadow="never">
      <!-- 搜索区 -->
      <el-form :inline="true" class="mb-4">
        <el-form-item label="名称">
          <el-input
            v-model="query.keyword"
            placeholder="请输入任务名称"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="query.status"
            placeholder="全部"
            clearable
            style="width: 160px"
          >
            <el-option
              v-for="opt in statusOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
        <el-form-item>
          <el-button type="success" @click="openCreate">新建任务</el-button>
        </el-form-item>
      </el-form>

      <!-- 列表 -->
      <pure-table
        row-key="id"
        border
        stripe
        :loading="loading"
        :data="dataList"
        :columns="columns"
        :pagination="pagination"
        @page-size-change="onSizeChange"
        @page-current-change="onCurrentChange"
      />
    </el-card>

    <!-- 新建任务弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      title="新建训练任务"
      width="640px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="110px"
      >
        <el-form-item label="任务名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入任务名称" />
        </el-form-item>
        <el-form-item label="镜像地址" prop="image">
          <el-input
            v-model="form.image"
            placeholder="例如 registry.example.com/train:latest"
          />
        </el-form-item>
        <el-form-item label="启动命令" prop="command">
          <el-input
            v-model="form.command"
            type="textarea"
            :rows="2"
            placeholder="例如 python train.py --epochs 10"
          />
        </el-form-item>
        <el-form-item label="GPU 数量">
          <el-input-number
            v-model="form.resourceSpec.gpuCount"
            :min="0"
            :max="64"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="CPU (核)">
          <el-input-number
            v-model="form.resourceSpec.cpu"
            :min="1"
            :max="512"
            controls-position="right"
          />
        </el-form-item>
        <el-form-item label="内存 (GB)">
          <el-input-number
            :model-value="form.resourceSpec.memoryBytes / (1024 * 1024 * 1024)"
            :min="1"
            :max="4096"
            controls-position="right"
            @update:model-value="
              (v: number) => (form.resourceSpec.memoryBytes = v * 1024 * 1024 * 1024)
            "
          />
        </el-form-item>
        <el-form-item label="超参数">
          <el-input
            v-model="hyperStr"
            type="textarea"
            :rows="3"
            placeholder="每行一个，格式 key=value，例如&#10;learning_rate=0.001&#10;batch_size=32"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitCreate">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
/* 运行中状态呼吸动画 */
:deep(.status-running) {
  animation: training-running-pulse 1.4s ease-in-out infinite;
}

@keyframes training-running-pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.6;
    transform: scale(1.05);
  }
}
</style>
