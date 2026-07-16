<script setup lang="ts">
import { reactive, ref, onMounted, computed } from "vue";
import { useRouter } from "vue-router";
import { http } from "@/utils/http";
import { message } from "@/utils/message";
import { getModelList } from "@/api/model";
import { getDatasetList } from "@/api/dataset";

const router = useRouter();
const loading = ref(false);

// 模型版本 / 数据集版本候选（扁平化）
const modelVersionOptions = ref<Array<{
  modelVersionId: number | string;
  label: string;
  imageName?: string;
  imageTarRelPath?: string;
  type?: string;
}>>([]);
const datasetVersionOptions = ref<Array<{
  datasetVersionId: number | string;
  label: string;
  datasetRelPath?: string;
  datasetFormat?: string;
  taskType?: string;
}>>([]);

const form = reactive({
  name: "",
  modelVersionId: "" as number | string | "",
  datasetVersionId: "" as number | string | "",
  epochs: 1,
  imgsz: 64,
  gpuCount: 0,
  cpu: 2,
  memoryBytes: 2147483648
});

const selectedModel = computed(() =>
  modelVersionOptions.value.find(m => m.modelVersionId === form.modelVersionId)
);
const selectedDataset = computed(() =>
  datasetVersionOptions.value.find(d => d.datasetVersionId === form.datasetVersionId)
);
/** 数据集按所选模型的任务类型过滤（model.type == dataset.taskType） */
const filteredDatasetOptions = computed(() => {
  const t = selectedModel.value?.type;
  if (!t) return datasetVersionOptions.value;
  return datasetVersionOptions.value.filter(d => !d.taskType || d.taskType === t);
});

/** 切换模型后，若当前数据集不在过滤集内则重置（避免类型不一致的组合） */
const onModelChange = () => {
  if (form.datasetVersionId && !filteredDatasetOptions.value.some(d => d.datasetVersionId === form.datasetVersionId)) {
    form.datasetVersionId = "";
  }
};

/** 拉取模型版本（含 config.imageName + storagePath + 模型任务类型） */
async function loadModelVersions() {
  try {
    const res: any = await getModelList({ size: 100 });
    const models = res?.data?.items ?? (Array.isArray(res?.data) ? res.data : []);
    const flat: typeof modelVersionOptions.value = [];
    for (const m of models) {
      const vr: any = await http.request("get", `/api/v1/models/${m.id}/versions`);
      const versions = vr?.data?.items ?? (Array.isArray(vr?.data) ? vr.data : []);
      for (const v of versions) {
        if (v.status !== "ready") continue;
        const cfg = v.config || {};
        flat.push({
          modelVersionId: v.id,
          label: `${m.name} @ ${v.version}`,
          imageName: cfg.imageName,
          imageTarRelPath: v.storagePath,
          type: m.type // 模型任务类型（与数据集 taskType 一致）
        });
      }
    }
    modelVersionOptions.value = flat;
  } catch (e) {
    console.error(e);
  }
}

/** 拉取数据集版本（含 storagePath + 数据集 format） */
async function loadDatasetVersions() {
  try {
    const res: any = await getDatasetList({ size: 100 });
    const datasets = res?.data?.items ?? (Array.isArray(res?.data) ? res.data : []);
    const flat: typeof datasetVersionOptions.value = [];
    for (const d of datasets) {
      const vr: any = await http.request("get", `/api/v1/datasets/${d.id}/versions`);
      const versions = vr?.data?.items ?? (Array.isArray(vr?.data) ? vr.data : []);
      for (const v of versions) {
        if (v.status !== "ready") continue;
        flat.push({
          datasetVersionId: v.id,
          label: `${d.name} @ ${v.version}`,
          datasetRelPath: v.storagePath,
          datasetFormat: d.format || (v.storagePath ? v.storagePath.split(".").pop() : ""),
          taskType: d.taskType // 数据集任务类型（与模型 type 一致）
        });
      }
    }
    datasetVersionOptions.value = flat;
  } catch (e) {
    console.error(e);
  }
}

