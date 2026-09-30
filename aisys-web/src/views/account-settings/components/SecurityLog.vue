<script setup lang="ts">
import dayjs from "dayjs";
import { getMineLogs } from "@/api/user";
import { reactive, ref, onMounted } from "vue";
import { deviceDetection } from "@pureadmin/utils";
import type { PaginationProps } from "@pureadmin/table";

defineOptions({
  name: "SecurityLog"
});

const loading = ref(true);
const dataList = ref<any[]>([]);
const pagination = reactive<PaginationProps>({
  total: 0,
  pageSize: 10,
  currentPage: 1,
  background: true,
  layout: "total, prev, pager, next"
});

// 列对齐后端 AuditLogDtos.Response：action/resource/ipAddress/userAgent/createdAt
const columns: TableColumnList = [
  { label: "时间", prop: "createdAt", minWidth: 170, formatter: ({ createdAt }) =>
      createdAt ? dayjs(createdAt).format("YYYY-MM-DD HH:mm:ss") : "-" },
  { label: "操作", prop: "action", minWidth: 100 },
  { label: "资源", prop: "resource", minWidth: 100 },
  {
    label: "结果",
    prop: "detail",
    minWidth: 80,
    align: "center",
    cellRenderer: ({ row }) => {
      let ok = true;
      try { ok = !(JSON.parse(row.detail || "{}").success === false); } catch { /* ignore */ }
      return ok ? "成功" : "失败";
    }
  },
  { label: "IP", prop: "ipAddress", minWidth: 140, showOverflowTooltip: true },
  { label: "User-Agent", prop: "userAgent", minWidth: 180, showOverflowTooltip: true }
];

async function onSearch() {
  loading.value = true;
  try {
    const { code, data }: any = await getMineLogs({
      page: pagination.currentPage,
      size: pagination.pageSize
    });
    if (code === 0) {
      // 后端返回 PageResult：data.items / data.total / data.page / data.size
      dataList.value = data?.items ?? [];
      pagination.total = data?.total ?? 0;
      pagination.pageSize = data?.size ?? pagination.pageSize;
      pagination.currentPage = data?.page ?? pagination.currentPage;
    }
  } finally {
    loading.value = false;
  }
}

const onSizeChange = (v: number) => {
  pagination.pageSize = v;
  pagination.currentPage = 1;
  onSearch();
};
const onCurrentChange = (v: number) => {
  pagination.currentPage = v;
  onSearch();
};

onMounted(() => {
  onSearch();
});
</script>

<template>
  <div :class="['min-w-45', deviceDetection() ? 'max-w-full' : 'max-w-[70%]']">
    <h3 class="my-8!">安全日志</h3>
    <pure-table
      row-key="id"
      table-layout="auto"
      :loading="loading"
      :data="dataList"
      :columns="columns"
      :pagination="pagination"
      @page-size-change="onSizeChange"
      @page-current-change="onCurrentChange"
    />
  </div>
</template>
