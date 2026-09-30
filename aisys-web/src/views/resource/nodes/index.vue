<script setup lang="ts">
import { ref, reactive, onMounted } from "vue";
import { useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import { message } from "@/utils/message";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import ServerLine from "~icons/ri/server-line";
import {
  getNodeList,
  getNodeDetail,
  getNodeMetrics,
  updateNodeLabels,
  updateNode,
  deleteNode,
  enterMaintenance,
  exitMaintenance,
  executeCommand,
  listNodeGroups,
  type ComputeNode,
  type NodeStatus
} from "@/api/resource";

defineOptions({
  name: "ResourceNodes"
});

const route = useRoute();

/** 状态 → tag 类型与文案 */
const statusMeta: Record<
  NodeStatus,
  { type: "success" | "info" | "warning"; text: string; color: string }
> = {
  online: { type: "success", text: "在线", color: "#67c23a" },
  offline: { type: "info", text: "离线", color: "#909399" },
  maintenance: { type: "warning", text: "维护中", color: "#e6a23c" }
};

const getStatusMeta = (s?: NodeStatus) =>
  statusMeta[s] ?? statusMeta.offline;

/** 分页参数 */
const pagination = reactive<PaginationProps>({
  total: 0,
  currentPage: 1,
  pageSize: 12,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const searchKeyword = ref("");
const statusFilter = ref<NodeStatus | "">("");
const groupFilter = ref<number | "">("");
const groups = ref<Array<{ id: any; name: string }>>([]);
const loading = ref(false);
const dataList = ref<ComputeNode[]>([]);

/** 取出节点的标签列表（兼容对象与数组两种结构） */
const toLabelPairs = (labels?: any): Array<{ key: string; value: string }> => {
  if (!labels) return [];
  if (Array.isArray(labels)) {
    return labels.map((l: any) => ({
      key: String(l.key ?? ""),
      value: String(l.value ?? "")
    }));
  }
  if (typeof labels === "object") {
    return Object.entries(labels).map(([key, value]) => ({
      key,
      value: String(value ?? "")
    }));
  }
  return [];
};

/** 取出 GPU 描述（兼容数组对象与字符串） */
const gpuSummary = (gpu?: any): string => {
  if (!gpu) return "—";
  if (typeof gpu === "string") return gpu;
  if (Array.isArray(gpu)) {
    return gpu
      .map((g: any) => {
        if (typeof g === "string") return g;
        const model = g.model ?? g.name ?? g.type ?? "";
        const count = g.count ?? g.num ?? "";
        return count ? `${count} × ${model}` : model;
      })
      .filter(Boolean)
      .join(" / ");
  }
  return JSON.stringify(gpu);
};

/** 渲染 CPU 信息（{model, cores, threads} 对象 → 人类可读） */
const cpuText = (cpu?: any): string => {
  if (!cpu) return "—";
  if (typeof cpu === "string") return cpu;
  const model = cpu.model ?? cpu.name ?? "";
  const cores = cpu.cores ?? "";
  const threads = cpu.threads ?? "";
  const parts: string[] = [];
  if (model) parts.push(String(model));
  const spec: string[] = [];
  if (cores !== "" && cores != null) spec.push(`${cores}核`);
  if (threads !== "" && threads != null) spec.push(`${threads}线程`);
  if (spec.length) parts.push(`(${spec.join("/")})`);
  return parts.length ? parts.join(" ") : JSON.stringify(cpu);
};

const fmtBytes = (b?: number) => {
  if (b == null || Number.isNaN(b)) return "—";
  if (b === 0) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(u.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
  return `${(b / Math.pow(1024, i)).toFixed(1)} ${u[i]}`;
};

const loadList = async () => {
  loading.value = true;
  try {
    const res = await getNodeList({
      keyword: searchKeyword.value || undefined,
      status: statusFilter.value || undefined,
      groupId: groupFilter.value || undefined,
      page: pagination.currentPage,
      size: pagination.pageSize
    });
    if (res?.code === 0 && res.data) {
      dataList.value = (res.data.items ?? []) as ComputeNode[];
      pagination.total = res.data.total ?? 0;
    } else {
      ElMessage.error(res?.message ?? "获取节点列表失败");
    }
  } catch (e) {
    console.error(e);
    ElMessage.error("获取节点列表失败");
  } finally {
    loading.value = false;
  }
};

const loadGroups = async () => {
  try {
    const res = await listNodeGroups();
    if (res?.code === 0) {
      const d = res.data;
      const arr: any[] = Array.isArray(d) ? d : d?.items ?? d?.list ?? [];
      groups.value = arr.map((g: any) => ({ id: g.id, name: g.name }));
    }
  } catch (e) {
    console.error(e);
  }
};

const onSearch = () => {
  pagination.currentPage = 1;
  loadList();
};

const onReset = () => {
  searchKeyword.value = "";
  statusFilter.value = "";
  groupFilter.value = "";
  pagination.currentPage = 1;
  loadList();
};

const onPageSizeChange = (size: number) => {
  pagination.pageSize = size;
  pagination.currentPage = 1;
  loadList();
};

const onCurrentChange = (current: number) => {
  pagination.currentPage = current;
  loadList();
};

/* ------------------------- 维护模式切换 ------------------------- */

const togglingId = ref<string | number | null>(null);

const onToggleMaintenance = async (node: ComputeNode) => {
  const target = node.status === "maintenance" ? "exit" : "enter";
  const actionText = target === "enter" ? "进入维护" : "退出维护";
  try {
    await ElMessageBox.confirm(
      `确认对节点「${node.nodeName || node.name}」${actionText}模式？`,
      "提示",
      { type: "warning" }
    );
  } catch {
    return;
  }
  togglingId.value = node.id;
  try {
    const fn = target === "enter" ? enterMaintenance : exitMaintenance;
    const res = await fn(node.id);
    if (res?.code === 0) {
      message(`${actionText}成功`, { type: "success" });
      await loadList();
    } else {
      ElMessage.error(res?.message ?? `${actionText}失败`);
    }
  } catch (e) {
    console.error(e);
    ElMessage.error(`${actionText}失败`);
  } finally {
    togglingId.value = null;
  }
};

/* ------------------------- 标签编辑 ------------------------- */

const labelDialogVisible = ref(false);
const labelForm = reactive<{ id: any; labels: Array<{ key: string; value: string }> }>({
  id: null,
  labels: []
});
const labelSaving = ref(false);

const openLabelDialog = async (node: ComputeNode) => {
  labelForm.id = node.id;
  let pairs = toLabelPairs(node.labels);
  if (pairs.length === 0) {
    try {
      const res = await getNodeDetail(node.id);
      if (res?.code === 0) {
        pairs = toLabelPairs((res.data as any)?.labels);
      }
    } catch (e) {
      console.error(e);
    }
  }
  labelForm.labels =
    pairs.length > 0 ? pairs.map(p => ({ ...p })) : [{ key: "", value: "" }];
  labelDialogVisible.value = true;
};

const addLabelRow = () => {
  labelForm.labels.push({ key: "", value: "" });
};

const removeLabelRow = (index: number) => {
  labelForm.labels.splice(index, 1);
};

const submitLabels = async () => {
  const map: Record<string, string> = {};
  for (const row of labelForm.labels) {
    const key = row.key.trim();
    if (!key) continue;
    map[key] = row.value.trim();
  }
  labelSaving.value = true;
  try {
    const res = await updateNodeLabels(labelForm.id, { labels: map });
    if (res?.code === 0) {
      message("标签更新成功", { type: "success" });
      labelDialogVisible.value = false;
      await loadList();
    } else {
      ElMessage.error(res?.message ?? "标签更新失败");
    }
  } catch (e) {
    console.error(e);
    ElMessage.error("标签更新失败");
  } finally {
    labelSaving.value = false;
  }
};

/* ------------------------- 详情 / 删除 / 分配分组 / 命令 ------------------------- */
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailData = ref<any>(null);
const metricsData = ref<any>(null);
const groupForm = reactive<{ id: any; nodeGroupId: any }>({
  id: null,
  nodeGroupId: ""
});
const groupSaving = ref(false);
const commandForm = reactive<{ id: any; command: string; timeout: number }>({
  id: null,
  command: "",
  timeout: 30
});
const commandResult = ref<any>(null);
const commandRunning = ref(false);
const deletingId = ref<any>(null);

const openDetail = async (node: ComputeNode) => {
  detailVisible.value = true;
  detailLoading.value = true;
  detailData.value = node;
  metricsData.value = null;
  commandResult.value = null;
  commandForm.id = node.id;
  commandForm.command = "";
  groupForm.id = node.id;
  groupForm.nodeGroupId = (node as any).nodeGroupId ?? "";
  try {
    const [d, m] = await Promise.all([
      getNodeDetail(node.id),
      getNodeMetrics(node.id).catch(() => null)
    ]);
    if (d?.code === 0) {
      detailData.value = { ...node, ...(d.data as any) };
      groupForm.nodeGroupId = (d.data as any)?.nodeGroupId ?? "";
    }
    if (m?.code === 0) metricsData.value = m.data;
  } catch (e) {
    console.error(e);
  } finally {
    detailLoading.value = false;
  }
};

const onAssignGroup = async () => {
  groupSaving.value = true;
  try {
    const res = await updateNode(groupForm.id, {
      nodeGroupId: groupForm.nodeGroupId === "" ? null : groupForm.nodeGroupId
    });
    if (res?.code === 0) {
      message("分组已更新", { type: "success" });
      await loadList();
    } else {
      ElMessage.error(res?.message ?? "分组更新失败");
    }
  } catch (e) {
    console.error(e);
    ElMessage.error("分组更新失败");
  } finally {
    groupSaving.value = false;
  }
};

const onRunCommand = async () => {
  if (!commandForm.command.trim()) {
    ElMessage.warning("请输入命令");
    return;
  }
  commandRunning.value = true;
  commandResult.value = null;
  try {
    const res = await executeCommand(commandForm.id, {
      command: commandForm.command,
      timeout: commandForm.timeout
    });
    if (res?.code === 0) {
      commandResult.value = res.data;
    } else {
      ElMessage.error(res?.message ?? "命令执行失败");
    }
  } catch (e: any) {
    console.error(e);
    ElMessage.error(e?.message ?? "命令执行失败");
  } finally {
    commandRunning.value = false;
  }
};

const onDeleteNode = async (node: ComputeNode) => {
  try {
    await ElMessageBox.confirm(
      `确认删除节点「${node.nodeName || node.name}」吗？此操作不可恢复。`,
      "危险操作",
      { type: "warning" }
    );
  } catch {
    return;
  }
  deletingId.value = node.id;
  try {
    const res = await deleteNode(node.id);
    if (res?.code === 0) {
      message("节点已删除", { type: "success" });
      await loadList();
    } else {
      ElMessage.error(res?.message ?? "删除失败");
    }
  } catch (e) {
    console.error(e);
    ElMessage.error("删除失败");
  } finally {
    deletingId.value = null;
  }
};

onMounted(() => {
  loadGroups();
  const qid = Number(route.query.groupId);
  if (qid) groupFilter.value = qid;
  loadList();
});
</script>

<template>
  <div class="p-4">
    <!-- 搜索 / 工具栏 -->
    <div class="flex justify-between items-center mb-4 flex-wrap gap-2">
      <div class="flex items-center gap-2 flex-wrap">
        <el-input
          v-model="searchKeyword"
          placeholder="节点名称 / Agent ID / IP"
          clearable
          class="!w-64"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <el-select
          v-model="statusFilter"
          placeholder="状态"
          clearable
          class="!w-32"
          @change="onSearch"
        >
          <el-option label="在线" value="online" />
          <el-option label="离线" value="offline" />
          <el-option label="维护中" value="maintenance" />
        </el-select>
        <el-select
          v-model="groupFilter"
          placeholder="分组"
          clearable
          class="!w-40"
          @change="onSearch"
        >
          <el-option
            v-for="g in groups"
            :key="g.id"
            :label="g.name"
            :value="g.id"
          />
        </el-select>
        <el-button type="primary" @click="onSearch">搜索</el-button>
        <el-button @click="onReset">重置</el-button>
      </div>
      <el-button :icon="useRenderIcon(Refresh)" @click="loadList">
        刷新
      </el-button>
    </div>

    <!-- 卡片网格 -->
    <div v-loading="loading" class="min-h-60">
      <template v-if="dataList.length > 0">
        <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
          <div
            v-for="node in dataList"
            :key="node.id"
            class="el-card node-card p-4 rounded-lg bg-bg_color"
            style="box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08)"
          >
            <!-- 头部：名称 + 状态 -->
            <div class="flex justify-between items-start mb-3">
              <div class="flex items-center gap-2 min-w-0">
                <el-icon class="text-xl shrink-0">
                  <ServerLine />
                </el-icon>
                <span class="font-bold truncate" :title="node.nodeName || node.name">
                  {{ node.nodeName || node.name }}
                </span>
              </div>
              <el-tag
                :type="getStatusMeta(node.status).type"
                size="small"
                effect="light"
              >
                <span
                  class="inline-block w-1.5 h-1.5 rounded-full mr-1"
                  :style="{ background: getStatusMeta(node.status).color }"
                />
                {{ getStatusMeta(node.status).text }}
              </el-tag>
            </div>

            <!-- 信息行 -->
            <div class="text-sm leading-7 text-gray-600 break-all">
              <div>
                <span class="text-gray-500">Agent ID：</span>
                <span>{{ node.agentId || "—" }}</span>
              </div>
              <div>
                <span class="text-gray-500">IP：</span>
                <span>{{ node.ipAddress || node.ip || "—" }}</span>
              </div>
              <div>
                <span class="text-gray-500">硬件：</span>
                <span>
                  {{ node.cpuCores || 0 }} 核 CPU
                  · {{ node.gpuCount || 0 }} GPU
                  · 内存 {{ fmtBytes(node.totalMemory) }}
                </span>
              </div>
              <div v-if="node.runningTasks != null">
                <span class="text-gray-500">运行任务：</span>
                <span>{{ node.runningTasks }}</span>
              </div>
            </div>

            <!-- 标签 -->
            <div class="mt-3 min-h-6">
              <template v-if="toLabelPairs(node.labels).length > 0">
                <el-tag
                  v-for="(lp, idx) in toLabelPairs(node.labels)"
                  :key="idx"
                  size="small"
                  type="info"
                  class="mr-1 mb-1"
                >
                  {{ lp.key }}{{ lp.value ? `: ${lp.value}` : "" }}
                </el-tag>
              </template>
              <span v-else class="text-xs text-gray-400">暂无标签</span>
            </div>

            <!-- 操作 -->
            <div class="mt-3 pt-3 border-t border-gray-100 flex justify-end gap-2 flex-wrap">
              <el-button link type="primary" size="small" @click="openDetail(node)">
                详情
              </el-button>
              <el-button link type="primary" size="small" @click="openLabelDialog(node)">
                标签
              </el-button>
              <el-button
                link
                :type="node.status === 'maintenance' ? 'success' : 'warning'"
                size="small"
                :loading="togglingId === node.id"
                @click="onToggleMaintenance(node)"
              >
                {{ node.status === "maintenance" ? "退出维护" : "维护" }}
              </el-button>
              <el-button
                link
                type="danger"
                size="small"
                :loading="deletingId === node.id"
                @click="onDeleteNode(node)"
              >
                删除
              </el-button>
            </div>
          </div>
        </div>
      </template>
      <template v-else-if="!loading">
        <el-empty description="暂无计算节点" />
      </template>
    </div>

    <!-- 分页 -->
    <div class="flex justify-end mt-4">
      <el-pagination
        background
        :current-page="pagination.currentPage"
        :page-size="pagination.pageSize"
        :page-sizes="[12, 24, 48]"
        :total="pagination.total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="onPageSizeChange"
        @current-change="onCurrentChange"
      />
    </div>

    <!-- 标签编辑弹窗 -->
    <el-dialog
      v-model="labelDialogVisible"
      title="编辑节点标签"
      width="560px"
      destroy-on-close
    >
      <div class="space-y-3">
        <div
          v-for="(row, idx) in labelForm.labels"
          :key="idx"
          class="flex items-center gap-2"
        >
          <el-input
            v-model="row.key"
            placeholder="标签键 key"
            class="flex-1"
          />
          <span class="text-gray-400">:</span>
          <el-input
            v-model="row.value"
            placeholder="标签值 value"
            class="flex-1"
          />
          <el-button
            link
            type="danger"
            @click="removeLabelRow(idx)"
          >
            删除
          </el-button>
        </div>
      </div>
      <div class="mt-3">
        <el-button link type="primary" @click="addLabelRow">
          + 新增一行标签
        </el-button>
      </div>
      <template #footer>
        <el-button @click="labelDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="labelSaving" @click="submitLabels">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- 节点详情弹窗 -->
    <el-dialog
      v-model="detailVisible"
      title="节点详情"
      width="720px"
      destroy-on-close
    >
      <div v-loading="detailLoading">
        <el-descriptions v-if="detailData" :column="2" border>
          <el-descriptions-item label="节点名称">
            {{ detailData.nodeName || detailData.name || "—" }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getStatusMeta(detailData.status).type" size="small">
              {{ getStatusMeta(detailData.status).text }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="Agent ID">
            {{ detailData.agentId || "—" }}
          </el-descriptions-item>
          <el-descriptions-item label="IP">
            {{ detailData.ipAddress || detailData.ip || "—" }}
          </el-descriptions-item>
          <el-descriptions-item label="Agent 版本">
            {{ detailData.agentVersion || "—" }}
          </el-descriptions-item>
          <el-descriptions-item label="操作系统">
            {{ detailData.osInfo || "—" }}
          </el-descriptions-item>
          <el-descriptions-item label="CPU">
            {{ cpuText(detailData.cpuInfo) }}
          </el-descriptions-item>
          <el-descriptions-item label="GPU">
            {{ gpuSummary(detailData.gpuInfo) }}
          </el-descriptions-item>
          <el-descriptions-item label="内存 / 磁盘">
            {{ fmtBytes(detailData.totalMemory) }} / {{ fmtBytes(detailData.totalDisk) }}
          </el-descriptions-item>
          <el-descriptions-item label="最近心跳">
            {{ detailData.lastHeartbeatAt || "—" }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ detailData.createdAt || "—" }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 资源容量快照 -->
        <div v-if="metricsData" class="mt-4">
          <div class="text-sm font-bold mb-2">资源容量</div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="GPU 总/空闲">
              {{ metricsData.totalGpu }} / {{ metricsData.freeGpu }}
            </el-descriptions-item>
            <el-descriptions-item label="CPU 总/空闲">
              {{ metricsData.totalCpu }} / {{ metricsData.freeCpu }}
            </el-descriptions-item>
            <el-descriptions-item label="内存空闲">
              {{ fmtBytes(metricsData.freeMemoryBytes) }}
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 分配分组 -->
        <div class="mt-4">
          <div class="text-sm font-bold mb-2">分配分组</div>
          <div class="flex items-center gap-2">
            <el-select
              v-model="groupForm.nodeGroupId"
              placeholder="选择节点分组"
              clearable
              class="!w-56"
            >
              <el-option
                v-for="g in groups"
                :key="g.id"
                :label="g.name"
                :value="g.id"
              />
            </el-select>
            <el-button type="primary" size="small" :loading="groupSaving" @click="onAssignGroup">
              保存分组
            </el-button>
          </div>
        </div>

        <!-- 远程命令执行 -->
        <div class="mt-4">
          <div class="text-sm font-bold mb-2">远程命令执行</div>
          <div class="flex items-center gap-2 mb-2">
            <el-input
              v-model="commandForm.command"
              placeholder="例如：nvidia-smi 或 df -h"
              class="flex-1"
              @keyup.enter="onRunCommand"
            />
            <el-input-number
              v-model="commandForm.timeout"
              :min="1"
              :max="300"
              controls-position="right"
              class="!w-28"
            />
            <span class="text-xs text-gray-400">秒</span>
            <el-button
              type="primary"
              :loading="commandRunning"
              @click="onRunCommand"
            >
              执行
            </el-button>
          </div>
          <div v-if="commandResult" class="mt-2">
            <el-tag
              :type="commandResult.exitCode === 0 ? 'success' : 'danger'"
              size="small"
            >
              退出码 {{ commandResult.exitCode }}{{ commandResult.timedOut ? "（超时）" : "" }}
            </el-tag>
            <pre v-if="commandResult.stdout" class="cmd-out">{{ commandResult.stdout }}</pre>
            <pre v-if="commandResult.stderr" class="cmd-err">{{ commandResult.stderr }}</pre>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button
          v-if="detailData"
          :type="detailData.status === 'maintenance' ? 'success' : 'warning'"
          @click="detailVisible = false; onToggleMaintenance(detailData)"
        >
          {{ detailData.status === "maintenance" ? "退出维护" : "进入维护" }}
        </el-button>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.node-card {
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}
.node-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12) !important;
}
.cmd-out {
  margin-top: 8px;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.cmd-err {
  margin-top: 8px;
  padding: 8px;
  background: #fef0f0;
  color: #f56c6c;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
