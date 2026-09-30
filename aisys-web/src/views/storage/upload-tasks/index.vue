<script setup lang="ts">
import { h, reactive, ref, onMounted, onUnmounted } from "vue";
import { ElTag, ElButton, ElMessage, ElMessageBox } from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import { listUploadTasks, cancelUploadTask } from "@/api/storage";

defineOptions({ name: "StorageUploadTasks" });

const loading = ref(false);
const dataList = ref<any[]>([]);
const statusFilter = ref("");

const pagination = reactive<PaginationProps>({
  total: 0,
  currentPage: 1,
  pageSize: 20,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const fmtSize = (b?: number) => {
  if (b == null || Number.isNaN(b)) return "-";
  if (b === 0) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(u.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
  return `${(b / Math.pow(1024, i)).toFixed(2)} ${u[i]}`;
};

const statusMeta = (s?: string) => {
  switch ((s || "").toUpperCase()) {
    case "UPLOADING":
      return { type: "primary", text: "上传中（浏览器→后端）" };
    case "PROCESSING":
      return { type: "warning", text: "后端写入存储池中" };
    case "COMPLETED":
      return { type: "success", text: "已完成" };
    case "FAILED":
      return { type: "danger", text: "失败" };
    case "CANCELLED":
      return { type: "info", text: "已取消" };
    default:
      return { type: "info", text: s || "-" };
  }
};

const columns: TableColumnList = [
  { label: "ID", prop: "id", width: 70, align: "center" },
  {
    label: "文件名",
    prop: "fileName",
    minWidth: 180,
    showOverflowTooltip: true
  },
  {
    label: "路径",
    prop: "relativePath",
    minWidth: 200,
    showOverflowTooltip: true
  },
  {
    label: "大小",
    prop: "sizeBytes",
    width: 120,
    align: "right",
    formatter: ({ sizeBytes }: any) => fmtSize(sizeBytes)
  },
  {
    label: "进度",
    width: 130,
    align: "center",
    cellRenderer: ({ row }: any) =>
      h("span", null, () => `${row.receivedChunks}/${row.totalChunks} 片`)
  },
  {
    label: "状态",
    prop: "status",
    width: 180,
    align: "center",
    cellRenderer: ({ row }: any) => {
      const m = statusMeta(row.status);
      return h(ElTag, { type: m.type }, () => m.text);
    }
  },
  {
    label: "错误",
    prop: "errorMsg",
    minWidth: 160,
    showOverflowTooltip: true,
    formatter: ({ errorMsg }: any) => errorMsg || "-"
  },
  {
    label: "操作",
    width: 110,
    fixed: "right",
    align: "center",
    cellRenderer: ({ row }: any) => {
      const active = row.status === "UPLOADING" || row.status === "PROCESSING";
      return active
        ? h(
            ElButton,
            {
              link: true,
              type: "danger",
              size: "small",
              loading: cancelingId.value === row.id,
              onClick: () => onCancel(row)
            },
            () => "取消"
          )
        : h("span", { class: "text-gray-300" }, () => "-");
    }
  },
  { label: "创建时间", prop: "createdAt", minWidth: 170 }
];

const fetchList = async () => {
  loading.value = true;
  try {
    const res: any = await listUploadTasks({
      status: statusFilter.value || undefined,
      page: pagination.currentPage,
      size: pagination.pageSize
    });
    if (res?.code === 0) {
      dataList.value = res.data?.items ?? [];
      pagination.total = res.data?.total ?? 0;
    } else {
      ElMessage.error(res?.message ?? "查询失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "查询失败");
  } finally {
    loading.value = false;
  }
};

const onSearch = () => {
  pagination.currentPage = 1;
  fetchList();
};
const onSizeChange = (v: number) => {
  pagination.pageSize = v;
  pagination.currentPage = 1; // 改变页大小后回到第 1 页，避免请求越界 offset
  fetchList();
};
const onCurrentChange = (v: number) => {
  pagination.currentPage = v;
  fetchList();
};

const cancelingId = ref<any>(null);
const onCancel = (row: any) => {
  ElMessageBox.confirm(`确认取消上传任务「${row.fileName || row.id}」吗？`, "提示", {
    type: "warning"
  })
    .then(async () => {
      cancelingId.value = row.id;
      try {
        const res: any = await cancelUploadTask(row.id);
        if (res?.code === 0) {
          ElMessage.success("已取消");
          fetchList();
        } else {
          ElMessage.error(res?.message ?? "取消失败");
        }
      } catch (e: any) {
        ElMessage.error(e?.message ?? "取消失败");
      } finally {
        cancelingId.value = null;
      }
    })
    .catch(() => {});
};

// 自动刷新（上传中/处理中的任务状态会变化，便于观察 后端→存储池）
let timer: any = null;
onMounted(() => {
  fetchList();
  timer = setInterval(fetchList, 3000);
});
onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>

<template>
  <div class="p-4">
    <el-form :inline="true" class="mb-2">
      <el-form-item label="状态">
        <el-select
          v-model="statusFilter"
          placeholder="全部"
          clearable
          style="width: 180px"
          @change="onSearch"
        >
          <el-option label="上传中" value="UPLOADING" />
          <el-option label="处理中" value="PROCESSING" />
          <el-option label="已完成" value="COMPLETED" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="fetchList">刷新</el-button>
      </el-form-item>
      <span class="text-xs text-gray-400 ml-2">每 3 秒自动刷新</span>
    </el-form>

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
  </div>
</template>
