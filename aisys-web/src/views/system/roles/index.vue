<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from "vue";
import {
  ElButton,
  ElMessage,
  ElMessageBox,
  ElTag,
  ElCheckboxGroup,
  ElCheckbox
} from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import {
  createRole,
  deleteRole,
  getAllPermissions,
  getRolePage,
  getRolePermissions,
  updateRole,
  updateRolePermissions
} from "@/api/system";

defineOptions({ name: "SystemRoles" });

/* --------------------------------- 列表 ---------------------------------- */
const loading = ref(false);
const dataList = ref<any[]>([]);

const query = reactive({
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

const columns: TableColumnList = [
  { label: "ID", prop: "id", width: 70, align: "center" },
  { label: "角色编码", prop: "code", minWidth: 140 },
  { label: "角色名称", prop: "name", minWidth: 140 },
  {
    label: "描述",
    prop: "description",
    minWidth: 200,
    showOverflowTooltip: true
  },
  {
    label: "系统内置",
    prop: "isSystem",
    width: 100,
    align: "center",
    cellRenderer: ({ row }) =>
      h(
        ElTag,
        { type: row.isSystem ? "success" : "info" },
        () => (row.isSystem ? "是" : "否")
      )
  },
  {
    label: "操作",
    width: 240,
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
        {
          link: true,
          type: "warning",
          size: "small",
          onClick: () => openPermission(row)
        },
        () => "权限"
      ),
      h(
        ElButton,
        {
          link: true,
          type: "danger",
          size: "small",
          disabled: !!row.isSystem,
          onClick: () => handleDelete(row)
        },
        () => "删除"
      )
    ]
  }
];

