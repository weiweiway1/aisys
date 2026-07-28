<script setup lang="ts">
import { h, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox, ElTag, ElButton } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import {
  getModelList,
  createModel,
  deleteModel,
  publishModel,
  createModelVersion,
  initiateModelUpload,
  completeModelUpload,
  modelTypeLabel,
  type ModelItem,
  type ModelPayload,
  type ModelStatus
} from "@/api/model";

/** 模型文件默认分片大小（16MB，与后端 aisys.model.default-chunk-size 一致） */
const MODEL_CHUNK_SIZE = 16 * 1024 * 1024;

defineOptions({ name: "ModelList" });

// ★★★ 强制确认组件是否加载（浏览器 Console 必须看到这行） ★★★
console.log("[ModelList-V2] 组件脚本开始执行", { timestamp: Date.now() });

const router = useRouter();

const loading = ref(false);
const dataList = ref<ModelItem[]>([]);
const total = ref(0);

const query = reactive({
  page: 1,
  size: 10,
  keyword: "",
  status: "" as "" | ModelStatus
});

const pagination = reactive({
  total: 0,
  currentPage: 1,
  pageSize: 20,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const statusTagType = (status: ModelStatus) => {
  switch (status) {
    case "published":
      return "success";
    case "deprecated":
      return "warning";
    case "draft":
    default:
      return "info";
  }
};

const statusLabel = (status: ModelStatus) => {
  switch (status) {
    case "published":
      return "已发布";
    case "deprecated":
      return "已废弃";
    case "draft":
    default:
      return "草稿";
  }
};

const formatDate = (val?: string) => (val ? val : "-");

const columns: TableColumnList = [
  {
    label: "名称",
    prop: "name",
    minWidth: 160,
    showOverflowTooltip: true
  },
  {
    label: "类型",
    prop: "type",
    width: 120,
    cellRenderer: ({ row }) => h(ElTag, { type: "info", effect: "plain" }, () => modelTypeLabel(row.type))
  },
  {
    label: "框架",
    prop: "framework",
    width: 140,
    showOverflowTooltip: true
  },
  {
    label: "状态",
    width: 110,
    align: "center",
    cellRenderer: ({ row }) =>
      h(
        ElTag,
        { type: statusTagType(row.status) },
        () => statusLabel(row.status)
      )
  },
  {
    label: "创建时间",
    width: 180,
    cellRenderer: ({ row }) => formatDate(row.createdAt)
  },
  {
    label: "操作",
    width: 240,
    fixed: "right",
    cellRenderer: ({ row }) =>
      h("div", { class: "flex items-center gap-2" }, [
        h(
          ElButton,
          { link: true, type: "primary", size: "small", onClick: () => goDetail(row) },
          () => "详情"
        ),
        h(
          ElButton,
          {
            link: true,
            type: "success",
            size: "small",
            disabled: row.status === "published",
            onClick: () => handlePublish(row)
          },
          () => "发布"
        ),
        h(
          ElButton,
          { link: true, type: "danger", size: "small", onClick: () => handleDelete(row) },
          () => "删除"
        )
      ])
  }
];

const fetchList = async () => {
  loading.value = true;
  try {
    const res: any = await getModelList({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined
    });
    if (res?.code === 0) {
      const d = res.data ?? {};
      dataList.value = (d.items ?? []) as ModelItem[];
      total.value = d.total ?? 0;
      pagination.total = total.value;
      pagination.currentPage = query.page;
      pagination.pageSize = query.size;
    } else {
      ElMessage.error(res?.message || "获取列表失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "获取列表失败");
  } finally {
    loading.value = false;
  }
};

const handleSearch = () => {
  query.page = 1;
  fetchList();
};

const handleReset = () => {
  query.keyword = "";
  query.status = "";
  query.page = 1;
  fetchList();
};

const onSizeChange = (size: number) => {
  query.size = size;
  query.page = 1;
  fetchList();
};

const onCurrentChange = (page: number) => {
  query.page = page;
  fetchList();
};

const goDetail = (row: ModelItem) => {
  router.push(`/model/detail/${row.id}`);
};

const handlePublish = async (row: ModelItem) => {
  try {
    await ElMessageBox.confirm(
      `确定要发布模型「${row.name}」吗？`,
      "发布确认",
      { type: "warning" }
    );
  } catch {
    return;
  }
  try {
    const res: any = await publishModel(row.id);
    if (res?.code === 0) {
      ElMessage.success("发布成功");
      fetchList();
    } else {
      ElMessage.error(res?.message || "发布失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "发布失败");
  }
};

const handleDelete = async (row: ModelItem) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除模型「${row.name}」吗？此操作不可恢复。`,
      "删除确认",
      { type: "warning" }
    );
  } catch {
    return;
  }
  try {
    const res: any = await deleteModel(row.id);
    if (res?.code === 0) {
      ElMessage.success("删除成功");
      fetchList();
    } else {
      ElMessage.error(res?.message || "删除失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message || "删除失败");
  }
};

// 新建弹窗（含文件上传）
const dialogVisible = ref(false);
const submitLoading = ref(false);
const uploading = ref(false);
const uploadProgress = ref(0);
const uploadStatusText = ref("");
const formRef = ref<FormInstance>();
const form = reactive<{
  name: string;
  type: string;
  framework: string;
  description: string;
  version: string;       // 版本号
  imageName: string;     // Docker 镜像名（docker run 用）
}>({
  name: "",
  type: "image_classification",
  framework: "",
  description: "",
  version: "",
  imageName: ""
});
const selectedFile = ref<File | null>(null);

const rules: FormRules = {
  name: [{ required: true, message: "请输入模型名称", trigger: "blur" }],
  type: [{ required: true, message: "请选择模型类型", trigger: "change" }],
  framework: [{ required: true, message: "请输入框架", trigger: "blur" }],
  version: [{ required: true, message: "请输入版本号", trigger: "blur" }],
  imageName: [{ required: true, message: "请输入 Docker 镜像名称（用于训练/评测启动容器）", trigger: "blur" }]
};

// 模型类型 = 任务类型，与数据集 taskType 一致（图像分类/时序分类/目标检测）
const typeOptions = [
  { label: "图像分类", value: "image_classification" },
  { label: "时序分类", value: "time_series" },
  { label: "目标检测", value: "object_detection" }
];

/** 计算文件的 SHA-256 hash（用于秒传检测） */
const calculateFileHash = (file: File): Promise<string> => {
  return new Promise((resolve) => {
    // 大文件仅取前 1MB + 文件大小作为简易指纹，避免阻塞 UI
    if (file.size > 50 * 1024 * 1024) {
      resolve(`${file.size}-${file.name}`);
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const buffer = reader.result as ArrayBuffer;
      // 简易 hash：用文件大小 + 前 256 字节 + 后 256 字节的组合
      const bytes = new Uint8Array(buffer);
      let hash = file.size.toString(16);
      for (let i = 0; i < Math.min(256, bytes.length); i++) {
        hash += bytes[i].toString(16).padStart(2, "0");
      }
      resolve(hash);
    };
    reader.readAsArrayBuffer(file.slice(0, Math.min(file.size, 1024 * 1024)));
  });
};

/** 诊断日志：带时间戳的 console 输出 */
const diag = (msg: string, data?: any) => {
  const ts = new Date().toLocaleTimeString("zh-CN", { hour12: false });
  console.warn(`[DIAG ${ts}] ${msg}`, data ?? "");
};

/** 修正 S3 预签名 URL 的 hostname */
const fixPresignUrl = (url: string): { fixed: string; changed: boolean; original: string } => {
  try {
    const u = new URL(url);
    const original = url;
    let changed = false;
    if (u.port === "8333" || u.hostname === "seaweedfs") {
      const pathAndQuery = u.pathname + u.search;
      const fixed = `${window.location.origin}/s3-upload${pathAndQuery.startsWith('/') ? '' : '/'}${pathAndQuery}`;
      diag(`fixPresignUrl: S3 URL → 同源反代 ${fixed.substring(0, 100)}...`);
      return { fixed, changed: true, original };
    }
    return { fixed: url, changed: false, original: url };
  } catch (e) {
    diag(`fixPresignUrl 解析失败`, e);
    return { fixed: url, changed: false, original: url };
  }
};

/** 上传分片到 S3 预签名 URL（PUT 直传，带诊断 + 超时） */
const uploadChunkToS3 = (
  url: string,
  blob: Blob,
  onProgress?: (loaded: number) => void
): Promise<string> => {
  return new Promise((resolve, reject) => {
    const { fixed, changed, original } = fixPresignUrl(url);
    const idxTag = `chunk#${Math.random().toString(36).slice(2, 6)}`;
    diag(`${idxTag} 开始上传 blob.size=${blob.size} bytes=${blob.size / 1024 / 1024}MB`, {
      originalHost: new URL(original).hostname,
      fixedHost: new URL(fixed).hostname,
      urlChanged: changed,
      fullUrl: fixed.substring(0, 120) + "..."
    });

    const xhr = new XMLHttpRequest();
    xhr.open("PUT", fixed);

    // ★ 关键修复：加 60 秒超时，避免永久挂起
    xhr.timeout = 60000;

    xhr.setRequestHeader("Content-Type", "application/octet-stream");

    if (onProgress && xhr.upload) {
      xhr.upload.onprogress = e => {
        if (e.lengthComputable) onProgress(e.loaded);
      };
    }

    xhr.onload = () => {
      diag(`${idxTag} onload status=${xhr.status} etag=${xhr.getResponseHeader("ETag")}`, {
        responseText: xhr.responseText?.substring(0, 200),
        allResponseHeaders: xhr.getAllResponseHeaders()?.substring(0, 300)
      });
      if (xhr.status >= 200 && xhr.status < 300) {
        const etag = xhr.getResponseHeader("ETag") || `${Date.now()}`;
        resolve(etag.replace(/"/g, ""));
      } else {
        reject(new Error(`S3 上传失败: HTTP ${xhr.status} ${xhr.statusText}`));
      }
    };

    xhr.onerror = () => {
      diag(`${idxTag} onerror 触发！网络层错误`);
      reject(new Error("网络错误（XHR onerror）"));
    };

    xhr.ontimeout = () => {
      diag(`${idxTag} ontimout 触发！超过 60 秒无响应`);
      reject(new Error("上传超时（60s无响应）— S3 可能不可达或签名无效"));
    };

    const startTime = Date.now();
    xhr.send(blob);

    // 监控：如果 10 秒内没有任何进度变化，打警告
    let lastLoaded = 0;
    const progressMonitor = setInterval(() => {
      const elapsed = Date.now() - startTime;
      if (elapsed > 10000 && lastLoaded === 0 && xhr.readyState !== 4) {
        diag(`${idxTag} ⚠️ 已等待 ${elapsed / 1000}s 但 upload 进度仍为 0，readyState=${xhr.readyState}`);
      }
      if (xhr.readyState === 4) clearInterval(progressMonitor);
    }, 5000);
    xhr.addEventListener("loadend", () => clearInterval(progressMonitor), { once: true });
  });
};

const openCreate = () => {
  form.name = "";
  form.type = "image_classification";
  form.framework = "";
  form.description = "";
  form.version = "v1.0";
  form.imageName = "";
  selectedFile.value = null;
  uploadProgress.value = 0;
  uploadStatusText.value = "";
  dialogVisible.value = true;
};

const onFileChange = (file: any) => {
  selectedFile.value = file.raw ?? file;
};

const onFileRemove = () => {
  selectedFile.value = null;
};

const handleCreate = async () => {
  // ★★★ 入口确认：点确定按钮后必须看到这行 ★★★
  console.log("[ModelList-V2] ★ handleCreate 被调用!", {
    hasFormRef: !!formRef.value,
    selectedFile: selectedFile.value ? { name: selectedFile.value.name, size: selectedFile.value.size } : null,
    formValues: { ...form }
  });

  if (!formRef.value) {
    console.error("[ModelList-V2] ❌ formRef.value 为空! 提前返回");
    return;
  }

  try {
    await formRef.value.validate(async valid => {
      console.log("[ModelList-V2] 表单验证结果:", valid);
      if (!valid) {
        console.warn("[ModelList-V2] ❌ 表单验证未通过，停止");
        return;
      }

    // 无文件时只创建元数据（兼容纯元数据场景）
    if (!selectedFile.value) {
      submitLoading.value = true;
      try {
        const res: any = await createModel({
          name: form.name,
          type: form.type,
          framework: form.framework,
          description: form.description
        });
        if (res?.code === 0) {
          ElMessage.success("创建成功（未上传模型文件）");
          dialogVisible.value = false;
          fetchList();
        } else {
          ElMessage.error(res?.message || "创建失败");
        }
      } catch (e: any) {
        ElMessage.error(e?.message || "创建失败");
      } finally {
        submitLoading.value = false;
      }
      return;
    }

    // 有文件：创建模型 → 创建版本 → 分片上传 → 完成
    const file = selectedFile.value;
    submitLoading.value = true;
    uploading.value = true;
    uploadProgress.value = 0;
    uploadStatusText.value = "创建模型...";

    try {
      diag(`=== 开始上传流程 file=${file.name} size=${file.size} (${(file.size / 1024 / 1024).toFixed(1)}MB) ===`);

      // 1. 创建模型（元数据）
      const modelRes: any = await createModel({
        name: form.name,
        type: form.type,
        framework: form.framework,
        description: form.description
      });
      if (!modelRes || modelRes.code !== 0) throw new Error(modelRes?.message || "创建模型失败");
      const modelId = modelRes.data.id;
      diag(`步骤1 ✅ 创建模型 modelId=${modelId}`);
      uploadStatusText.value = "创建版本...";

      // 2. 创建版本（config.imageName 是 docker run 必需的镜像名）
      const config = form.imageName.trim() ? { imageName: form.imageName.trim() } : undefined;
      const verRes: any = await createModelVersion(modelId, { version: form.version.trim(), config });
      if (!verRes || verRes.code !== 0) throw new Error(verRes?.message || "创建版本失败");
      const versionId = verRes.data.id;
      diag(`步骤2 ✅ 创建版本 versionId=${versionId}`);
      uploadStatusText.value = "初始化上传...";

      // 3. 计算文件 hash（秒传检测）
      const fileHash = await calculateFileHash(file);
      diag(`步骤3 ✅ fileHash=${fileHash}`);

      // 4. 初始化分片上传（获取预签名 URL）
      const initRes: any = await initiateModelUpload(modelId, versionId, {
        fileSize: file.size,
        fileHash,
        chunkSize: MODEL_CHUNK_SIZE
      });
      if (!initRes || initRes.code !== 0) throw new Error(initRes?.message || "初始化上传失败");

      const initData: any = initRes.data;
      diag(`步骤4 ✅ initiateUpload dedup=${initData.dedup} chunkCount=${initData.chunkCount} chunkSize=${initData.chunkSize} uploadId=${initData.uploadId}`, {
        firstChunkUrl: initData?.chunkUrls?.[0]?.url?.substring(0, 100) + "..."
      });

      // 秒传命中，直接完成
      if (initData.dedup) {
        uploadStatusText.value = "秒传命中，跳过上传...";
        uploadProgress.value = 100;
        const compRes: any = await completeModelUpload(modelId, versionId, {
          uploadId: "",
          fileSize: file.size,
          checksum: fileHash,
          parts: []
        });
        if (!compRes || compRes.code !== 0) throw new Error(compRes?.message || "标记完成失败");
        ElMessage.success("上传成功（秒传）");
        dialogVisible.value = false;
        fetchList();
        return;
      }

      // 5. 分片直传到 S3
      const chunkCount = initData.chunkCount;
      const chunkUrls = initData.chunkUrls ?? [];
      diag(`步骤5 开始分片上传 totalChunks=${chunkCount} concurrency=3`);
      const loaded = new Array(chunkCount).fill(0);
      const updateProgress = () => {
        const sum = loaded.reduce((a, b) => a + b, 0);
        uploadProgress.value = Math.min(99, Math.round((sum / file.size) * 100));
      };

      let completed = 0;
      uploadStatusText.value = `上传中 0/${chunkCount}`;
      let nextIdx = 0;

      const worker = async (): Promise<CompletedPart[]> => {
        const parts: CompletedPart[] = [];
        while (true) {
          const idx = nextIdx++;
          if (idx >= chunkCount) break;
          const start = idx * MODEL_CHUNK_SIZE;
          const end = Math.min(start + MODEL_CHUNK_SIZE, file.size);
          const blob = file.slice(start, end);
          diag(`  worker: 开始分片 ${idx + 1}/${chunkCount} start=${start} end=${end}`);
          const etag = await uploadChunkToS3(
            chunkUrls[idx]?.url,
            blob,
            l => { loaded[idx] = l; updateProgress(); }
          );
          diag(`  worker: ✅ 分片 ${idx + 1}/${chunkCount} etag=${etag}`);
          loaded[idx] = end - start;
          updateProgress();
          parts.push({ partNumber: idx + 1, etag });
          completed++;
          uploadStatusText.value = `上传中 ${completed}/${chunkCount}`;
        }
        return parts;
      };

      // 并发上传（最多 3 个并发 worker）
      diag(`启动 ${Math.min(3, chunkCount)} 个并发 worker...`);
      const workers = Array.from({ length: Math.min(3, chunkCount) }, () => worker());
      const allParts = (await Promise.all(workers)).flat();
      diag(`步骤5 ✅ 所有分片上传完成 totalParts=${allParts.length}`);

      // 按 partNumber 排序（保证顺序正确）
      allParts.sort((a, b) => a.partNumber - b.partNumber);

      // 6. 完成上传
      diag(`步骤6 通知后端组装 uploadId=${initData.uploadId} parts=${allParts.length}`);
      uploadStatusText.value = "后端组装中...";
      uploadProgress.value = 99;
      const compRes2: any = await completeModelUpload(modelId, versionId, {
        uploadId: initData.uploadId,
        fileSize: file.size,
        checksum: fileHash,
        parts: allParts
      });
      if (!compRes2 || compRes2.code !== 0) throw new Error(compRes2?.message || "组装失败");

      uploadProgress.value = 100;
      uploadStatusText.value = "已完成";
      ElMessage.success("模型上传成功");
      dialogVisible.value = false;
      fetchList();
    } catch (e: any) {
      diag(`❌ 上传流程异常: ${e?.message}`, e);
      ElMessage.error(e?.message || "上传失败");
    } finally {
      submitLoading.value = false;
      uploading.value = false;
    }
    });  // end validate callback
  } catch (e: any) {
    console.error("[ModelList-V2] ❌ handleCreate 外层异常:", e);
    ElMessage.error(e?.message || "操作失败");
    submitLoading.value = false;
    uploading.value = false;
  }
};

onMounted(() => {
  console.log("[ModelList-V2] ★ 组件已挂载，fetchList 开始");
  fetchList();
});
</script>

<template>
  <div class="model-list-container">
    <!-- 顶部搜索 -->
    <el-form :inline="true" class="search-form" @submit.prevent>
      <el-form-item label="关键词">
        <el-input
          v-model="query.keyword"
          placeholder="模型名称"
          clearable
          style="width: 200px"
          @keyup.enter="handleSearch"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="query.status"
          placeholder="全部"
          clearable
          style="width: 140px"
        >
          <el-option label="草稿" value="draft" />
          <el-option label="已发布" value="published" />
          <el-option label="已废弃" value="deprecated" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
        <el-button v-perms="['model:create']" type="success" @click="openCreate">
          新建模型
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
    >
      <template #empty>
        <span>暂无数据</span>
      </template>
    </pure-table>

    <!-- 新建弹窗（含上传） -->
    <el-dialog
      v-model="dialogVisible"
      title="新建模型"
      width="560px"
      destroy-on-close
      :close-on-click-modal="!uploading"
      :close-on-press-escape="!uploading"
      :show-close="!uploading"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="90px"
        :disabled="uploading"
      >
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入模型名称" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" placeholder="请选择类型" style="width: 100%">
            <el-option
              v-for="opt in typeOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="版本号" prop="version">
          <el-input v-model="form.version" placeholder="例如 v1.0, v2.0-beta" />
        </el-form-item>
        <el-form-item label="镜像名称" prop="imageName">
          <el-input v-model="form.imageName" placeholder="例如 aisys/ultralytics-yolo-cls:v1（docker run 使用的镜像名）" />
          <div style="font-size:12px;color:#909399;line-height:1.4;margin-top:4px">Docker 容器镜像名，用于训练/评测时启动容器。必须与构建镜像时的 tag 一致。</div>
        </el-form-item>
        <el-form-item label="框架" prop="framework">
          <el-input v-model="form.framework" placeholder="如 PyTorch / TensorFlow / ONNX" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="可选"
          />
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
              <div class="upload-tip">
                支持 .pt/.pth/.onnx/.pb/.h5 等格式；大文件自动分片上传（16MB/片）
              </div>
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
        <el-button @click="dialogVisible = false" :disabled="uploading">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleCreate">
          {{ uploading ? "上传中..." : "确定" }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.model-list-container {
  padding: 16px;
}

.search-form {
  margin-bottom: 12px;
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
</style>
