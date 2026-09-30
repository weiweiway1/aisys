<script setup lang="ts">
import { h, ref, reactive, computed, onMounted } from "vue";
import { ElMessage, ElTag, ElButton, ElEmpty } from "element-plus";
import type { FormInstance, FormRules } from "element-plus";
import type { PaginationProps } from "@pureadmin/table";
import {
  getBenchmarkList,
  createBenchmark
} from "@/api/evaluation";
import { getDatasetList } from "@/api/dataset";
import { http } from "@/utils/http";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import AddFill from "~icons/ri/add-circle-line";
import Refresh from "~icons/ep/refresh";

defineOptions({
  name: "EvaluationBenchmarks"
});

const loading = ref(false);
const dataList = ref<any[]>([]);
const total = ref(0);

const query = reactive({
  page: 1,
  size: 10
});

const pagination = reactive<PaginationProps>({
  total: 0,
  currentPage: 1,
  pageSize: 10,
  background: true,
  layout: "total, sizes, prev, pager, next, jumper"
});

const columns: TableColumnList = [
  { label: "序号", type: "index", width: 60, align: "center" },
  {
    label: "名称",
    prop: "name",
    minWidth: 160,
    showOverflowTooltip: true
  },
  {
    label: "类别",
    prop: "category",
    width: 140,
    align: "center",
    cellRenderer: ({ row }) =>
      row.category
        ? h(ElTag, { type: "info" }, () => row.category)
        : h("span", null, "-")
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
    width: 180,
    align: "center"
  },
  {
    label: "操作",
    width: 120,
    align: "center",
    fixed: "right",
    cellRenderer: ({ row }) =>
      h(
        ElButton,
        { link: true, type: "primary", onClick: () => openDetail(row) },
        () => "详情"
      )
  }
];

const getList = async () => {
  loading.value = true;
  try {
    const res = await getBenchmarkList({
      page: query.page,
      size: query.size
    });
    if (res?.code === 0 && res.data) {
      dataList.value = res.data.items ?? [];
      total.value = res.data.total ?? 0;
      pagination.total = total.value;
      pagination.currentPage = query.page;
      pagination.pageSize = query.size;
    } else {
      ElMessage.error(res?.message ?? "获取评测集列表失败");
    }
  } catch (e: any) {
    ElMessage.error(e?.message ?? "获取评测集列表失败");
  } finally {
    loading.value = false;
  }
};

const onSizeChange = (size: number) => {
  query.size = size;
  query.page = 1;
  getList();
};

const onCurrentChange = (page: number) => {
  query.page = page;
  getList();
};

/* ---------------- 新建评测集弹窗 ---------------- */
const dialogVisible = ref(false);
const submitLoading = ref(false);
const formRef = ref<FormInstance>();
const datasetVersionOptions = ref<Array<{ id: number; label: string }>>([]);
const form = reactive({
  name: "",
  category: "",
  description: "",
  promptTemplate: "",
  datasetVersionIds: [] as number[]
});

const rules: FormRules = {
  name: [{ required: true, message: "请输入评测集名称", trigger: "blur" }]
};

/** 加载数据集版本（供评测集绑定测试数据） */
async function loadDatasetVersions() {
  try {
    const res: any = await getDatasetList({ size: 100 });
    const datasets = res?.data?.items ?? (Array.isArray(res?.data) ? res.data : []);
    const flat: Array<{ id: number; label: string }> = [];
    for (const d of datasets) {
      const vr: any = await http.request("get", `/api/v1/datasets/${d.id}/versions`);
      const versions = vr?.data?.items ?? (Array.isArray(vr?.data) ? vr.data : []);
      for (const v of versions) {
        if (v.status === "ready") flat.push({ id: v.id, label: `${d.name}-${v.version}` });
      }
    }
    datasetVersionOptions.value = flat;
  } catch (e) {
    console.error(e);
  }
}

const resetForm = () => {
  form.name = "";
  form.category = "";
  form.description = "";
  form.promptTemplate = "";
  form.datasetVersionIds = [];
  formRef.value?.clearValidate();
};

const openDialog = () => {
  resetForm();
  dialogVisible.value = true;
};

/* ---------------- 评测集详情（只读） ---------------- */
const detailVisible = ref(false);
const currentDetail = ref<any>(null);
const openDetail = (row: any) => {
  currentDetail.value = row;
  detailVisible.value = true;
};
const detailDatasetLabels = computed(() => {
  const ids = currentDetail.value?.datasetVersionIds;
  if (!Array.isArray(ids) || !ids.length) return [];
  const map = new Map(datasetVersionOptions.value.map(d => [d.id, d.label]));
  return ids.map((id: number) => map.get(id) ?? `数据集版本#${id}`);
});

