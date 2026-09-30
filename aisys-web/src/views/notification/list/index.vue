<script setup lang="ts">
import { h, reactive, ref, computed, onMounted } from "vue";
import { ElTag, ElButton, ElEmpty, ElMessage, ElMessageBox } from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import {
  getNotificationList,
  markNotificationRead,
  markAllNotificationsRead,
  type NotificationItem
} from "@/api/notification";

defineOptions({
  name: "NotificationList"
});

const loading = ref(false);
const dataList = ref<NotificationItem[]>([]);
const unreadCount = ref(0);

const pagination = reactive<PaginationProps>({
  total: 0,
  currentPage: 1,
  pageSize: 10,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const queryParams = reactive({
  title: "",
  type: "",
  isRead: ""
});

/** 级别标签类型映射 */
const levelTagType = (level?: string) => {
  switch ((level || "").toUpperCase()) {
    case "URGENT":
    case "CRITICAL":
      return "danger";
    case "HIGH":
    case "WARNING":
      return "warning";
    case "LOW":
    case "INFO":
      return "info";
    default:
      return "primary";
  }
};

/** 类型展示文案 */
const typeText = (type?: string) => {
  if (!type) return "-";
  return type;
};

/** 时间格式化 */
const formatTime = (val?: string) => {
  if (!val) return "-";
  return val.replace("T", " ").split(".")[0];
};

/** 是否已读（兼容 isRead/read 字段） */
const isRead = (row: NotificationItem) =>
  row.isRead === true || row.read === true;

const hasUnread = computed(() => unreadCount.value > 0);

const columns: TableColumnList = [
  { label: "序号", type: "index", width: 60, align: "center" },
  {
    label: "标题",
    prop: "title",
    minWidth: 200,
    showOverflowTooltip: true
  },
  {
    label: "内容",
    prop: "content",
    minWidth: 240,
    showOverflowTooltip: true,
    cellRenderer: ({ row }) => row.content || "-"
  },
  {
    label: "类型",
    prop: "type",
    width: 120,
    align: "center",
    cellRenderer: ({ row }) =>
      row.type
        ? h(ElTag, { type: "info", effect: "plain" }, () => typeText(row.type))
        : "-"
  },
  {
    label: "级别",
    prop: "level",
    width: 100,
    align: "center",
    cellRenderer: ({ row }) =>
      row.level
        ? h(ElTag, { type: levelTagType(row.level) }, () => row.level)
        : "-"
  },
  {
    label: "时间",
    prop: "createdAt",
    width: 180,
    align: "center",
    formatter: ({ createdAt }) => formatTime(createdAt)
  },
  {
    label: "状态",
    width: 90,
    align: "center",
    cellRenderer: ({ row }) =>
      isRead(row)
        ? h(ElTag, { type: "success" }, () => "已读")
        : h(ElTag, { type: "danger" }, () => "未读")
  },
  {
    label: "操作",
    width: 120,
    align: "center",
    fixed: "right",
    cellRenderer: ({ row }) =>
      isRead(row)
        ? h("span", { class: "text-gray-400" }, "—")
        : h(
            ElButton,
            { link: true, type: "primary", onClick: () => handleMarkRead(row) },
            () => "标记已读"
          )
  }
];

/** 加载列表 */
const loadData = async () => {
  loading.value = true;
  try {
    const res = await getNotificationList({
      page: pagination.currentPage,
      size: pagination.pageSize,
      ...queryParams
    });
    if (res.code === 0 && res.data) {
      dataList.value = (res.data.items || []) as NotificationItem[];
      pagination.total = res.data.total ?? 0;
    } else {
      ElMessage.error(res.message || "加载通知列表失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "加载通知列表失败");
  } finally {
    loading.value = false;
  }
};

/** 刷新未读数 */
const loadUnreadCount = async () => {
  try {
    const res = await getUnreadCount();
    if (res.code === 0) {
      // 兼容 data 为数字或对象 { count: number }
      const d = res.data;
      unreadCount.value =
        typeof d === "number"
          ? d
          : (d && (d.count ?? d.unreadCount ?? 0)) || 0;
    }
  } catch (e) {
    // 静默处理，未读数不影响主流程
  }
};

/** 搜索 */
const handleSearch = () => {
  pagination.currentPage = 1;
  loadData();
};

/** 重置 */
const handleReset = () => {
  queryParams.title = "";
  queryParams.type = "";
  queryParams.isRead = "";
  pagination.currentPage = 1;
  loadData();
};

/** 标记单条已读 */
const handleMarkRead = async (row: NotificationItem) => {
  if (isRead(row)) return;
  try {
    const res = await markNotificationRead(row.id);
    if (res.code === 0) {
      ElMessage.success("已标记为已读");
      await Promise.all([loadData(), loadUnreadCount()]);
    } else {
      ElMessage.error(res.message || "操作失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "操作失败");
  }
};

/** 全部已读 */
const handleMarkAllRead = async () => {
  if (!hasUnread.value) {
    ElMessage.info("暂无未读通知");
    return;
  }
  try {
    await ElMessageBox.confirm("确定将所有通知标记为已读吗？", "提示", {
      type: "warning"
    });
  } catch {
    return; // 用户取消
  }
  try {
    const res = await markAllNotificationsRead();
    if (res.code === 0) {
      ElMessage.success("已全部标记为已读");
      await Promise.all([loadData(), loadUnreadCount()]);
    } else {
      ElMessage.error(res.message || "操作失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "操作失败");
  }
};

/** 分页变化 */
const onSizeChange = (val: number) => {
  pagination.pageSize = val;
  pagination.currentPage = 1;
  loadData();
};

const onCurrentChange = (val: number) => {
  pagination.currentPage = val;
  loadData();
};

onMounted(() => {
  loadData();
  loadUnreadCount();
});
</script>

<template>
  <div class="main">
    <!-- 搜索表单 -->
    <el-form
      :inline="true"
      :model="queryParams"
      class="search-form bg-bg_color w-full pl-8 pt-3 overflow-auto"
    >
      <el-form-item label="标题：" prop="title">
        <el-input
          v-model="queryParams.title"
          placeholder="请输入通知标题"
          clearable
          class="!w-48"
          @keyup.enter="handleSearch"
        />
      </el-form-item>
      <el-form-item label="类型：" prop="type">
        <el-input
          v-model="queryParams.type"
          placeholder="请输入类型"
          clearable
          class="!w-40"
        />
      </el-form-item>
      <el-form-item label="状态：" prop="isRead">
        <el-select
          v-model="queryParams.isRead"
          placeholder="请选择状态"
          clearable
          class="!w-40"
        >
          <el-option label="未读" value="false" />
          <el-option label="已读" value="true" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="handleSearch">
          搜索
        </el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 工具栏 -->
    <div class="flex justify-between items-center w-full px-6 py-3 bg-bg_color">
      <div class="flex items-center gap-2">
        <span class="text-base font-bold">通知列表</span>
        <el-badge
          v-if="hasUnread"
          :value="unreadCount"
          :max="99"
          class="ml-2"
        />
      </div>
      <el-button
        type="success"
        plain
        :disabled="!hasUnread"
        @click="handleMarkAllRead"
      >
        全部标记已读
      </el-button>
    </div>

    <!-- 通知表格 -->
    <pure-table
      row-key="id"
      border
      stripe
      table-layout="auto"
      :loading="loading"
      :data="dataList"
      :columns="columns"
      :pagination="pagination"
      :header-cell-style="{
        background: 'var(--el-fill-color-light)',
        color: 'var(--el-text-color-primary)'
      }"
      @page-size-change="onSizeChange"
      @page-current-change="onCurrentChange"
    >
      <template #empty>
        <el-empty description="暂无通知" />
      </template>
    </pure-table>
  </div>
</template>

<style lang="scss" scoped>
.search-form {
  :deep(.el-form-item) {
    margin-bottom: 12px;
  }
}

.main {
  display: flex;
  flex-direction: column;
}
</style>
