import { http } from "@/utils/http";

/**
 * 仪表盘相关接口。
 * 后端统一响应 { code, message, data }；分页 data 为
 * { items, total, page, size, totalPages }。此处仅取 total 用于统计卡片。
 */
type Result = {
  code: number;
  message: string;
  data?: any;
};

type PageData = {
  items: Array<any>;
  total: number;
  page: number;
  size: number;
  totalPages: number;
};

type ResultTable = {
  code: number;
  message: string;
  data?: PageData;
};

/**
 * 模型数（GET /api/v1/models）—— 仅取 total。
 */
export const getModelsTotal = () => {
  return http.request<ResultTable>("get", "/api/v1/models", {
    params: { page: 1, size: 1 }
  });
};

/**
 * 数据集数（GET /api/v1/datasets）—— 仅取 total。
 */
export const getDatasetsTotal = () => {
  return http.request<ResultTable>("get", "/api/v1/datasets", {
    params: { page: 1, size: 1 }
  });
};

/**
 * 训练任务数（GET /api/v1/training/tasks）—— 仅取 total。
 */
export const getTrainingTasksTotal = () => {
  return http.request<ResultTable>("get", "/api/v1/training/tasks", {
    params: { page: 1, size: 1 }
  });
};

/**
 * 评测任务数（GET /api/v1/evaluation/tasks）—— 仅取 total。
 */
export const getEvaluationTasksTotal = () => {
  return http.request<ResultTable>("get", "/api/v1/evaluation/tasks", {
    params: { page: 1, size: 1 }
  });
};

export type { Result, ResultTable, PageData };