const handleSubmit = async (formEl: FormInstance | undefined) => {
  if (!formEl) return;
  await formEl.validate(async valid => {
    if (!valid) return;
    submitLoading.value = true;
    try {
      const res = await createBenchmark({
        name: form.name,
        category: form.category || undefined,
        description: form.description || undefined,
        promptTemplate: form.promptTemplate || undefined,
        datasetVersionIds: form.datasetVersionIds.length ? form.datasetVersionIds : undefined
      });
      if (res?.code === 0) {
        ElMessage.success("新建评测集成功");
        dialogVisible.value = false;
        getList();
      } else {
        ElMessage.error(res?.message ?? "新建评测集失败");
      }
    } catch (e: any) {
      ElMessage.error(e?.message ?? "新建评测集失败");
    } finally {
      submitLoading.value = false;
    }
  });
};

onMounted(() => {
  getList();
  loadDatasetVersions();
});
</script>

<template>
  <div>
    <div class="w-full flex justify-between mb-4">
      <div class="flex">
        <el-button
          type="primary"
          :icon="useRenderIcon(AddFill)"
          @click="openDialog"
        >
          新建评测集
        </el-button>
        <el-button :icon="useRenderIcon(Refresh)" @click="getList">
          刷新
        </el-button>
      </div>
    </div>

    <pure-table
      row-key="id"
      :loading="loading"
      :data="dataList"
      :columns="columns"
      :pagination="pagination"
      @page-size-change="onSizeChange"
      @page-current-change="onCurrentChange"
    >
      <template #empty>
        <el-empty description="暂无评测集数据" />
      </template>
    </pure-table>

    <!-- 新建评测集弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      title="新建评测集"
      :width="640"
      draggable
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
      >
        <el-form-item label="名称" prop="name">
          <el-input
            v-model="form.name"
            style="width: 460px"
            placeholder="请输入评测集名称"
            clearable
          />
        </el-form-item>
        <el-form-item label="类别" prop="category">
          <el-input
            v-model="form.category"
            style="width: 460px"
            placeholder="请输入类别，如 MMLU、GSM8K"
            clearable
          />
        </el-form-item>
        <el-form-item label="测试数据集">
          <el-select
            v-model="form.datasetVersionIds"
            multiple
            filterable
            placeholder="选择 ready 的数据集版本作为评测测试集"
            style="width: 460px"
          >
            <el-option
              v-for="d in datasetVersionOptions"
              :key="d.id"
              :label="d.label"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            style="width: 460px"
            placeholder="请输入评测集描述"
          />
        </el-form-item>
        <el-form-item label="Prompt模板" prop="promptTemplate">
          <el-input
            v-model="form.promptTemplate"
            type="textarea"
            :rows="5"
            style="width: 460px"
            placeholder="请输入 Prompt 模板（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitLoading"
          @click="handleSubmit(formRef)"
        >
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 评测集详情（只读） -->
    <el-dialog
      v-model="detailVisible"
      title="评测集详情"
      :width="600"
      draggable
      destroy-on-close
    >
      <el-descriptions v-if="currentDetail" :column="1" border size="small">
        <el-descriptions-item label="名称">{{ currentDetail.name ?? "-" }}</el-descriptions-item>
        <el-descriptions-item label="类别">
          <el-tag v-if="currentDetail.category" size="small">{{ currentDetail.category }}</el-tag>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">{{ currentDetail.status ?? "-" }}</el-descriptions-item>
        <el-descriptions-item label="测试数据集">
          <template v-if="detailDatasetLabels.length">
            <el-tag v-for="(l, i) in detailDatasetLabels" :key="i" size="small" class="mr-1 mb-1">{{ l }}</el-tag>
          </template>
          <span v-else>-</span>
        </el-descriptions-item>
        <el-descriptions-item label="描述">{{ currentDetail.description ?? "-" }}</el-descriptions-item>
        <el-descriptions-item v-if="currentDetail.promptTemplate" label="Prompt 模板">
          <pre class="detail-pre">{{ currentDetail.promptTemplate }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ currentDetail.createdAt ?? "-" }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail-pre {
  margin: 0;
  padding: 8px;
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 4px;
  font-size: 12px;
  font-family: "Consolas", monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow: auto;
}
.mr-1 { margin-right: 4px; }
.mb-1 { margin-bottom: 4px; }
</style>
