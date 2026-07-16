<script setup lang="ts">
import { h, reactive, ref, onMounted } from "vue";
import {
  ElMessage,
  ElMessageBox,
  ElTag,
  ElButton,
  ElProgress
} from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import {
  getStoragePools,
  getStoragePoolUsage,
  createPool,
  updatePool,
  deletePool,
  type StoragePool,
  type StoragePoolUsage
} from "@/api/storage";

defineOptions({
  name: "StoragePools"
});

/* ----------------------------- 列表与分页 ----------------------------- */
const loading = ref(false);
const dataList = ref<(StoragePool & { usage?: StoragePoolUsage })[]>([]);
const searchName = ref("");

const pagination = reactive<PaginationProps>({
  total: 0,
  currentPage: 1,
  pageSize: 10,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

/** 字节格式化 */
function formatBytes(bytes?: number): string {
  if (bytes === undefined || bytes === null || Number.isNaN(bytes)) return "-";
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB", "PB"];
  const i = Math.min(
    units.length - 1,
    Math.floor(Math.log(bytes) / Math.log(1024))
  );
  return `${(bytes / Math.pow(1024, i)).toFixed(2)} ${units[i]}`;
}

const GB = 1073741824;
/** 配额以 GB 展示 */
const fmtGB = (b?: number) =>
  b == null || b <= 0 ? "-" : (b / GB).toFixed(0) + " GB";

/** 用量百分比 */
function usagePercent(
  item: StoragePool & { usage?: StoragePoolUsage }
): number {
  if (typeof item.usage?.usagePercent === "number") {
    return Math.min(100, Math.round(item.usage.usagePercent));
  }
  const used = item.usage?.usedBytes;
  const quota = item.usage?.quotaBytes ?? item.quotaBytes;
  if (!used || !quota || quota <= 0) return 0;
  return Math.min(100, Math.round((used / quota) * 100));
}

function statusType(status?: string) {
  if (!status) return "info";
  const s = String(status).toLowerCase();
  if (s === "active" || s === "online" || s === "ok" || s === "healthy")
    return "success";
  if (s === "maintenance" || s === "warning") return "warning";
  if (s === "error" || s === "offline" || s === "disabled") return "danger";
  return "info";
}

/** 存储池状态 → 中文文案 */
const poolStatusLabel = (s?: string) => {
  const v = String(s || "").toLowerCase();
  if (v === "active") return "启用";
  if (v === "inactive" || v === "disabled") return "停用";
  if (v === "maintenance") return "维护中";
  return s || "未知";
};

const columns: TableColumnList = [
  { label: "ID", prop: "id", width: 70, align: "center" },
  { label: "名称", prop: "name", minWidth: 150 },
  {
    label: "类型",
    prop: "type",
    width: 120,
    align: "center",
    cellRenderer: ({ row }) =>
      h(ElTag, null, () => row.type || "-")
  },
  {
    label: "Endpoint",
    prop: "endpoint",
    minWidth: 200,
    showOverflowTooltip: true
  },
  {
    label: "配额(GB)",
    prop: "quotaBytes",
    width: 120,
    align: "right",
    cellRenderer: ({ row }) => fmtGB(row.quotaBytes)
  },
  {
    label: "用量",
    minWidth: 200,
    cellRenderer: ({ row }) => {
      const pct = usagePercent(row);
      return h("div", { class: "flex flex-col gap-1" }, [
        h(ElProgress, {
          percentage: pct,
          strokeWidth: 14,
          status:
            pct >= 90 ? "exception" : pct >= 70 ? "warning" : "success"
        }),
        h(
          "span",
          { class: "text-xs text-gray-500" },
          `${formatBytes(row.usage?.usedBytes)} / ${fmtGB(
            row.usage?.quotaBytes ?? row.quotaBytes
          )}`
        )
      ]);
    }
  },
  {
    label: "状态",
    prop: "status",
    width: 100,
    align: "center",
    cellRenderer: ({ row }) =>
      h(
        ElTag,
        { type: statusType(row.status) },
        () => poolStatusLabel(row.status)
      )
  },
  {
    label: "操作",
    width: 290,
    fixed: "right",
    align: "center",
    cellRenderer: ({ row }) => [
      h(
        ElButton,
        {
          link: true,
          type: "primary",
          size: "small",
          onClick: () => openDetail(row)
        },
        () => "详情"
      ),
      h(
        ElButton,
        {
          link: true,
          type: "primary",
          size: "small",
          onClick: () => openEdit(row)
        },
        () => "编辑"
      ),
      h(
        ElButton,
        {
          link: true,
          type: row.status === "active" ? "warning" : "success",
          size: "small",
          onClick: () => handleToggleStatus(row)
        },
        () => (row.status === "active" ? "停用" : "启用")
      ),
      h(
        ElButton,
        {
          link: true,
          type: "danger",
          size: "small",
          onClick: () => handleDelete(row)
        },
        () => "删除"
      )
    ]
  }
];

async function fetchList() {
  loading.value = true;
  try {
    const res: any = await getStoragePools({
      keyword: searchName.value || undefined,
      page: pagination.currentPage,
      size: pagination.pageSize
    });
    if (res?.code === 0) {
      const d = res.data;
      const arr: StoragePool[] = Array.isArray(d)
        ? d
        : Array.isArray(d?.items)
        ? d.items
        : Array.isArray(d?.list)
        ? d.list
        : [];
      dataList.value = arr.map(p => ({ ...p }));
      pagination.total = Array.isArray(d) ? arr.length : (d?.total ?? arr.length);
      // 异步加载用量
      dataList.value.forEach((p, idx) => {
        if (p.id === undefined || p.id === null) return;
        getStoragePoolUsage(p.id)
          .then((u: any) => {
            if (u?.code === 0) {
              dataList.value[idx].usage = u.data;
            }
          })
          .catch(() => {
            /* 用量获取失败，忽略 */
          });
      });
    } else {
      ElMessage.error(res?.message || "获取存储池列表失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "获取存储池列表失败");
  } finally {
    loading.value = false;
  }
}

const onSearch = () => fetchList();
const onReset = () => {
  searchName.value = "";
  fetchList();
};

const onSizeChange = (val: number) => {
  pagination.pageSize = val;
  pagination.currentPage = 1;
  fetchList();
};
const onCurrentChange = (val: number) => {
  pagination.currentPage = val;
  fetchList();
};

/* ----------------------------- 新增/编辑弹窗 ----------------------------- */
const dialogVisible = ref(false);
const dialogTitle = ref("新建存储池");
const submitting = ref(false);
const formRef = ref();
const editingId = ref<number | string | null>(null);

const defaultForm = () => ({
  name: "",
  type: "seaweedfs",
  endpoint: "",
  bucket: "",
  accessKey: "",
  secretKey: "",
  quotaGb: undefined as number | undefined
});

const form = reactive(defaultForm());

const rules = {
  name: [{ required: true, message: "请输入存储池名称", trigger: "blur" }],
  type: [{ required: true, message: "请选择类型", trigger: "change" }],
  endpoint: [{ required: true, message: "请输入 Endpoint", trigger: "blur" }],
  bucket: [{ required: true, message: "请输入 Bucket", trigger: "blur" }]
};

const openCreate = () => {
  editingId.value = null;
  dialogTitle.value = "新建存储池";
  Object.assign(form, defaultForm());
  dialogVisible.value = true;
};

const openEdit = (row: StoragePool) => {
  editingId.value = row.id;
  dialogTitle.value = "编辑存储池";
  Object.assign(form, defaultForm(), {
    name: row.name ?? "",
    type: row.type ?? "s3",
    endpoint: row.endpoint ?? "",
    bucket: row.bucket ?? "",
    accessKey: row.accessKey ?? "",
    secretKey: "",
    quotaGb: row.quotaBytes ? row.quotaBytes / GB : undefined
  });
  dialogVisible.value = true;
};

const submit = async () => {
  if (!formRef.value) return;
  await formRef.value.validate(async (valid: boolean) => {
    if (!valid) return;
    submitting.value = true;
    try {
      const payload: any = { ...form };
      // 配额以 GB 录入，换算为字节下发
      payload.quotaBytes = Math.round((Number(payload.quotaGb) || 0) * GB);
      delete payload.quotaGb;
      // 编辑时若未重新填写 SecretKey，则不下发（后端留空不修改，避免把遮蔽值回写）
      if (editingId.value && !payload.secretKey) delete payload.secretKey;
      const res: any = editingId.value
        ? await updatePool(editingId.value, payload)
        : await createPool(payload);
      if (res?.code === 0) {
        ElMessage.success(editingId.value ? "更新成功" : "创建成功");
        dialogVisible.value = false;
        fetchList();
      } else {
        ElMessage.error(res?.message ?? "操作失败");
      }
    } catch (e: any) {
      ElMessage.error(e?.message ?? "操作失败");
    } finally {
      submitting.value = false;
    }
  });
};

/* ----------------------------- 详情弹窗 ----------------------------- */
const detailVisible = ref(false);
const detailData = ref<StoragePool & { usage?: StoragePoolUsage } | null>(null);
const openDetail = (row: StoragePool & { usage?: StoragePoolUsage }) => {
  detailData.value = row;
  detailVisible.value = true;
};

/* ----------------------------- 删除 ----------------------------- */
const handleDelete = (row: StoragePool) => {
  ElMessageBox.confirm(`确认删除存储池「${row.name}」吗？`, "提示", {
    type: "warning"
  })
    .then(async () => {
      try {
        const res: any = await deletePool(row.id);
        if (res?.code === 0) {
          ElMessage.success("删除成功");
          fetchList();
        } else {
          ElMessage.error(res?.message ?? "删除失败");
        }
      } catch (e: any) {
        ElMessage.error(e?.message ?? "删除失败");
      }
    })
    .catch(() => {});
};

/* ----------------------------- 启用/停用 ----------------------------- */
const handleToggleStatus = async (
  row: StoragePool & { status?: string }
) => {
  const next = row.status === "active" ? "inactive" : "active";
  try {
    // 后端 PUT 复用 Create DTO（name 为 @NotBlank），须发全量字段规避 400
    const res: any = await updatePool(row.id, {
      name: row.name,
      type: row.type,
      endpoint: row.endpoint,
      bucket: row.bucket,
      accessKey: row.accessKey,
      quotaBytes: row.quotaBytes,
      status: next
    });
    if (res?.code === 0) {
      ElMessage.success(next === "active" ? "已启用" : "已停用");
      fetchList();
    } else {
      ElMessage.error(res?.message ?? "操作失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "操作失败");
  }
};

onMounted(() => {
  fetchList();
});
</script>

<template>
  <div class="p-4">
    <!-- 查询区 -->
    <el-form :inline="true" class="mb-2">
      <el-form-item label="名称">
        <el-input
          v-model="searchName"
          placeholder="存储池名称"
          clearable
          style="width: 220px"
          @keyup.enter="onSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="onReset">重置</el-button>
        <el-button v-perms="['storage:manage']" type="success" @click="openCreate">
          新建存储池
        </el-button>
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

    <!-- 新建/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="560px"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
      >
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入存储池名称" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" style="width: 100%">
            <el-option label="SeaweedFS" value="seaweedfs" />
            <el-option label="S3" value="s3" />
            <el-option label="OSS" value="oss" />
            <el-option label="COS" value="cos" />
            <el-option label="MinIO" value="minio" />
            <el-option label="本地存储" value="local" />
          </el-select>
        </el-form-item>
        <el-form-item label="Endpoint" prop="endpoint">
          <el-input v-model="form.endpoint" placeholder="https://s3.example.com" />
        </el-form-item>
        <el-form-item label="Bucket" prop="bucket">
          <el-input v-model="form.bucket" placeholder="请输入 Bucket" />
        </el-form-item>
        <el-form-item label="AccessKey">
          <el-input v-model="form.accessKey" placeholder="请输入 AccessKey" />
        </el-form-item>
        <el-form-item label="SecretKey">
          <el-input
            v-model="form.secretKey"
            type="password"
            show-password
            :placeholder="editingId ? '留空则不修改' : '请输入 SecretKey'"
          />
        </el-form-item>
        <el-form-item label="配额(GB)">
          <el-input-number
            v-model="form.quotaGb"
            :min="0"
            :step="1"
            :precision="0"
            style="width: 100%"
            placeholder="留空表示不限"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          确认
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" title="存储池详情" width="560px">
      <el-descriptions v-if="detailData" :column="1" border>
        <el-descriptions-item label="ID">
          {{ detailData.id }}
        </el-descriptions-item>
        <el-descriptions-item label="名称">
          {{ detailData.name }}
        </el-descriptions-item>
        <el-descriptions-item label="类型">
          {{ detailData.type }}
        </el-descriptions-item>
        <el-descriptions-item label="Endpoint">
          {{ detailData.endpoint }}
        </el-descriptions-item>
        <el-descriptions-item label="Bucket">
          {{ detailData.bucket }}
        </el-descriptions-item>
        <el-descriptions-item label="配额">
          {{ fmtGB(detailData.quotaBytes) }}
        </el-descriptions-item>
        <el-descriptions-item label="已用">
          {{ formatBytes(detailData.usage?.usedBytes) }}
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>
