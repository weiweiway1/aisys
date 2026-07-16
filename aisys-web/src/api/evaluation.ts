import { http } from "@/utils/http";

/** 通用响应：{ code, message, data }，code===0 为成功 */
type Result = {
  code: number;
  message: string;
  data?: any;
};

/** 分页响应：data.items / total / page / size / totalPages */
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

/** 评测集（benchmark）创建/列表 */
export interface Benchmark {
  id?: number;
  name: string;
  category?: string;
  description?: string;
  promptTemplate?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** 评测任务创建/列表 */
export interface EvaluationTask {
  id?: number;
  name: string;
  benchmarkId?: number;
  benchmarkName?: string;
  modelVersionIds?: Array<number>;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** 排行榜条目（overallScores 为动态字段，如 accuracy / precision 等） */
export interface LeaderboardEntry {
  modelVersionId?: number;
  modelName?: string;
  modelVersion?: string;
  rank?: number;
  overallScores?: Record<string, number>;
  totalScore?: number;
  accuracy?: number;
}

/** 评测结果条目 */
export interface EvaluationResultItem {
  modelVersionId?: number;
  modelName?: string;
  modelVersion?: string;
  benchmarkId?: number;
  benchmarkName?: string;
  status?: string;
  totalQuestions?: number;
  correctCount?: number;
  accuracy?: number;
  overallScores?: Record<string, number>;
}

/* ------------------------------ 评测集 ------------------------------ */

/** 评测集列表（分页） GET /api/v1/benchmarks?page=&size= */
export const getBenchmarkList = (params?: {
  page?: number;
  size?: number;
}) => {
  return http.request<ResultTable>("get", "/api/v1/benchmarks", { params });
};

/** 新建评测集 POST /api/v1/benchmarks */
export const createBenchmark = (data: {
  name: string;
  category?: string;
  description?: string;
  promptTemplate?: string;
  datasetVersionIds?: number[];
}) => {
  return http.request<Result>("post", "/api/v1/benchmarks", { data });
};

/* --------------------------- 评测任务 --------------------------- */

/** 评测任务列表（分页） GET /api/v1/evaluation/tasks?page=&size= */
export const getEvaluationTaskList = (params?: {
  page?: number;
  size?: number;
}) => {
  return http.request<ResultTable>("get", "/api/v1/evaluation/tasks", {
    params
  });
};

/** 新建评测任务 POST /api/v1/evaluation/tasks
 * body: { name, benchmarkId, modelVersionIds:[1] } */
export const createEvaluationTask = (data: {
  name: string;
  benchmarkId: number;
  modelVersionIds: Array<number>;
}) => {
  return http.request<Result>("post", "/api/v1/evaluation/tasks", { data });
};

/** 启动评测任务 POST /api/v1/evaluation/tasks/{id}/start */
export const startEvaluationTask = (id: number) => {
  return http.request<Result>(
    "post",
    `/api/v1/evaluation/tasks/${id}/start`
  );
};

/** 获取评测任务结果 GET /api/v1/evaluation/tasks/{id}/results */
export const getEvaluationTaskResults = (id: number) => {
  return http.request<Result>(
    "get",
    `/api/v1/evaluation/tasks/${id}/results`
  );
};

/* --------------------------- 排行榜 --------------------------- */

/** 排行榜 GET /api/v1/evaluation/leaderboard?benchmarkId=&sortBy=accuracy */
export const getLeaderboard = (params?: {
  benchmarkId?: number;
  sortBy?: string;
}) => {
  return http.request<Result>("get", "/api/v1/evaluation/leaderboard", {
    params
  });
};
