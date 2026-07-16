<script setup lang="ts">
import { reactive } from "vue";
import {
  getModelsTotal,
  getDatasetsTotal,
  getTrainingTasksTotal,
  getEvaluationTasksTotal
} from "@/api/dashboard";

defineOptions({
  name: "Home"
});

interface StatCard {
  key: string;
  title: string;
  value: number;
  icon: string;
  color: string;
  loading: boolean;
}

const stats = reactive<StatCard[]>([
  {
    key: "models",
    title: "模型数",
    value: 0,
    icon: "ri:cpu-line",
    color: "#409eff",
    loading: true
  },
  {
    key: "datasets",
    title: "数据集数",
    value: 0,
    icon: "ri:database-2-line",
    color: "#67c23a",
    loading: true
  },
  {
    key: "training",
    title: "训练任务数",
    value: 0,
    icon: "ri:rocket-2-line",
    color: "#e6a23c",
    loading: true
  },
  {
    key: "evaluation",
    title: "评测数",
    value: 0,
    icon: "ri:bar-chart-grouped-line",
    color: "#f56c6c",
    loading: true
  }
]);

const readTotal = async (fn: () => Promise<any>): Promise<number> => {
  try {
    const res = await fn();
    const data: any = (res as any)?.data ?? {};
    return typeof data.total === "number" ? data.total : 0;
  } catch (e) {
    console.warn("[dashboard] load total failed", e);
    return 0;
  }
};

const loadStats = async () => {
  const [models, datasets, training, evaluation] = await Promise.all([
    readTotal(getModelsTotal),
    readTotal(getDatasetsTotal),
    readTotal(getTrainingTasksTotal),
    readTotal(getEvaluationTasksTotal)
  ]);
  const map: Record<string, number> = { models, datasets, training, evaluation };
  stats.forEach(item => {
    item.value = map[item.key] ?? 0;
    item.loading = false;
  });
};

loadStats();
</script>

<template>
  <div class="home-container">
    <el-row :gutter="20">
      <el-col
        v-for="item in stats"
        :key="item.key"
        :xs="24"
        :sm="12"
        :md="6"
        class="mb-4"
      >
        <el-card shadow="hover" class="stat-card">
          <div class="stat-card__inner">
            <div class="stat-card__icon" :style="{ backgroundColor: item.color }">
              <IconifyIconOnline :icon="item.icon" width="26" height="26" />
            </div>
            <div class="stat-card__content">
              <div class="stat-card__title">{{ item.title }}</div>
              <div class="stat-card__value">
                <el-skeleton-item
                  v-if="item.loading"
                  variant="text"
                  style="width: 60px"
                />
                <span v-else>{{ item.value }}</span>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped lang="scss">
.home-container {
  padding: 4px;
}

.stat-card {
  &__inner {
    display: flex;
    align-items: center;
    gap: 16px;
  }

  &__icon {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 56px;
    height: 56px;
    border-radius: 12px;
    color: #fff;
    font-size: 26px;
    flex-shrink: 0;
  }

  &__content {
    display: flex;
    flex-direction: column;
    justify-content: center;
    overflow: hidden;
  }

  &__title {
    font-size: 14px;
    color: var(--el-text-color-secondary);
  }

  &__value {
    margin-top: 6px;
    font-size: 26px;
    font-weight: 600;
    line-height: 1.2;
    color: var(--el-text-color-primary);
  }
}
</style>
