<script setup lang="ts">
import { h, onMounted, reactive, ref } from "vue";
import { ElButton, ElMessage, ElMessageBox, ElTag } from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import {
  createRule,
  deleteRule,
  listRules,
  updateRule
} from "@/api/notification";

defineOptions({ name: "SystemNotificationRules" });

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

const eventTypeTagType = (t: string) => {
  switch (t) {
    case "TASK_FAILED":
    case "ALERT":
      return "danger";
    case "TASK_COMPLETED":
    case "SYSTEM":
      return "success";
    default:
      return "warning";
  }
};

const channelTagType = (c: string) => {
  switch (c) {
    case "email":
      return "primary";
    case "webhook":
      return "success";
    case "sms":
      return "warning";
    default:
      return "info";
  }
};

const columns: TableColumnList = [
  { label: "ID", prop: "id", width: 70, align: "center" },
  {
    label: "事件类型",
    prop: "eventType",
    width: 160,
    align: "center",
    cellRenderer: ({ row }) =>
      h(
        ElTag,
        { type: eventTypeTagType(row.eventType) },
        () => row.eventType || "-"
      )
  },
  { label: "目标类型", prop: "targetType", width: 120, align: "center" },
  {
    label: "通知渠道",
    prop: "channels",
    minWidth: 200,
    cellRenderer: ({ row }) =>
      (row.channels || []).map((c: string) =>
        h(
          ElTag,
          { key: c, type: channelTagType(c), class: "mr-1 mb-1" },
          () => c
        )
      )
  },
  {
    label: "Webhook",
    prop: "webhookUrl",
    minWidth: 200,
    showOverflowTooltip: true,
    formatter: ({ webhookUrl }) => webhookUrl || "-"
  },
  {
    label: "启用",
    prop: "enabled",
    width: 90,
    align: "center",
    cellRenderer: ({ row }) =>
      h(
        ElTag,
        { type: row.enabled ? "success" : "info" },
        () => (row.enabled ? "是" : "否")
      )
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

const fetchList = async () => {
  loading.value = true;
  try {
    const res: any = await listRules({
      page: query.page,
      size: query.size,
      eventType: query.keyword || undefined
    });
    if (res?.code === 0) {
      const d = res.data ?? {};
      // 兼容数组与分页结构
      if (Array.isArray(res.data)) {
        dataList.value = res.data;
        pagination.total = res.data.length;
      } else {
        dataList.value = d.items ?? [];
        pagination.total = d.total ?? 0;
      }
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
const dialogTitle = ref("新建通知规则");
const submitting = ref(false);
const formRef = ref();
const editingId = ref<number | string | null>(null);

const defaultForm = () => ({
  eventType: "TASK_COMPLETED",
  targetType: "USER",
  channels: [] as string[],
  webhookUrl: "",
  enabled: true
});

const form = reactive(defaultForm());

const rules = {
  eventType: [{ required: true, message: "请选择事件类型", trigger: "change" }],
  targetType: [{ required: true, message: "请选择目标类型", trigger: "change" }],
  channels: [
    {
      validator: (_rule: any, value: string[], cb: any) => {
        if (!value || value.length === 0) {
          cb(new Error("请至少选择一个通知渠道"));
        } else {
          cb();
        }
      },
      trigger: "change"
    }
  ]
};

const eventTypes = [
  "TASK_COMPLETED",
  "TASK_FAILED",
  "ALERT",
  "SYSTEM",
  "CUSTOM"
];
const targetTypes = ["USER", "ROLE", "TENANT"];
const channelOptions = ["IN_APP", "EMAIL", "WEBHOOK"];

const openCreate = () => {
  editingId.value = null;
  dialogTitle.value = "新建通知规则";
  Object.assign(form, defaultForm());
  dialogVisible.value = true;
};

const openEdit = (row: any) => {
  editingId.value = row.id;
  dialogTitle.value = "编辑通知规则";
  Object.assign(form, defaultForm(), {
    eventType: row.eventType ?? "TASK_COMPLETED",
    targetType: row.targetType ?? "user",
    channels: row.channels ?? [],
    webhookUrl: row.webhookUrl ?? "",
    enabled: row.enabled ?? true
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
        ? await updateRule(editingId.value, payload)
        : await createRule(payload);
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
  ElMessageBox.confirm(
    `确认删除通知规则「${row.eventType}」吗？`,
    "提示",
    { type: "warning" }
  )
    .then(async () => {
      try {
        const res: any = await deleteRule(row.id);
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
          placeholder="事件类型 / 目标类型"
          clearable
          style="width: 220px"
          @keyup.enter="onSearch"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
        <el-button type="success" @click="openCreate">新建规则</el-button>
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
      width="540px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="事件类型" prop="eventType">
          <el-select v-model="form.eventType" style="width: 100%" placeholder="请选择事件类型">
            <el-option
              v-for="t in eventTypes"
              :key="t"
              :label="t"
              :value="t"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标类型" prop="targetType">
          <el-select v-model="form.targetType" style="width: 100%" placeholder="请选择目标类型">
            <el-option
              v-for="t in targetTypes"
              :key="t"
              :label="t"
              :value="t"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="通知渠道" prop="channels">
          <el-select
            v-model="form.channels"
            multiple
            style="width: 100%"
            placeholder="请选择通知渠道（可多选）"
          >
            <el-option
              v-for="c in channelOptions"
              :key="c"
              :label="c"
              :value="c"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Webhook URL">
          <el-input
            v-model="form.webhookUrl"
            placeholder="选择 webhook 渠道时填写"
          />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
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
