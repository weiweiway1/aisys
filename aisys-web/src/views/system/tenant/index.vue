<script setup lang="ts">
import { h, reactive, ref, onMounted } from "vue";
import { ElTag, ElButton, ElMessage, ElMessageBox } from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import {
  getTenantPage,
  createTenant,
  updateTenant,
  deleteTenant,
  type TenantQuery
} from "@/api/system";

defineOptions({ name: "SystemTenant" });

/* ----------------------------- 列表数据与分页 ----------------------------- */
const loading = ref(false);
const dataList = ref<any[]>([]);
const total = ref(0);

const query = reactive<TenantQuery>({
  page: 1,
  size: 10,
  keyword: ""
});

const pagination = reactive<PaginationProps>({
  total: 0,
  currentPage: 1,
  pageSize: 10,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const GB = 1073741824;

const statusType = (s: string) => {
  const v = String(s || "").toLowerCase();
  return v === "active" ? "success" : v === "disabled" ? "info" : "warning";
};
const statusLabel = (s?: string) => {
  const v = String(s || "").toLowerCase();
  return v === "active" ? "启用" : v === "disabled" ? "停用" : s || "-";
};

const columns: TableColumnList = [
  { label: "ID", prop: "id", width: 70, align: "center" },
  { label: "租户名称", prop: "name", minWidth: 160 },
  { label: "租户编码", prop: "code", minWidth: 140 },
  {
    label: "配额(GB)",
    prop: "maxQuotaBytes",
    minWidth: 110,
    align: "right",
    cellRenderer: ({ row }) =>
      row.maxQuotaBytes ? (row.maxQuotaBytes / GB).toFixed(1) + " GB" : "-"
  },
  {
    label: "状态",
    prop: "status",
    width: 100,
    align: "center",
    cellRenderer: ({ row }) =>
      h(ElTag, { type: statusType(row.status) }, () => statusLabel(row.status))
  },
  {
    label: "操作",
    width: 160,
    fixed: "right",
    align: "center",
    cellRenderer: ({ row }) => [
      h(
        ElButton,
        { link: true, type: "primary", size: "small", onClick: () => openEdit(row) },
        () => "编辑"
      ),
      h(
        ElButton,
        { link: true, type: "danger", size: "small", onClick: () => handleDelete(row) },
        () => "删除"
      )
    ]
  }
];

const fetchList = async () => {
  loading.value = true;
  try {
    const res: any = await getTenantPage({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined
    });
    if (res?.code === 0) {
      const d = res.data ?? {};
      dataList.value = d.items ?? [];
      total.value = d.total ?? 0;
      pagination.total = d.total ?? 0;
      pagination.currentPage = query.page;
      pagination.pageSize = query.size;
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
  query.page = 1;
  fetchList();
};

const resetQuery = () => {
  query.keyword = "";
  onSearch();
};

const onSizeChange = (val: number) => {
  query.size = val;
  query.page = 1;
  fetchList();
};

const onCurrentChange = (val: number) => {
  query.page = val;
  fetchList();
};

/* --------------------------------- 弹窗表单 -------------------------------- */
const dialogVisible = ref(false);
const dialogTitle = ref("新建租户");
const submitting = ref(false);
const formRef = ref();
const editingId = ref<number | string | null>(null);

const defaultForm = () => ({
  name: "",
  code: "",
  quotaGb: 10,
  status: "active"
});

const form = reactive(defaultForm());

const rules = {
  name: [{ required: true, message: "请输入租户名称", trigger: "blur" }],
  code: [{ required: true, message: "请输入租户编码", trigger: "blur" }]
};

const openCreate = () => {
  editingId.value = null;
  dialogTitle.value = "新建租户";
  Object.assign(form, defaultForm());
  dialogVisible.value = true;
};

const openEdit = (row: any) => {
  editingId.value = row.id;
  dialogTitle.value = "编辑租户";
  Object.assign(form, defaultForm(), {
    name: row.name ?? "",
    code: row.code ?? "",
    quotaGb: row.maxQuotaBytes ? row.maxQuotaBytes / GB : 10,
    status: row.status ?? "active"
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
      // 表单以 GB 录入，提交时换算为字节
      payload.maxQuotaBytes = Math.round((Number(payload.quotaGb) || 0) * GB);
      delete payload.quotaGb;
      const res: any = editingId.value
        ? await updateTenant(editingId.value, payload)
        : await createTenant(payload);
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

/* ---------------------------------- 删除 ---------------------------------- */
const handleDelete = (row: any) => {
  ElMessageBox.confirm(`确认删除租户「${row.name}」吗？`, "提示", {
    type: "warning"
  })
    .then(async () => {
      try {
        const res: any = await deleteTenant(row.id);
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

onMounted(() => {
  fetchList();
});
</script>

<template>
  <div class="p-4">
    <!-- 查询区 -->
    <el-form :inline="true" :model="query" class="mb-2">
      <el-form-item label="关键字">
        <el-input
          v-model="query.keyword"
          placeholder="租户名称 / 编码"
          clearable
          style="width: 220px"
          @keyup.enter="onSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
        <el-button type="success" @click="openCreate">新建租户</el-button>
      </el-form-item>
    </el-form>

    <!-- 表格 -->
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
      width="520px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="租户名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入租户名称" />
        </el-form-item>
        <el-form-item label="租户编码" prop="code">
          <el-input
            v-model="form.code"
            placeholder="请输入租户编码"
            :disabled="!!editingId"
          />
        </el-form-item>
        <el-form-item label="配额(GB)">
          <el-input-number
            v-model="form.quotaGb"
            :min="0"
            :step="1"
            :precision="1"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="启用" value="active" />
            <el-option label="禁用" value="disabled" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          确认
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
