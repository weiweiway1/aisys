import { http } from "@/utils/http";

/**
 * 模型类型（任务类型，与数据集 taskType 完全一致）：
 * image_classification(图像分类) / time_series(时序分类) / object_detection(目标检测)。
 */
export type ModelType =
  | "image_classification"
  | "time_series"
  | "object_detection"
  | string;

/** 模型类型选项（label/value）—— 供列表筛选/创建表单复用 */
export const MODEL_TYPE_OPTIONS = [
  { label: "图像分类", value: "image_classification" },
  { label: "时序分类", value: "time_series" },
  { label: "目标检测", value: "object_detection" }
];

/** 模型类型 → 中文标签 */
export const modelTypeLabel = (t?: string) =>
  MODEL_TYPE_OPTIONS.find(o => o.value === t)?.label || t || "-";

/** 模型状态 */
export type ModelStatus = "draft" | "published" | "deprecated";

/** 模型可见性 */
export type ModelVisibility = "private" | "tenant" | "public";

/** 模型对象 */
export interface ModelItem {
  id: string | number;
  tenantId?: string | number;
  name: string;
  type: ModelType;
  framework: string;
  description?: string;
  status: ModelStatus;
  visibility?: ModelVisibility;
  createdBy?: string | number;
  createdAt?: string;
}

/** 模型版本对象 */
export interface ModelVersion {
  id: string | number;
  modelId?: string | number;
  version: string;
  status?: string;
  description?: string;
  storagePath?: string;
  fileSize?: number;
  checksum?: string;
  config?: any;
  createdBy?: string | number;
  createdAt?: string;
  [key: string]: any;
}

/* ======================== 模型版本管理（后端 S3 分片上传） ======================== */

/** 创建版本请求 */
export interface ModelVersionCreateRequest {
  version: string;
  config?: Record<string, any>;
}

/** 创建版本响应 */
export interface ModelVersionResponse {
  id: string | number;
  modelId: string | number;
  version: string;
  storagePath: string;
  fileSize?: number;
  checksum?: string;
  status: string;
  config?: any;
  createdBy?: string | number;
  createdAt?: string;
}

/** 上传初始化请求 */
export interface ModelUploadInitRequest {
  fileSize: number;
  fileHash?: string;
  chunkSize?: number;
}

/** 单个分片预签名地址 */
export interface ChunkUrl {
  partNumber: number;
  url: string;
}

/** 上传初始化响应 */
export interface ModelUploadInitResponse {
  uploadId: string | null;     // null 表示秒传
  storagePath: string;
  chunkCount: number;
  chunkSize: number;
  chunkUrls: ChunkUrl[];
  dedup: boolean;              // true=命中秒传，无需再传
}

/** 已完成分片回执 */
export interface CompletedPart {
  partNumber: number;
  etag: string;
}

/** 上传完成请求 */
export interface ModelUploadCompleteRequest {
  uploadId: string;
  fileSize: number;
  checksum?: string;
  parts?: CompletedPart[];
}

/** 上传完成响应 */
export interface ModelUploadCompleteResponse {
  versionId: string | number;
  storagePath: string;
  fileSize: number;
  checksum: string;
  status: string;
}

type Result = {
  code: number;
  message: string;
  data?: any;
};

type ResultTable = {
  code: number;
  message: string;
  data?: {
    items: any[];
    total: number;
    page: number;
    size: number;
    totalPages?: number;
  };
};

/** 模型列表查询参数 */
export interface ModelQuery {
  page?: number;
  size?: number;
  keyword?: string;
  status?: ModelStatus;
  projectId?: string | number;
}

/** 创建/更新模型入参 */
export interface ModelPayload {
  name: string;
  type: ModelType;
  framework: string;
  description?: string;
}

/** 获取模型列表 */
export const getModelList = (params?: ModelQuery) => {
  return http.request<ResultTable>("get", "/api/v1/models", { params });
};

/** 获取模型详情 */
export const getModelDetail = (id: string | number) => {
  return http.request<Result>("get", `/api/v1/models/${id}`);
};

/** 创建模型 */
export const createModel = (data: ModelPayload) => {
  return http.request<Result>("post", "/api/v1/models", { data });
};

/** 更新模型 */
export const updateModel = (id: string | number, data: ModelPayload) => {
  return http.request<Result>("put", `/api/v1/models/${id}`, { data });
};

/** 删除模型 */
export const deleteModel = (id: string | number) => {
  return http.request<Result>("delete", `/api/v1/models/${id}`);
};

/** 发布模型 */
export const publishModel = (id: string | number) => {
  return http.request<Result>("post", `/api/v1/models/${id}/publish`);
};

/** 废弃模型 */
export const deprecateModel = (id: string | number) => {
  return http.request<Result>("post", `/api/v1/models/${id}/deprecate`);
};

/** 获取模型版本列表 */
export const getModelVersions = (id: string | number) => {
  return http.request<ResultTable>("get", `/api/v1/models/${id}/versions`);
};

/* ======================== 模型版本 & 上传接口 ======================== */

/** 创建模型版本（status=creating） */
export const createModelVersion = (
  id: string | number,
  data: ModelVersionCreateRequest
) => {
  return http.request<Result>("post", `/api/v1/models/${id}/versions`, { data });
};

/**
 * 初始化分片上传（后端 S3 createMultipart + 返回预签名 URL 列表）。
 * 若命中秒传（dedup=true），客户端无需再上传文件，直接调 complete 即可。
 */
export const initiateModelUpload = (
  modelId: string | number,
  versionId: string | number,
  data: ModelUploadInitRequest
) => {
  return http.request<Result>(
    "post",
    `/api/v1/models/${modelId}/versions/${versionId}/upload/initiate`,
    { data }
  );
};

/** 完成分片上传（S3 completeMultipart + 更新 DB status=ready） */
export const completeModelUpload = (
  modelId: string | number,
  versionId: string | number,
  data: ModelUploadCompleteRequest
) => {
  return http.request<Result>(
    "post",
    `/api/v1/models/${modelId}/versions/${versionId}/upload/complete`,
    { data }
  );
};
