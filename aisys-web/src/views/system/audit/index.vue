<script setup lang="ts">
import { reactive, ref, onMounted } from "vue";
import { ElMessage } from "element-plus";
import { getAuditLogPage, type AuditLogQuery } from "@/api/system";

defineOptions({ name: "SystemAudit" });

/* ----------------------------- 列表数据与分页 ----------------------------- */
const loading = ref(false);
const dataList = ref<any[]>([]);
const total = ref(0);

const dateRange = ref<[string, string] | null>(null);

const query = reactive<AuditLogQuery>({
  page: 1,
  size: 20,
  actorType: "",
  resource: "",
  action: "",
  startTime: "",
  endTime: ""
});

const fetchList = async () => {
  loading.value = true;
  try {
    // 处理时间区间
    if (dateRange.value && dateRange.value.length === 2) {
      query.startTime = dateRange.value[0];
      query.endTime = dateRange.value[1];
    } else {
      query.startTime = "";
      query.endTime = "";
    }
    const params: AuditLogQuery = { page: query.page, size: query.size };
    if (query.actorType) params.actorType = query.actorType;
    if (query.resource) params.resource = query.resource;
    if (query.action) params.action = query.action;
    if (query.startTime) params.startTime = query.startTime;
    if (query.endTime) params.endTime = query.endTime;

    const res: any = await getAuditLogPage(params);
    if (res?.code === 0) {
      const d = res.data ?? {};
      dataList.value = d.items ?? [];
      total.value = d.total ?? 0;
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
  query.actorType = "";
  query.resource = "";
  query.action = "";
  query.startTime = "";
  query.endTime = "";
  dateRange.value = null;
  onSearch();
};

const handleSizeChange = (val: number) => {
  query.size = val;
  query.page = 1;
  fetchList();
};

const handleCurrentChange = (val: number) => {
  query.page = val;
  fetchList();
};

/* --------------------------- 行内详情展开 ---------------------------- */
const expandedRows = ref<Array<any>>([]);
const toggleExpand = (row: any) => {
  const idx = expandedRows.value.indexOf(row.id);
  if (idx >= 0) expandedRows.value.splice(idx, 1);
  else expandedRows.value.push(row.id);
};

const actorTypeTag = (t: string) => {
  switch (t) {
    case "USER":
      return "primary";
    case "SYSTEM":
      return "info";
    case "SERVICE":
      return "warning";
    default:
      return "info";
  }
};

// 后端 row 无顶层 success 字段，成功/失败需从 detail JSON 解析（detail 形如 {"success":true,...}）
const isSuccess = (row: any) => {
  if (row && typeof row.success === "boolean") return row.success;
  if (row && typeof row.detail === "string") {
    try {
      const d = JSON.parse(row.detail);
      return !(d && d.success === false);
    } catch {
      return true;
    }
  }
  return true;
};

onMounted(() => {
  fetchList();
});
</script>

<template>
  <div class="p-4">
    <!-- 查询区 -->
    <el-form :inline="true" :model="query" class="mb-2">
      <el-form-item label="主体类型">
        <el-select
          v-model="query.actorType"
          placeholder="全部"
          clearable
          style="width: 140px"
        >
          <el-option label="用户" value="USER" />
          <el-option label="系统" value="SYSTEM" />
          <el-option label="服务" value="SERVICE" />
        </el-select>
      </el-form-item>
      <el-form-item label="资源">
        <el-input
          v-model="query.resource"
          placeholder="如 user / tenant"
          clearable
          style="width: 160px"
        />
      </el-form-item>
      <el-form-item label="操作">
        <el-input
          v-model="query.action"
          placeholder="如 create / delete"
          clearable
          style="width: 160px"
        />
      </el-form-item>
      <el-form-item label="时间范围">
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 360px"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="onSearch">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 表格 -->
    <el-table
      v-loading="loading"
      :data="dataList"
      border
      stripe
      style="width: 100%"
    >
      <el-table-column prop="id" label="ID" width="80" align="center" />
      <el-table-column label="时间" min-width="170">
        <template #default="{ row }">
          {{ row.createdAt || "-" }}
        </template>
      </el-table-column>
      <el-table-column label="主体类型" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="actorTypeTag(row.actorType)" effect="plain">
            {{ row.actorType || "-" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="userId"
        label="主体ID"
        min-width="120"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{ row.userId }}{{ row.username ? " (" + row.username + ")" : "" }}
        </template>
      </el-table-column>
      <el-table-column prop="resource" label="资源" min-width="140" />
      <el-table-column prop="resourceId" label="资源ID" min-width="120" show-overflow-tooltip />
      <el-table-column prop="action" label="操作" min-width="120">
        <template #default="{ row }">
          <el-tag type="warning" effect="plain">
            {{ row.action || "-" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="ipAddress"
        label="IP"
        min-width="130"
        show-overflow-tooltip
      />
      <el-table-column label="结果" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="isSuccess(row) ? 'success' : 'danger'">
            {{ isSuccess(row) ? "成功" : "失败" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="详情" width="90" align="center" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            size="small"
            @click="toggleExpand(row)"
          >
            {{ expandedRows.includes(row.id) ? "收起" : "查看" }}
          </el-button>
        </template>
      </el-table-column>

      <!-- 展开详情行 -->
      <template #append>
        <!-- 仅作占位，详情用下方 inline 展示 -->
      </template>
    </el-table>

    <!-- 详情抽屉式展示（避免 el-table expand 与 stripe 冲突，独立区域） -->
    <el-drawer
      :model-value="expandedRows.length > 0"
      title="日志详情"
      size="40%"
      @update:model-value="(v: boolean) => { if (!v) expandedRows.value = []; }"
    >
      <template v-if="dataList.length">
        <div
          v-for="row in dataList.filter(r =>
            expandedRows.includes(r.id)
          )"
          :key="row.id"
          class="audit-detail"
        >
          <el-descriptions :column="1" border>
            <el-descriptions-item label="ID">{{ row.id }}</el-descriptions-item>
            <el-descriptions-item label="时间">{{
              row.createdAt
            }}</el-descriptions-item>
            <el-descriptions-item label="主体类型">{{
              row.actorType
            }}</el-descriptions-item>
            <el-descriptions-item label="主体ID">{{
              row.userId
            }}{{ row.username ? " (" + row.username + ")" : "" }}</el-descriptions-item>
            <el-descriptions-item label="资源">{{
              row.resource
            }}</el-descriptions-item>
            <el-descriptions-item label="资源ID">{{
              row.resourceId
            }}</el-descriptions-item>
            <el-descriptions-item label="操作">{{
              row.action
            }}</el-descriptions-item>
            <el-descriptions-item label="IP">{{
              row.ipAddress
            }}</el-descriptions-item>
            <el-descriptions-item label="结果">{{
              isSuccess(row) ? "成功" : "失败"
            }}</el-descriptions-item>
            <el-descriptions-item label="消息">{{
              row.message || "-"
            }}</el-descriptions-item>
            <el-descriptions-item label="请求详情">
              <pre class="detail-pre">{{
                typeof row.detail === "string"
                  ? row.detail
                  : JSON.stringify(row.detail ?? row.request ?? {}, null, 2)
              }}</pre>
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </template>
    </el-drawer>

    <!-- 分页 -->
    <div class="flex justify-end mt-4">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[20, 50, 100, 200]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>
  </div>
</template>

<style scoped>
.detail-pre {
  margin: 0;
  padding: 8px;
  max-height: 240px;
  overflow: auto;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.audit-detail + .audit-detail {
  margin-top: 16px;
}
</style>
