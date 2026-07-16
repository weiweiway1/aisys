<script setup lang="ts">
import { ref, reactive, onMounted, computed } from "vue";
import { useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import type { UploadRequestOptions } from "element-plus";
import {
  browseFiles,
  initiateUploadTask,
  uploadTaskChunk,
  completeUploadTask,
  UPLOAD_CHUNK_SIZE,
  deleteFiles,
  getStoragePools,
  type StorageObject
} from "@/api/storage";
import { getToken, formatToken } from "@/utils/auth";

defineOptions({
  name: "StorageFiles"
});

const loading = ref(false);
const deleting = ref(false);
const rowLoadingKeys = ref(new Set<string>());
const isRowLoading = (key: string) => rowLoadingKeys.value.has(key);
const fileList = ref<StorageObject[]>([]);
const selectedKeys = ref<string[]>([]);

const pools = ref<{ id: number; name: string }[]>([]);

const query = reactive({
  poolId: undefined as number | undefined,
  prefix: ""
});

/** 当前路径面包屑（基于 prefix） */
const crumbs = computed(() => {
  const prefix = query.prefix || "";
  const parts = prefix.split("/").filter(Boolean);
  const list: { label: string; path: string }[] = [
    { label: "根目录", path: "" }
  ];
  let acc = "";
  parts.forEach(p => {
    acc = acc ? `${acc}/${p}` : p;
    list.push({ label: p, path: acc.endsWith("/") ? acc : `${acc}/` });
  });
  return list;
});

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

function formatTime(t?: string): string {
  if (!t) return "-";
  const d = new Date(t);
  if (Number.isNaN(d.getTime())) return t;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(
    d.getDate()
  )} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const route = useRoute();

async function loadPools() {
  try {
    const res: any = await getStoragePools();
    if (res && res.code === 0) {
      const d = res.data;
      const arr: any[] = Array.isArray(d)
        ? d
        : Array.isArray(d?.items)
        ? d.items
        : Array.isArray(d?.list)
        ? d.list
        : [];
      pools.value = arr.map(p => ({ id: p.id, name: p.name }));
      // 优先采用路由 query 中的 poolId（来自存储池详情的"浏览文件"深链）
      const qid = Number(route.query.poolId);
      if (qid && pools.value.some(p => p.id === qid)) {
        query.poolId = qid;
      } else if (!query.poolId && pools.value.length > 0) {
        query.poolId = pools.value[0].id;
      }
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "获取存储池列表失败");
  }
}

async function loadFiles() {
  if (!query.poolId) {
    ElMessage.warning("请先选择存储池");
    return;
  }
  loading.value = true;
  try {
    const res: any = await browseFiles({
      poolId: query.poolId,
      prefix: query.prefix || undefined,
      limit: 200
    });
    if (res && res.code === 0) {
      const d = res.data;
      const arr: any[] = Array.isArray(d)
        ? d
        : Array.isArray(d?.items)
        ? d.items
        : Array.isArray(d?.list)
        ? d.list
        : [];
      fileList.value = arr.map(o => ({
        key: o.key ?? o.name ?? o.path ?? "",
        size: o.size,
        lastModified: o.lastModified ?? o.last_modified ?? o.updatedAt,
        ...o
      }));
    } else {
      ElMessage.error(res?.message || "获取文件列表失败");
      fileList.value = [];
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "获取文件列表失败");
    fileList.value = [];
  } finally {
    loading.value = false;
  }
}

function onSearch() {
  query.prefix = query.prefix || "";
  loadFiles();
}

function onReset() {
  query.prefix = "";
  loadFiles();
}

function onPoolChange() {
  query.prefix = "";
  loadFiles();
}

/** 进入子目录 */
function enterDir(row: StorageObject) {
  if (row.key && row.key.endsWith("/")) {
    query.prefix = row.key;
    loadFiles();
  }
}

function crumbTo(path: string) {
  query.prefix = path;
  loadFiles();
}

function isDir(row: StorageObject): boolean {
  return !!(row.directory || (row.key && row.key.endsWith("/")));
}

async function downloadFile(row: StorageObject) {
  if (!query.poolId) {
    ElMessage.warning("请先选择存储池");
    return;
  }
  rowLoadingKeys.value.add(row.key);
  try {
    // 经后端代理流式下载（规避预签名 URL 内网 host 不可达）
    const t = getToken();
    const headers: Record<string, string> = t?.accessToken
      ? { Authorization: formatToken(t.accessToken) }
      : {};
    const resp = await fetch(
      `/api/v1/files/download?poolId=${query.poolId}&path=${encodeURIComponent(row.key)}`,
      { headers }
    );
    if (!resp.ok) throw new Error("下载失败: " + resp.status);
    const blob = await resp.blob();
    const disposition = resp.headers.get("Content-Disposition") || "";
    const m = disposition.match(/filename="?([^"]+)"?/);
    const filename = m ? m[1] : row.key;
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  } catch (e: any) {
    ElMessage.error(e?.message || "下载失败");
  } finally {
    rowLoadingKeys.value.delete(row.key);
  }
}

