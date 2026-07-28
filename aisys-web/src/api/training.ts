import { http } from "@/utils/http";

/**
 * 训练任务模块 API（对齐后端 Training Service：/api/v1/training）
 * 统一响应 { code, message, data }，code===0 为成功。
 * 分页 data = { items, total, page, size, totalPages }。
 */

type Result = {
  code: number;
  message: string;
  data?: any;
};

type ResultTable = {
  code: number;
  message: string;
  data?: {
    items: Array<any>;
    total: number;
    page: number;
    size: number;
    totalPages?: number;
  };
};

/** 资源规格 */
export interface ResourceSpec {
  gpuCount: number;
  cpu: number;
  memoryBytes: number;
}

/** 训练任务 */
export interface TrainingTask {
  id: string;
  name: string;
  image: string;
  command: string;
  resourceSpec: ResourceSpec;
  hyperparameters?: Record<string, string>;
  status: string;
  progress?: number;
  node?: string;
  createdAt?: string;
  updatedAt?: string;
  /** 训练产物（检查点/权重文件），仅在详情接口返回 */
  checkpoints?: Checkpoint[];
}

/** 训练检查点（权重文件） */
export interface Checkpoint {
  id: number;
  taskId: number;
  step: number;
  storagePath: string;
  loss?: number;
  metrics?: Record<string, any>;
  isActive?: boolean;
  createdAt?: string;
}

/** 单条训练日志 */
export interface TrainingLog {
  id?: number;
  taskId?: number;
  ts?: string | number;
  loggedAt?: string;
  step?: number;
  level: string;
  message: string;
}

/** 单条训练指标 */
export interface TrainingMetricPoint {
  ts: number;
  step: number;
  metrics: {
    loss?: number;
    lr?: number;
    accuracy?: number;
    [key: string]: any;
  };
}

/** 创建任务请求体 */
export interface CreateTaskPayload {
  name: string;
  image: string;
  command: string;
  resourceSpec: ResourceSpec;
  hyperparameters?: Record<string, string>;
}

/** 获取训练任务列表 */
export const getTaskList = (params?: object) => {
  return http.request<ResultTable>("get", "/api/v1/training/tasks", {
    params
  });
};

/** 创建训练任务 */
export const createTask = (data: CreateTaskPayload) => {
  return http.request<Result>("post", "/api/v1/training/tasks", {
    data
  });
};

/** 启动任务 */
export const startTask = (id: string) => {
  return http.request<Result>("post", `/api/v1/training/tasks/${id}/start`);
};

/** 停止任务 */
export const stopTask = (id: string) => {
  return http.request<Result>("post", `/api/v1/training/tasks/${id}/stop`);
};

/** 获取任务日志 */
export const getTaskLogs = (id: string, params?: object) => {
  return http.request<ResultTable>("get", `/api/v1/training/tasks/${id}/logs`, {
    params
  });
};

/** 获取任务指标（后端参数名为 from/to） */
export const getTaskMetrics = (
  id: string,
  params?: { from?: number; to?: number; startTimestamp?: number; endTimestamp?: number }
) => {
  const { from, to, startTimestamp, endTimestamp } = params || {};
  return http.request<Result>("get", `/api/v1/training/tasks/${id}/metrics`, {
    params: { from: from ?? startTimestamp, to: to ?? endTimestamp }
  });
};

/** 获取任务详情（用于监控页顶部状态条） */
export const getTaskDetail = (id: string) => {
  return http.request<Result>("get", `/api/v1/training/tasks/${id}`);
};
