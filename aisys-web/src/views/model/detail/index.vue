<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElTag } from "element-plus";
import {
  getModelDetail,
  getModelVersions,
  createModelVersion,
  initiateModelUpload,
  completeModelUpload,
  type ModelItem,
  type ModelVersion
} from "@/api/model";

defineOptions({ name: "ModelDetail" });

/** 模型文件默认分片大小（16MB，与后端一致） */
const MODEL_CHUNK_SIZE = 16 * 1024 * 1024;

const route = useRoute();
const router = useRouter();
const modelId = computed(() => route.params.id as string);

const loading = ref(false);
const versionLoading = ref(false);
const model = reactive<Partial<ModelItem>>({});
const versions = ref<ModelVersion[]>([]);

/** 模型级别状态（draft/published/deprecated） */
const modelStatusTagType = (status?: string) => {
  switch (status) {
    case "published": return "success" as const;
    case "deprecated": return "warning" as const;
    default: return "info" as const;
  }
};
const modelStatusLabel = (status?: string) => {
  switch (status) {
    case "published": return "已发布";
    case "deprecated": return "已废弃";
    default: return "草稿";
  }
};

const formatDate = (val?: string) => (val ? val : "-");

const formatBytes = (b?: number) => {
  if (b == null || Number.isNaN(b)) return "-";
  if (b === 0) return "0 B";
  const u = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(u.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
  return `${(b / Math.pow(1024, i)).toFixed(2)} ${u[i]}`;
};

const fetchDetail = async () => {
  loading.value = true;
  try {
    const res: any = await getModelDetail(route.params.id as string);
    if (res?.code === 0) {
      Object.assign(model, res.data ?? {});
    } else {
      ElMessage.error(res?.message || "获取详情失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "获取详情失败");
  } finally {
    loading.value = false;
  }
};

const fetchVersions = async () => {
  versionLoading.value = true;
  try {
    const res: any = await getModelVersions(route.params.id as string);
    if (res?.code === 0) {
      // 后端返回 ApiResponse<List>（res.data 为数组），非分页
      const d = res.data;
      versions.value = (Array.isArray(d) ? d : (d?.items ?? d?.list ?? [])) as ModelVersion[];
    } else {
      ElMessage.error(res?.message || "获取版本列表失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "获取版本列表失败");
  } finally {
    versionLoading.value = false;
  }
};

const goBack = () => {
  router.push("/model/list");
};

/* ======================== 版本状态映射 ======================== */
/** 版本状态 → tag 类型 + 中文 */
const versionStatusMap = (s?: string) => {
  switch ((s || "").toUpperCase()) {
    case "READY":
      return { type: "success" as const, text: "就绪" };
    case "CREATING":
      return { type: "warning" as const, text: "上传中" };
    case "FAILED":
      return { type: "danger" as const, text: "失败" };
    default:
      return { type: "info" as const, text: s || "-" };
  }
};

/* ======================== 上传新版本 ======================== */
const uploadVisible = ref(false);
const uploadForm = reactive({ version: "", file: null as File | null, imageName: "" });
const uploading = ref(false);
const uploadProgress = ref(0);
const uploadStatusText = ref("");

const openUpload = () => {
  uploadForm.version = "";
  uploadForm.file = null;
  uploadForm.imageName = "";
  uploadProgress.value = 0;
  uploadStatusText.value = "";
  uploadVisible.value = true;
};

const onFileChange = (file: any) => {
  uploadForm.file = file.raw ?? file;
};
const onFileRemove = () => {
  uploadForm.file = null;
};

/** 计算文件简易 hash */
const calculateFileHash = (file: File): Promise<string> => {
  return new Promise((resolve) => {
    if (file.size > 50 * 1024 * 1024) {
      resolve(`${file.size}-${file.name}`);
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const bytes = new Uint8Array(reader.result as ArrayBuffer);
      let hash = file.size.toString(16);
      for (let i = 0; i < Math.min(256, bytes.length); i++) {
        hash += bytes[i].toString(16).padStart(2, "0");
      }
      resolve(hash);
    };
    reader.readAsArrayBuffer(file.slice(0, Math.min(file.size, 1024 * 1024)));
  });
};

/** 将 S3 预签名 URL 改为同源 nginx 反代路径 /s3-upload/ */
const fixPresignUrl = (url: string): string => {
  try {
    const u = new URL(url);
    if (u.port === "8333" || u.hostname === "seaweedfs") {
      // 提取完整路径+查询参数，挂到同源 /s3-upload/ 下
      const pathAndQuery = u.pathname + u.search;
      return `${window.location.origin}/s3-upload${pathAndQuery.startsWith('/') ? '' : '/'}${pathAndQuery}`;
    }
    return url;
  } catch {
    return url;
  }
};

/** PUT 分片到 S3 预签名 URL（带超时+诊断） */
const uploadChunkToS3 = (
  url: string,
  blob: Blob,
  onProgress?: (loaded: number) => void
): Promise<string> => {
  return new Promise((resolve, reject) => {
    const fixedUrl = fixPresignUrl(url);
    const idxTag = `chunk#${Math.random().toString(36).slice(2, 6)}`;
    console.log(`[Detail-Diag] ${idxTag} PUT开始 size=${blob.size}MB url=${fixedUrl}`);
    console.log(`[Detail-Diag] ${idxTag} 原始url=${url}`);

    const xhr = new XMLHttpRequest();
    xhr.open("PUT", fixedUrl);
    xhr.timeout = 60000; // ★ 60秒超时，防止永久挂起

    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    if (onProgress && xhr.upload) {
      xhr.upload.onprogress = e => {
        if (e.lengthComputable) onProgress(e.loaded);
      };
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        const etag = xhr.getResponseHeader("ETag") || `${Date.now()}`;
        console.log(`[Detail-Diag] ${idxTag} ✅ HTTP ${xhr.status} etag=${etag}`);
        resolve(etag.replace(/"/g, ""));
      } else {
        console.error(`[Detail-Diag] ${idxTag} ❌ HTTP ${xhr.status}`, xhr.responseText?.substring(0, 200));
        reject(new Error(`S3 上传失败: HTTP ${xhr.status}`));
      }
    };
    xhr.ontimeout = () => {
      console.error(`[Detail-Diag] ${idxTag} ⏰ 超时 60s! S3 无响应`);
      reject(new Error("S3 上传超时（60s无响应）"));
    };
    xhr.onerror = (e) => {
      console.error(`[Detail-Diag] ${idxTag} 🔌 网络错误`, e);
      reject(new Error("网络错误：无法连接S3"));
    };
    xhr.send(blob);
  });
};

const submitUpload = async () => {
  console.log("[Detail-Diag] ★ submitUpload 开始", {
    version: uploadForm.version,
    hasFile: !!uploadForm.file,
    fileSize: uploadForm.file?.size,
    modelId: modelId.value
  });

  if (!uploadForm.version.trim()) {
    ElMessage.warning("请输入版本号");
    return;
  }
  if (!uploadForm.file) {
    ElMessage.warning("请选择模型文件");
    return;
  }

  const file = uploadForm.file;
  const version = uploadForm.version.trim();
  uploading.value = true;
  uploadProgress.value = 0;
  uploadStatusText.value = "创建版本...";

  try {
    // 1. 创建版本（config.imageName 是 docker run 必需的镜像名）
    console.log("[Detail-Diag] 步骤1: 创建版本...");
    const config = uploadForm.imageName.trim() ? { imageName: uploadForm.imageName.trim() } : undefined;
    const verRes: any = await createModelVersion(modelId.value, { version, config });
    if (!verRes || verRes.code !== 0) throw new Error(verRes?.message || "创建版本失败");
    const versionId = verRes.data.id;
    console.log("[Detail-Diag] 步骤1 ✅ versionId=", versionId);

    // 2. 初始化上传
    uploadStatusText.value = "初始化上传...";
    const fileHash = await calculateFileHash(file);
    console.log("[Detail-Diag] 步骤2: initiateUpload... fileSize=", file.size, "hash=", fileHash);
    const initRes: any = await initiateModelUpload(modelId.value, versionId, {
      fileSize: file.size,
      fileHash,
      chunkSize: MODEL_CHUNK_SIZE
    });
    if (!initRes || initRes.code !== 0) throw new Error(initRes?.message || "初始化上传失败");

    const initData: any = initRes.data;
    console.log("[Detail-Diag] 步骤2 ✅ initiateUpload 返回:", {
      dedup: initData.dedup,
      uploadId: initData.uploadId,
      chunkCount: initData.chunkCount,
      firstUrl: initData?.chunkUrls?.[0]?.url?.substring(0, 120) + "...",
    });

    // 秒传命中
    if (initData.dedup) {
      uploadStatusText.value = "秒传命中...";
      uploadProgress.value = 100;
      const compRes: any = await completeModelUpload(modelId.value, versionId, {
        uploadId: "", fileSize: file.size, checksum: fileHash, parts: []
      });
      if (!compRes || compRes.code !== 0) throw new Error(compRes?.message || "标记完成失败");
      ElMessage.success("上传成功（秒传）");
      uploadVisible.value = false;
      fetchVersions();
      return;
    }

    // 3. 分片直传 S3
    const chunkCount = initData.chunkCount;
    const chunkUrls = initData.chunkUrls ?? [];
    const loaded = new Array(chunkCount).fill(0);
    const updateProgress = () => {
      const sum = loaded.reduce((a, b) => a + b, 0);
      uploadProgress.value = Math.min(99, Math.round((sum / file.size) * 100));
    };

    let completed = 0;
    let nextIdx = 0;
    uploadStatusText.value = `上传中 0/${chunkCount}`;

    const worker = async (): Promise<any[]> => {
      const parts: any[] = [];
      while (true) {
        const idx = nextIdx++;
        if (idx >= chunkCount) break;
        const start = idx * MODEL_CHUNK_SIZE;
        const end = Math.min(start + MODEL_CHUNK_SIZE, file.size);
        const blob = file.slice(start, end);
        const etag = await uploadChunkToS3(chunkUrls[idx]?.url, blob, l => {
          loaded[idx] = l;
          updateProgress();
        });
        loaded[idx] = end - start;
        updateProgress();
        parts.push({ partNumber: idx + 1, etag });
        completed++;
        uploadStatusText.value = `上传中 ${completed}/${chunkCount}`;
      }
      return parts;
    };

    const workers = Array.from({ length: Math.min(3, chunkCount) }, () => worker());
    const allParts = (await Promise.all(workers)).flat();
    allParts.sort((a: any, b: any) => a.partNumber - b.partNumber);

    // 4. 完成
    uploadStatusText.value = "后端组装中...";
    uploadProgress.value = 99;
    const compRes2: any = await completeModelUpload(modelId.value, versionId, {
      uploadId: initData.uploadId,
      fileSize: file.size,
      checksum: fileHash,
      parts: allParts
    });
    if (!compRes2 || compRes2.code !== 0) throw new Error(compRes2?.message || "组装失败");

    uploadProgress.value = 100;
    uploadStatusText.value = "已完成";
    ElMessage.success("版本上传成功");
    uploadVisible.value = false;
    fetchVersions();
  } catch (e: any) {
    ElMessage.error(e?.message || "上传失败");
  } finally {
    uploading.value = false;
  }
};

onMounted(() => {
  fetchDetail();
  fetchVersions();
});
</script>

<template>
  <div class="model-detail-container" v-loading="loading">
    <el-page-header title="返回" @back="goBack">
      <template #content>
        <span class="header-title">模型详情</span>
      </template>
    </el-page-header>

    <!-- 基本信息 -->
    <el-card shadow="never" class="info-card">
      <template #header>
        <span>基本信息</span>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="名称">
          {{ model.name || "-" }}
        </el-descriptions-item>
        <el-descriptions-item label="类型">
          {{ model.type || "-" }}
        </el-descriptions-item>
        <el-descriptions-item label="框架">
          {{ model.framework || "-" }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="modelStatusTagType(model.status)">
            {{ modelStatusLabel(model.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="可见性">
          {{ model.visibility || "-" }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">
          {{ formatDate(model.createdAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">
          {{ model.description || "-" }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 版本列表 -->
    <el-card shadow="never" class="version-card">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>版本列表</span>
          <el-button v-perms="['model:create']" type="primary" size="small" @click="openUpload">
            上传新版本
          </el-button>
        </div>
      </template>
      <el-table
        v-loading="versionLoading"
        :data="versions"
        border
        stripe
        style="width: 100%"
      >
        <el-table-column label="版本号" prop="version" min-width="140" />
        <el-table-column label="状态" prop="status" width="110">
          <template #default="{ row }">
            <el-tag :type="versionStatusMap(row.status).type" size="small">
              {{ versionStatusMap(row.status).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文件大小" width="120" align="right">
          <template #default="{ row }">{{ row.fileSize ? formatBytes(row.fileSize) : "-" }}</template>
        </el-table-column>
        <el-table-column label="校验值" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.checksum" class="checksum-text">{{ row.checksum }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <template #empty>
          <span>暂无版本（点击「上传新版本」开始）</span>
        </template>
      </el-table>
    </el-card>

    <!-- 上传新版本对话框 -->
    <el-dialog
      v-model="uploadVisible"
      title="上传新版本"
      width="540px"
      destroy-on-close
      :close-on-click-modal="!uploading"
      :close-on-press-escape="!uploading"
      :show-close="!uploading"
    >
      <el-form label-width="100px" :disabled="uploading">
        <el-form-item label="版本号">
          <el-input v-model="uploadForm.version" placeholder="例如 v2.0, v1.1-beta" />
        </el-form-item>
        <el-form-item label="镜像名称" required>
          <el-input v-model="uploadForm.imageName" placeholder="例如 aisys/ultralytics-yolo-cls:v1（docker run 使用的镜像名）" />
          <div class="upload-tip">Docker 容器镜像名，用于训练/评测时 docker run 启动容器。必须与构建镜像时的 tag 一致。</div>
        </el-form-item>
        <el-form-item label="模型文件">
          <el-upload
            :auto-upload="false"
            :limit="1"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
            :show-file-list="true"
            accept=".pt,.pth,.onnx,.pb,.h5,.hl,.bin,.safetensors,.joblib,.pkl,.pickle"
          >
            <el-button type="primary">选择模型文件</el-button>
            <template #tip>
              <div class="upload-tip">支持 PyTorch/ONNX/TensorFlow 等格式；大文件自动分片上传（16MB/片）</div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>

      <!-- 上传进度 -->
      <div v-if="uploading || uploadProgress > 0" class="upload-progress-area">
        <el-progress
          :percentage="uploadProgress"
          :status="uploadProgress >= 100 ? 'success' : undefined"
          :stroke-width="14"
        />
        <p class="progress-text">{{ uploadStatusText }}（{{ uploadProgress }}%）</p>
      </div>

      <template #footer>
        <el-button @click="uploadVisible = false" :disabled="uploading">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">
          {{ uploading ? "上传中..." : "上传" }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.model-detail-container {
  padding: 16px;
}

.header-title {
  font-weight: 600;
}

.info-card,
.version-card {
  margin-top: 16px;
}

.upload-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  line-height: 1.4;
  margin-top: 4px;
}

.upload-progress-area {
  padding: 12px 0 0;
}

.progress-text {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
  margin: 6px 0 0;
}

.checksum-text {
  font-family: "Consolas", "Monaco", monospace;
  font-size: 11px;
  word-break: break-all;
  color: var(--el-text-color-secondary, #909399);
}
</style>