const submit = async () => {
  if (!form.name) return message("请输入任务名称", { type: "warning" });
  const mv = selectedModel.value;
  if (!mv || !mv.imageName || !mv.imageTarRelPath) {
    return message("请选择一个 ready 的模型版本（需含 imageName 与镜像 tar）", { type: "warning" });
  }
  const dv = selectedDataset.value;
  if (!dv || !dv.datasetRelPath) {
    return message("请选择一个 ready 的数据集版本", { type: "warning" });
  }
  // 组装 containerSpec：每个模型是一个容器
  const containerSpec = {
    imageName: mv.imageName,
    imageTarRelPath: mv.imageTarRelPath,
    datasetRelPath: dv.datasetRelPath,
    datasetFormat: dv.datasetFormat || "yolo",
    taskMode: "train"
  };
  loading.value = true;
  try {
    const res: any = await http.request("post", "/api/v1/training/tasks", {
      data: {
        name: form.name,
        modelVersionId: Number(form.modelVersionId),
        datasetVersionId: Number(form.datasetVersionId),
        containerSpec,
        hyperparameters: { epochs: Number(form.epochs), imgsz: Number(form.imgsz) },
        resourceSpec: {
          gpuCount: Number(form.gpuCount),
          cpu: Number(form.cpu),
          memoryBytes: Number(form.memoryBytes)
        }
      }
    });
    if (res?.code === 0) {
      // 创建后自动 start（提交→调度→Agent 拉起容器）
      const tid = res.data?.id;
      if (tid) {
        const sr: any = await http.request("post", `/api/v1/training/tasks/${tid}/start`);
        if (sr?.code === 0) message("已创建并启动，正在调度到计算节点", { type: "success" });
      }
      message("训练任务创建成功", { type: "success" });
      router.push("/training/monitor/" + (tid || ""));
    } else message(res?.message || "创建失败", { type: "error" });
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  loadModelVersions();
  loadDatasetVersions();
});
</script>

<template>
  <div class="p-5">
    <el-card shadow="never">
      <template #header><span class="font-bold">创建训练任务（每个模型 = 一个容器）</span></template>
      <el-form label-width="120px" style="max-width: 640px">
        <el-form-item label="任务名称" required>
          <el-input v-model="form.name" placeholder="例如 YOLO11n-CPU-Run-001" />
        </el-form-item>
        <el-form-item label="模型版本" required>
          <el-select v-model="form.modelVersionId" placeholder="选择 ready 的模型版本" filterable style="width:100%" @change="onModelChange">
            <el-option v-for="m in modelVersionOptions" :key="m.modelVersionId" :label="m.label" :value="m.modelVersionId" />
          </el-select>
          <div v-if="selectedModel" class="text-xs text-gray-400 mt-1">
            类型 {{ selectedModel.type || "—" }} · 镜像 {{ selectedModel.imageName || "—" }}
          </div>
        </el-form-item>
        <el-form-item label="数据集版本" required>
          <el-select v-model="form.datasetVersionId" :placeholder="selectedModel?.type ? '仅显示同任务类型数据集' : '选择 ready 的数据集版本'" filterable style="width:100%">
            <el-option v-for="d in filteredDatasetOptions" :key="d.datasetVersionId" :label="d.label" :value="d.datasetVersionId" />
          </el-select>
          <div v-if="selectedDataset" class="text-xs text-gray-400 mt-1">
            格式 {{ selectedDataset.datasetFormat || "—" }} · {{ selectedDataset.datasetRelPath || "—" }}
          </div>
        </el-form-item>
        <el-form-item label="Epochs">
          <el-input-number v-model="form.epochs" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="图像尺寸">
          <el-input-number v-model="form.imgsz" :min="32" :max="1024" :step="32" />
          <span class="ml-2 text-xs text-gray-400">imgsz，CPU 建议 64</span>
        </el-form-item>
        <el-form-item label="GPU 数">
          <el-input-number v-model="form.gpuCount" :min="0" :max="8" />
          <span class="ml-2 text-xs text-gray-400">CPU 节点填 0</span>
        </el-form-item>
        <el-form-item label="CPU 核">
          <el-input-number v-model="form.cpu" :min="1" />
        </el-form-item>
        <el-form-item label="内存 (Bytes)">
          <el-input-number v-model="form.memoryBytes" :min="0" :step="536870912" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="submit">创建并启动</el-button>
          <el-button @click="router.push('/training/tasks')">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>
