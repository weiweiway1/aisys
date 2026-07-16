<script setup lang="ts">
import { h, reactive, ref, onMounted } from "vue";
import { ElTag, ElButton, ElMessage, ElMessageBox } from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import {
  getUserPage,
  createUser,
  updateUser,
  deleteUser,
  assignUserRoles,
  getRoleList,
  type UserQuery
} from "@/api/system";

defineOptions({ name: "SystemUser" });

/* ----------------------------- 列表数据与分页 ----------------------------- */
const loading = ref(false);
const dataList = ref<any[]>([]);
const total = ref(0);

const query = reactive<UserQuery>({
  page: 1,
  size: 10,
  keyword: "",
  tenantId: undefined,
  role: "",
  status: ""
});

const pagination = reactive<PaginationProps>({
  total: 0,
  currentPage: 1,
  pageSize: 10,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const statusType = (s: string) => {
  const v = String(s || "").toLowerCase();
  return v === "active" ? "success" : v === "disabled" ? "info" : "warning";
};
const statusLabel = (s?: string) => {
  const v = String(s || "").toLowerCase();
  return v === "active" ? "启用" : v === "disabled" ? "停用" : s || "-";
};

const columns: TableColumnList = [
  { type: "index", label: "#", width: 55 },
  { label: "用户名", prop: "username", minWidth: 120 },
  { label: "昵称", prop: "nickname", minWidth: 120 },
  {
    label: "邮箱",
    prop: "email",
    minWidth: 180,
    showOverflowTooltip: true
  },
  { label: "电话", prop: "phone", minWidth: 130 },
  {
    label: "租户ID",
    prop: "tenantId",
    width: 90,
    align: "center"
  },
  {
    label: "角色",
    minWidth: 160,
    cellRenderer: ({ row }) => {
      const roles = row.roles || [];
      if (!roles.length) return "-";
      return roles.map((r: string) =>
        h(ElTag, { type: "primary", effect: "plain", class: "mr-1" }, () => r)
      );
    }
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
    const res: any = await getUserPage({
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
  query.tenantId = undefined;
  query.role = "";
  query.status = "";
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

/* --------------------------------- 角色下拉 -------------------------------- */
const roleOptions = ref<any[]>([]);
const fetchRoles = async () => {
  try {
    const res: any = await getRoleList();
    if (res?.code === 0) {
      const d = res.data;
      roleOptions.value = Array.isArray(d) ? d : d?.items ?? [];
    }
  } catch {
    /* 非关键，忽略 */
  }
};

/* --------------------------------- 弹窗表单 -------------------------------- */
const dialogVisible = ref(false);
const dialogTitle = ref("新建用户");
const submitting = ref(false);
const formRef = ref();
const editingId = ref<number | string | null>(null);

const defaultForm = () => ({
  username: "",
  nickname: "",
  email: "",
  phone: "",
  password: "",
  tenantId: undefined as number | undefined,
  roles: [] as string[],
  status: "active"
});

const form = reactive(defaultForm());

const rules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  nickname: [{ required: true, message: "请输入昵称", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }]
};

const openCreate = () => {
  editingId.value = null;
  dialogTitle.value = "新建用户";
  Object.assign(form, defaultForm());
  dialogVisible.value = true;
};

const openEdit = (row: any) => {
  editingId.value = row.id;
  dialogTitle.value = "编辑用户";
  Object.assign(form, defaultForm(), {
    username: row.username ?? "",
    nickname: row.nickname ?? "",
    email: row.email ?? "",
    phone: row.phone ?? "",
    password: "",
    tenantId: row.tenantId,
    roles: row.roles ?? [],
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
      if (editingId.value) {
        // 编辑：Update DTO 仅含 email/nickname/phone/status（无 roles/username/tenantId）
        const base: any = { ...form };
        delete base.roles;
        delete base.username;
        delete base.tenantId;
        if (!base.password) delete base.password;
        const res: any = await updateUser(editingId.value, base);
        if (res?.code !== 0) {
          ElMessage.error(res?.message ?? "更新失败");
          return;
        }
        // 角色重新分配：roleCodes → roleIds，走专用端点 PUT /users/{id}/roles
        const roleIds = (form.roles || [])
          .map((code: string) => roleOptions.value.find((r: any) => r.code === code)?.id)
          .filter((id: any) => id != null);
        const rr: any = await assignUserRoles(editingId.value, roleIds);
        if (rr?.code !== 0) {
          ElMessage.error(rr?.message ?? "角色更新失败");
          return;
        }
        ElMessage.success("更新成功");
        dialogVisible.value = false;
        fetchList();
      } else {
        // 新建：角色字段名须为 roleCodes（匹配后端 UserDtos.Create）
        const payload: any = { ...form, roleCodes: form.roles };
        delete payload.roles;
        const res: any = await createUser(payload);
        if (res?.code === 0) {
          ElMessage.success("创建成功");
          dialogVisible.value = false;
          fetchList();
        } else {
          ElMessage.error(res?.message ?? "创建失败");
        }
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
  ElMessageBox.confirm(`确认删除用户「${row.username}」吗？`, "提示", {
    type: "warning"
  })
    .then(async () => {
      try {
        const res: any = await deleteUser(row.id);
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
  fetchRoles();
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
          placeholder="用户名 / 昵称 / 邮箱"
          clearable
          style="width: 220px"
          @keyup.enter="onSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
        <el-button type="success" @click="openCreate">新建用户</el-button>
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
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            :disabled="!!editingId"
          />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="请输入昵称" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="电话">
          <el-input v-model="form.phone" placeholder="请输入电话" />
        </el-form-item>
        <el-form-item
          label="密码"
          :prop="editingId ? undefined : 'password'"
        >
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="editingId ? '留空则不修改' : '请输入密码'"
          />
        </el-form-item>
        <el-form-item label="租户ID">
          <el-input-number
            v-model="form.tenantId"
            :min="1"
            controls-position="right"
            placeholder="平台用户留空"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select
            v-model="form.roles"
            multiple
            filterable
            allow-create
            placeholder="请选择角色"
            style="width: 100%"
          >
            <el-option
              v-for="r in roleOptions"
              :key="(r.code ?? r.name ?? r.id) + ''"
              :label="r.name ?? r.code"
              :value="r.code ?? r.name"
            />
          </el-select>
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
