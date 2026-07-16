import { http } from "@/utils/http";
import { getToken, formatToken } from "@/utils/auth";

/** 数据集类型（与原项目对齐：taskType 为主要分类；type 为次要/兼容字段，自由字符串） */
export type DatasetType = string;

/**
 * 数据集格式（源格式，与原项目 DatasetFormat 对齐）：
 * image_classification: imagenet/csv/json_manifest
 * time_series: ucr_ts/ucr_tsv/csv/arff
 * object_detection: coco/yolo/voc
 * 选项由 utils/datasetFormats.ts 按任务类型级联提供。
 */
export type DatasetFormat = string;

/** 数据集状态 */
export type DatasetStatus = "draft" | "ready" | "deprecated" | string;

/** 数据集对象 */
export interface DatasetItem {
  id: string | number;
  tenantId?: string | number;
  name: string;
  type: DatasetType;
  format: DatasetFormat;
  description?: string;
  status?: DatasetStatus;
  rowCount?: number;
  sizeBytes?: number;
  tags?: string[];
  createdBy?: string | number;
  createdAt?: string;
  updatedAt?: string;
  [key: string]: any;
}

/** 数据集版本对象 */
export interface DatasetVersion {
  id: string | number;
  datasetId?: string | number;
  version: string;
  status?: string;
  description?: string;
  rowCount?: number;
  sizeBytes?: number;
  createdBy?: string | number;
  createdAt?: string;
  [key: string]: any;
}

/** 预览结果（columns + rows） */
export interface DatasetPreviewData {
  columns: Array<{ field: string; label?: string }>;
  rows: Array<Record<string, any>>;
  page?: number;
  size?: number;
  total?: number;
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

/** 数据集列表查询参数 */
export interface DatasetQuery {
  page?: number;
  size?: number;
  keyword?: string;
  type?: DatasetType;
  status?: DatasetStatus;
}

/** 创建/更新数据集入参 */
export interface DatasetPayload {
  name: string;
  /** ML 任务类型：image_classification / time_series / object_detection */
  taskType?: string;
  /** 源格式（随 taskType 级联，见 utils/datasetFormats.ts） */
  format?: DatasetFormat;
  /** 兼容字段（原项目未在创建表单暴露） */
  type?: DatasetType;
  tags?: string[];
  description?: string;
  license?: string;
  storagePoolId?: number | string;
  projectId?: number | string;
}

/** 获取数据集列表 */
export const getDatasetList = (params?: DatasetQuery) => {
  return http.request<ResultTable>("get", "/api/v1/datasets", { params });
};

/** 获取数据集详情 */
export const getDatasetDetail = (id: string | number) => {
  return http.request<Result>("get", `/api/v1/datasets/${id}`);
};

/** 创建数据集 */
export const createDataset = (data: DatasetPayload) => {
  return http.request<Result>("post", "/api/v1/datasets", { data });
};

/** 更新数据集 */
export const updateDataset = (id: string | number, data: DatasetPayload) => {
  return http.request<Result>("put", `/api/v1/datasets/${id}`, { data });
};

/** 删除数据集 */
export const deleteDataset = (id: string | number) => {
  return http.request<Result>("delete", `/api/v1/datasets/${id}`);
};

/** 获取数据集版本列表 */
export const getDatasetVersions = (id: string | number) => {
  return http.request<ResultTable>("get", `/api/v1/datasets/${id}/versions`);
};

/** 获取数据集某版本的预览数据 */
export const getDatasetVersionPreview = (
  id: string | number,
  versionId: string | number,
  params?: { page?: number; size?: number }
) => {
  return http.request<Result>(
    "get",
    `/api/v1/datasets/${id}/versions/${versionId}/preview`,
    { params }
  );
};

/**
 * 取单样本图片（鉴权 XHR blob → objectURL；预览图片网格用）。
 * 后端 row.url 是相对路径占位；前端用此方法带 Authorization 取图并转 blob URL 供 <el-image> 显示。
 * 失败返回 null（前端展示占位）。调用方负责在切页/卸载时 URL.revokeObjectURL 释放。
 */
export const getSampleImageBlob = (
  id: string | number,
  versionId: string | number,
  index: number
): Promise<string | null> => {
  return new Promise(resolve => {
    const xhr = new XMLHttpRequest();
    xhr.open(
      "GET",
      `/api/v1/datasets/${id}/versions/${versionId}/samples/${index}/image`
    );
    xhr.responseType = "blob";
    const t = getToken();
    if (t?.accessToken)
      xhr.setRequestHeader("Authorization", formatToken(t.accessToken));
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300 && xhr.response) {
        resolve(URL.createObjectURL(xhr.response as Blob));
      } else resolve(null);
    };
    xhr.onerror = () => resolve(null);
    xhr.send();
  });
};

/** 创建数据集版本（返回预签名上传 URL） */
export const createDatasetVersion = (
  id: string | number,
  data: { version: string; description?: string }
) => {
  return http.request<Result>("post", `/api/v1/datasets/${id}/versions`, {
    data
  });
};

/** 上传数据集版本（multipart，经后端代理直传；一步完成落库+存储+统计+ready） */
/**
 * 上传数据集版本（multipart，经后端代理直传）。
 * 用 XHR 直发：http 工具默认 Content-Type=application/json 会覆盖 FormData，导致后端 415。
 */
export const uploadDatasetVersion = (
  id: string | number,
  form: FormData,
  onProgress?: (e: { loaded: number; total: number }) => void
) => {
  return new Promise<Result>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", `/api/v1/datasets/${id}/versions/upload`);
    const t = getToken();
    if (t?.accessToken)
      xhr.setRequestHeader("Authorization", formatToken(t.accessToken));
    if (onProgress && xhr.upload) {
      xhr.upload.onprogress = e => onProgress({ loaded: e.loaded, total: e.total });
    }
    xhr.onload = () => {
      try {
        resolve(JSON.parse(xhr.responseText));
      } catch (e) {
        reject(e);
      }
    };
    xhr.onerror = () => reject(new Error("上传失败"));
    xhr.send(form);
  });
};

/** 完成版本上传：回写行数/列信息并置为 ready */
export const completeDatasetVersion = (
  id: string | number,
  versionId: string | number
) => {
  return http.request<Result>(
    "post",
    `/api/v1/datasets/${id}/versions/${versionId}/complete`
  );
};

/** 获取版本统计信息 */
export const getDatasetVersionStatistics = (
  id: string | number,
  versionId: string | number
) => {
  return http.request<Result>(
    "get",
    `/api/v1/datasets/${id}/versions/${versionId}/statistics`
  );
};

/** 删除数据集版本 */
export const deleteDatasetVersion = (
  id: string | number,
  versionId: string | number
) => {
  return http.request<Result>(
    "delete",
    `/api/v1/datasets/${id}/versions/${versionId}`
  );
};

/** 导出/下载版本（返回预签名下载 URL） */
export const exportDatasetVersion = (
  id: string | number,
  versionId: string | number
) => {
  return http.request<Result>(
    "get",
    `/api/v1/datasets/${id}/versions/${versionId}/export`
  );
};