const fetchList = async () => {
  loading.value = true;
  try {
    const res: any = await getRolePage({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined
    });
    if (res?.code === 0) {
      // 后端 /roles 返回裸数组（非分页），兼容 items/list 两种结构
      const d = res.data;
      const arr = Array.isArray(d) ? d : (d?.items ?? d?.list ?? []);
      dataList.value = arr;
      pagination.total = Array.isArray(d) ? arr.length : (d?.total ?? arr.length);
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

/* -------------------------------- 新增/编辑 ------------------------------- */
const dialogVisible = ref(false);
const dialogTitle = ref("新建角色");
const submitting = ref(false);
const formRef = ref();
const editingId = ref<number | string | null>(null);

const defaultForm = () => ({
  code: "",
  name: "",
  description: ""
});

const form = reactive(defaultForm());

const rules = {
  code: [{ required: true, message: "请输入角色编码", trigger: "blur" }],
  name: [{ required: true, message: "请输入角色名称", trigger: "blur" }]
};

const openCreate = () => {
  editingId.value = null;
  dialogTitle.value = "新建角色";
  Object.assign(form, defaultForm());
  dialogVisible.value = true;
};

const openEdit = (row: any) => {
  editingId.value = row.id;
  dialogTitle.value = "编辑角色";
  Object.assign(form, defaultForm(), {
    code: row.code ?? "",
    name: row.name ?? "",
    description: row.description ?? ""
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
      const res: any = editingId.value
        ? await updateRole(editingId.value, payload)
        : await createRole(payload);
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

/* --------------------------------- 删除 ---------------------------------- */
const handleDelete = (row: any) => {
  if (row.isSystem) {
    ElMessage.warning("系统内置角色不可删除");
    return;
  }
  ElMessageBox.confirm(`确认删除角色「${row.name}」吗？`, "提示", {
    type: "warning"
  })
    .then(async () => {
      try {
        const res: any = await deleteRole(row.id);
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

/* -------------------------------- 权限分配 -------------------------------- */
const permDialogVisible = ref(false);
const permSubmitting = ref(false);
const permLoading = ref(false);
const allPermissions = ref<any[]>([]);
const currentRole = ref<any>(null);
const checkedPermissionIds = ref<Array<number | string>>([]);

/** 按 resource 字段对全部权限分组 */
const RESOURCE_LABELS: Record<string, string> = {
  system: "系统",
  model: "模型",
  dataset: "数据集",
  training: "训练",
  evaluation: "评测",
  resource: "资源",
  storage: "存储",
  notification: "通知",
  monitor: "监控",
  其他: "其他"
};
const resourceLabel = (r?: string) => (r && RESOURCE_LABELS[r]) || r || "其他";

const groupedPermissions = computed(() => {
  const map: Record<string, any[]> = {};
  for (const p of allPermissions.value) {
    const key = p.resource || "其他";
    (map[key] ||= []).push(p);
  }
  return Object.entries(map).map(([resource, list]) => ({
    resource,
    list,
    allChecked: list.every(p => checkedPermissionIds.value.includes(p.id)),
    indeterminate:
      list.some(p => checkedPermissionIds.value.includes(p.id)) &&
      !list.every(p => checkedPermissionIds.value.includes(p.id))
  }));
});

const fetchAllPermissions = async () => {
  try {
    const res: any = await getAllPermissions();
    if (res?.code === 0) {
      allPermissions.value = Array.isArray(res.data)
        ? res.data
        : res.data?.items ?? [];
    }
  } catch {
    // ignore
  }
};

const openPermission = async (row: any) => {
  currentRole.value = row;
  checkedPermissionIds.value = [];
  permDialogVisible.value = true;
  permLoading.value = true;
  await fetchAllPermissions();
  try {
    const res: any = await getRolePermissions(row.id);
    if (res?.code === 0) {
      const ids = Array.isArray(res.data)
        ? res.data
        : res.data?.permissionIds ?? res.data?.items ?? [];
      checkedPermissionIds.value = ids.map((x: any) =>
        typeof x === "object" ? x.id : x
      );
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "获取角色权限失败");
  } finally {
    permLoading.value = false;
  }
};

const onGroupCheck = (group: { resource: string; list: any[] }, val: boolean) => {
  const ids = group.list.map(p => p.id);
  const set = new Set(checkedPermissionIds.value);
  if (val) ids.forEach(id => set.add(id));
  else ids.forEach(id => set.delete(id));
  checkedPermissionIds.value = Array.from(set);
};

const submitPermissions = async () => {
  if (!currentRole.value) return;
  permSubmitting.value = true;
  try {
    // 过滤 group「全选」复选框误注入的 undefined/null/空串，避免后端 NOT NULL 违约
    const ids = checkedPermissionIds.value.filter(
      (x: any) => x !== null && x !== undefined && x !== ""
    );
    const res: any = await updateRolePermissions(currentRole.value.id, ids);
    if (res?.code === 0) {
      ElMessage.success("权限保存成功");
      permDialogVisible.value = false;
    } else {
      ElMessage.error(res?.message ?? "权限保存失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "权限保存失败");
  } finally {
    permSubmitting.value = false;
  }
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
          placeholder="角色编码 / 名称"
          clearable
          style="width: 220px"
          @keyup.enter="onSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
        <el-button type="success" @click="openCreate">新建角色</el-button>
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
      width="500px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="角色编码" prop="code">
          <el-input
            v-model="form.code"
            placeholder="请输入角色编码"
            :disabled="!!editingId"
          />
        </el-form-item>
        <el-form-item label="角色名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入角色名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入描述"
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

    <!-- 权限分配弹窗 -->
    <el-dialog
      v-model="permDialogVisible"
      :title="`权限分配 - ${currentRole?.name ?? ''}`"
      width="640px"
      destroy-on-close
    >
      <div v-loading="permLoading">
        <div v-for="group in groupedPermissions" :key="group.resource" class="mb-4">
          <div class="mb-2 font-bold">
            <el-checkbox
              :model-value="group.allChecked"
              :indeterminate="group.indeterminate"
              @change="(val: boolean) => onGroupCheck(group, val)"
            >
              {{ resourceLabel(group.resource) }}
            </el-checkbox>
          </div>
          <div class="pl-6 flex flex-wrap gap-y-2">
            <el-checkbox-group v-model="checkedPermissionIds">
              <el-checkbox
                v-for="p in group.list"
                :key="p.id"
                :value="p.id"
                :label="p.name || p.code || p.action"
              >
                {{ p.name || p.code || p.action }}
              </el-checkbox>
            </el-checkbox-group>
          </div>
        </div>
        <el-empty
          v-if="!groupedPermissions.length && !permLoading"
          description="暂无权限"
        />
      </div>
      <template #footer>
        <el-button @click="permDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="permSubmitting"
          @click="submitPermissions"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
