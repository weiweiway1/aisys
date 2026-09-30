<script setup lang="ts">
import { h, reactive, ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { ElButton, ElMessage, ElMessageBox } from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import dayjs from "dayjs";
import {
  listNodeGroups,
  createNodeGroup,
  updateNodeGroup,
  deleteNodeGroup,
  type NodeGroup
} from "@/api/resource";

defineOptions({ name: "ResourceNodeGroups" });

const router = useRouter();
const goToNodes = (row: NodeGroup) => {
  router.push({ path: "/resources/nodes", query: { groupId: String(row.id) } });
};

/* ----------------------------- 列表与分页 ----------------------------- */
const loading = ref(false);
const dataList = ref<NodeGroup[]>([]);
const searchKeyword = ref("");

const pagination = reactive<PaginationProps>({
  total: 0,
  currentPage: 1,
  pageSize: 10,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const columns: TableColumnList = [
  { label: "ID", prop: "id", width: 70, align: "center" },
  { label: "分组名称", prop: "name", minWidth: 160 },
  {
    label: "节点数",
    prop: "nodeCount",
    width: 90,
    align: "center",
    cellRenderer: ({ row }) => h("span", null, () => String(row.nodeCount ?? 0))
  },
  {
    label: "描述",
    prop: "description",
    minWidth: 220,
    showOverflowTooltip: true
  },
  {
    label: "创建时间",
    prop: "createdAt",
    minWidth: 170,
    formatter: ({ createdAt }) =>
      createdAt
        ? dayjs(createdAt).format("YYYY-MM-DD HH:mm:ss")
        : "-"
  },
  {
    label: "操作",
    width: 220,
    fixed: "right",
    align: "center",
    cellRenderer: ({ row }) => [
      h(
        ElButton,
        {
          link: true,
          type: "primary",
          size: "small",
          onClick: () => goToNodes(row)
        },
        () => "查看节点"
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
    const res: any = await listNodeGroups();
    if (res?.code === 0) {
      const d = res.data;
      const arr: NodeGroup[] = Array.isArray(d)
        ? d
        : Array.isArray(d?.items)
        ? d.items
        : Array.isArray(d?.list)
        ? d.list
        : [];
      const filtered = searchKeyword.value
        ? arr.filter(g =>
            (g.name || "")
              .toLowerCase()
              .includes(searchKeyword.value.toLowerCase())
          )
        : arr;
      dataList.value = filtered;
      pagination.total = filtered.length;
    } else {
      ElMessage.error(res?.message ?? "查询失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "查询失败");
  } finally {
    loading.value = false;
  }
}

const onSearch = () => fetchList();
const onReset = () => {
  searchKeyword.value = "";
  fetchList();
};

const onSizeChange = (val: number) => {
  pagination.pageSize = val;
};
const onCurrentChange = (val: number) => {
  pagination.currentPage = val;
};

/* ----------------------------- 新增/编辑弹窗 ----------------------------- */
const dialogVisible = ref(false);
const dialogTitle = ref("新建节点分组");
const submitting = ref(false);
const formRef = ref();
const editingId = ref<number | string | null>(null);

const defaultForm = () => ({
  name: "",
  description: ""
});

const form = reactive(defaultForm());

const rules = {
  name: [{ required: true, message: "请输入分组名称", trigger: "blur" }]
};

const openCreate = () => {
  editingId.value = null;
  dialogTitle.value = "新建节点分组";
  Object.assign(form, defaultForm());
  dialogVisible.value = true;
};

const openEdit = (row: NodeGroup) => {
  editingId.value = row.id;
  dialogTitle.value = "编辑节点分组";
  Object.assign(form, defaultForm(), {
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
      const payload = { ...form };
      const res: any = editingId.value
        ? await updateNodeGroup(editingId.value, payload)
        : await createNodeGroup(payload);
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

/* ----------------------------- 删除 ----------------------------- */
const handleDelete = (row: NodeGroup) => {
  ElMessageBox.confirm(`确认删除分组「${row.name}」吗？`, "提示", {
    type: "warning"
  })
    .then(async () => {
      try {
        const res: any = await deleteNodeGroup(row.id);
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
    <el-form :inline="true" class="mb-2">
      <el-form-item label="关键字">
        <el-input
          v-model="searchKeyword"
          placeholder="分组名称"
          clearable
          style="width: 220px"
          @keyup.enter="onSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="onReset">重置</el-button>
        <el-button
          v-perms="['resource:manage']"
          type="success"
          @click="openCreate"
        >
          新建分组
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
      width="480px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入分组名称" />
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
  </div>
</template>