/* ----------------------------- 上传（大文件分片：浏览器→后端→存储池） ----------------------------- */
const uploadRef = ref();
const uploading = ref(false);
const uploadProgress = ref(0);      // 浏览器→后端 进度（0-100）
const uploadStatusText = ref("");   // 状态文案

/** el-upload 自定义请求：切片→逐片经后端写 S3 part→complete 组装入池。浏览器全程只与后端通信。 */
async function customUpload(options: UploadRequestOptions) {
  const { file } = options;
  if (!query.poolId) {
    ElMessage.warning("请先选择存储池");
    options.onError(new Error("未选择存储池"));
    return;
  }
  const f = file as File;
  const size = f.size;
  const prefix = query.prefix || "";
  const fileName = f.name;
  const path =
    prefix === "" || prefix.endsWith("/")
      ? prefix + fileName
      : prefix + "/" + fileName;
  uploading.value = true;
  uploadProgress.value = 0;
  uploadStatusText.value = "发起上传任务...";
  try {
    const init: any = await initiateUploadTask({
      poolId: query.poolId,
      path,
      fileName,
      size,
      chunkSize: UPLOAD_CHUNK_SIZE
    });
    if (!init || init.code !== 0) {
      throw new Error(init?.message || "发起上传任务失败");
    }
    const taskId = init.data.taskId;
    const totalChunks = init.data.totalChunks as number;
    const chunkSize = UPLOAD_CHUNK_SIZE;
    // 并发上传（3 路并行，消除分片间的停顿）；进度 = 各片已传字节之和 / 总大小
    const concurrency = 3;
    const loaded = new Array(totalChunks).fill(0);
    const updateProgress = () => {
      const sum = loaded.reduce((a, b) => a + b, 0);
      uploadProgress.value = Math.min(100, Math.round((sum / size) * 100));
    };
    let nextIdx = 0;
    let completed = 0;
    uploadStatusText.value = `上传中 0/${totalChunks}`;
    const worker = async () => {
      while (true) {
        const idx = nextIdx++;
        if (idx >= totalChunks) break;
        const start = idx * chunkSize;
        const end = Math.min(start + chunkSize, size);
        const blob = f.slice(start, end);
        const res: any = await uploadTaskChunk(taskId, idx + 1, blob, l => {
          loaded[idx] = l;
          updateProgress();
        });
        if (!res || res.code !== 0) {
          throw new Error(res?.message || `第 ${idx + 1} 片上传失败`);
        }
        loaded[idx] = end - start;
        updateProgress();
        completed++;
        uploadStatusText.value = `上传中 ${completed}/${totalChunks}`;
      }
    };
    await Promise.all(
      Array.from({ length: Math.min(concurrency, totalChunks) }, () => worker())
    );
    // 后端组装入池
    uploadStatusText.value = "后端写入存储池中...";
    const done: any = await completeUploadTask(taskId);
    if (!done || done.code !== 0) {
      throw new Error(done?.message || "组装失败");
    }
    uploadProgress.value = 100;
    uploadStatusText.value = "已完成";
    ElMessage.success(`${fileName} 上传成功`);
    loadFiles();
    options.onSuccess({});
  } catch (e: any) {
    ElMessage.error(e?.message || "上传失败");
    options.onError(e);
  } finally {
    uploading.value = false;
  }
}

function onUploadClick() {
  uploadRef.value?.$el?.querySelector("input[type=file]")?.click();
}

/* ----------------------------- 删除 ----------------------------- */
function handleDelete(row: StorageObject) {
  if (!query.poolId) {
    ElMessage.warning("请先选择存储池");
    return;
  }
  ElMessageBox.confirm(`确认删除文件「${row.key}」吗？`, "提示", {
    type: "warning"
  })
    .then(async () => {
      rowLoadingKeys.value.add(row.key);
      try {
        const res: any = await deleteFiles({
          poolId: query.poolId,
          keys: [row.key]
        });
        if (res?.code === 0) {
          ElMessage.success("删除成功");
          loadFiles();
        } else {
          ElMessage.error(res?.message ?? "删除失败");
        }
      } catch (e: any) {
        ElMessage.error(e?.message ?? "删除失败");
      } finally {
        rowLoadingKeys.value.delete(row.key);
      }
    })
    .catch(() => {});
}

function handleBatchDelete() {
  if (!query.poolId) {
    ElMessage.warning("请先选择存储池");
    return;
  }
  if (selectedKeys.value.length === 0) {
    ElMessage.warning("请先勾选要删除的文件");
    return;
  }
  ElMessageBox.confirm(
    `确认删除选中的 ${selectedKeys.value.length} 个文件吗？`,
    "提示",
    { type: "warning" }
  )
    .then(async () => {
      deleting.value = true;
      try {
        const res: any = await deleteFiles({
          poolId: query.poolId,
          keys: selectedKeys.value
        });
        if (res?.code === 0) {
          ElMessage.success("删除成功");
          selectedKeys.value = [];
          loadFiles();
        } else {
          ElMessage.error(res?.message ?? "删除失败");
        }
      } catch (e: any) {
        ElMessage.error(e?.message ?? "删除失败");
      } finally {
        deleting.value = false;
      }
    })
    .catch(() => {});
}

function onSelectionChange(rows: StorageObject[]) {
  selectedKeys.value = rows.map(r => r.key).filter(Boolean);
}

onMounted(async () => {
  await loadPools();
  if (query.poolId) {
    loadFiles();
  }
});
</script>

<template>
  <div class="p-4">
    <!-- 顶部操作区 -->
    <el-card shadow="never" class="mb-3">
      <el-form :inline="true" :model="query">
        <el-form-item label="存储池">
          <el-select
            v-model="query.poolId"
            placeholder="请选择存储池"
            style="width: 220px"
            @change="onPoolChange"
          >
            <el-option
              v-for="p in pools"
              :key="p.id"
              :label="p.name"
              :value="p.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="前缀">
          <el-input
            v-model="query.prefix"
            placeholder="例如：data/"
            clearable
            style="width: 280px"
            @keyup.enter="onSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">浏览</el-button>
          <el-button @click="onReset">重置</el-button>
          <el-button
            v-perms="['storage:manage']"
            type="success"
            :loading="uploading"
            @click="onUploadClick"
          >
            上传
          </el-button>
          <el-button
            v-perms="['storage:manage']"
            type="danger"
            :disabled="selectedKeys.length === 0"
            :loading="deleting"
            @click="handleBatchDelete"
          >
            批量删除
          </el-button>
        </el-form-item>
      </el-form>
      <!-- 上传进度（浏览器→后端，分片逐片） -->
      <div
        v-if="uploading || uploadProgress > 0"
        class="upload-progress-bar"
      >
        <el-progress
          :percentage="uploadProgress"
          :status="uploadProgress >= 100 ? 'success' : undefined"
          :stroke-width="16"
        />
        <span class="upload-status-text">
          {{ uploadStatusText }}（{{ uploadProgress }}%）
        </span>
      </div>

      <!-- 面包屑 -->
      <el-breadcrumb class="crumbs" separator="/">
        <el-breadcrumb-item
          v-for="(c, idx) in crumbs"
          :key="idx"
          @click="crumbTo(c.path)"
        >
          <el-link type="primary" :underline="false">{{ c.label }}</el-link>
        </el-breadcrumb-item>
      </el-breadcrumb>
    </el-card>

    <!-- 文件列表 -->
    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="fileList"
        border
        stripe
        style="width: 100%"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="48" align="center" />
        <el-table-column type="index" label="#" width="60" align="center" />
        <el-table-column prop="key" label="路径 / Key" min-width="280">
          <template #default="{ row }">
            <el-link
              v-if="isDir(row)"
              type="primary"
              :underline="false"
              @click="enterDir(row)"
            >
              📁 {{ row.key }}
            </el-link>
            <span v-else>{{ row.key }}</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="140" align="right">
          <template #default="{ row }">
            {{ isDir(row) ? "-" : formatBytes(row.size) }}
          </template>
        </el-table-column>
        <el-table-column label="最后修改" width="200">
          <template #default="{ row }">
            {{ formatTime(row.lastModified) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="!isDir(row)">
              <el-button
                type="primary"
                link
                :loading="isRowLoading(row.key)"
                @click="downloadFile(row)"
              >
                下载
              </el-button>
              <el-button
                v-perms="['storage:manage']"
                type="danger"
                link
                :loading="isRowLoading(row.key)"
                @click="handleDelete(row)"
              >
                删除
              </el-button>
            </template>
            <span v-else class="dir-hint">目录</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无文件" />
        </template>
      </el-table>
    </el-card>

    <!-- 隐藏的 el-upload 用于触发文件选择 -->
    <el-upload
      ref="uploadRef"
      class="hidden-upload"
      :show-file-list="false"
      :auto-upload="true"
      :http-request="customUpload"
    />
  </div>
</template>

<style scoped>
.crumbs {
  margin-top: 8px;
  font-size: 14px;
}
.crumbs :deep(.el-breadcrumb__item) {
  cursor: pointer;
}
.dir-hint {
  color: var(--el-text-color-secondary);
}
.hidden-upload {
  display: none;
}
</style>
