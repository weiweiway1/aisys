import { http } from "@/utils/http";
import { getToken, formatToken } from "@/utils/auth";

/** 统一响应：业务数据 */
type Result = {
  code: number;
  message: string;
  data?: any;
};

/** 统一响应：分页列表 */
type ResultTable = {
  code: number;
  message: string;
  data?: {
    items: any[];
    total: number;
    page: number;
    size: number;
  };
};

/** 存储池 */
export interface StoragePool {
  id: number;
  name: string;
  type: string;
  /** 配额（字节），可能为空表示不限 */
  quotaBytes?: number;
  [key: string]: any;
}

/** 存储池用量 */
export interface StoragePoolUsage {
  /** 已用字节 */
  usedBytes?: number;
  /** 配额字节 */
  quotaBytes?: number;
  /** 空闲字节 */
  freeBytes?: number;
  /** 用量百分比 0-100 */
  usagePercent?: number;
  [key: string]: any;
}

/** 文件/对象条目 */
export interface StorageObject {
  key: string;
  size?: number;
  lastModified?: string;
  [key: string]: any;
}

/**
 * 存储池列表（GET /api/v1/storage/pools）。
 * 后端返回数组；此处兼容分页结构。
 */
export const getStoragePools = (params?: { keyword?: string; page?: number; size?: number }) => {
  return http.request<Result>("get", "/api/v1/storage/pools", { params });
};

/** 存储池用量（GET /api/v1/storage/pools/{id}/usage） */
export const getStoragePoolUsage = (id: number) => {
  return http.request<Result>("get", `/api/v1/storage/pools/${id}/usage`);
};

/** 文件浏览（GET /api/v1/files/browse?poolId&prefix&limit） */
export const browseFiles = (params: {
  poolId: number;
  prefix?: string;
  limit?: number;
}) => {
  return http.request<ResultTable | Result>("get", "/api/v1/files/browse", {
    params
  });
};

/** 新建存储池（POST /api/v1/storage/pools） */
export const createPool = (data: Partial<StoragePool>) => {
  return http.request<Result>("post", "/api/v1/storage/pools", { data });
};

/** 更新存储池（PUT /api/v1/storage/pools/{id}） */
export const updatePool = (id: number | string, data: Partial<StoragePool>) => {
  return http.request<Result>("put", `/api/v1/storage/pools/${id}`, { data });
};

/** 删除存储池（DELETE /api/v1/storage/pools/{id}） */
export const deletePool = (id: number | string) => {
  return http.request<Result>("delete", `/api/v1/storage/pools/${id}`);
};

/** 批量删除文件（DELETE /api/v1/files?poolId=，body {keys}）。poolId 必须走 query，否则后端取不到会回退默认池删错。 */
export const deleteFiles = (params: { poolId: number; keys: string[] }) => {
  return http.request<Result>("delete", "/api/v1/files", {
    params: { poolId: params.poolId },
    data: { keys: params.keys }
  });
};

/* ----------------------- 大文件分片上传（浏览器→后端→存储池） ----------------------- */
/** 默认分片大小 50MB（S3 分片最小 5MB，最多 10000 片 → 单文件最大约 488GB，覆盖 100GB） */
export const UPLOAD_CHUNK_SIZE = 50 * 1024 * 1024;

/** 发起上传任务（后端 createMultipart + 建任务） */
export const initiateUploadTask = (data: {
  poolId?: number;
  path: string;
  fileName: string;
  size: number;
  chunkSize?: number;
}) => {
  return http.request<Result>("post", "/api/v1/files/upload-tasks", { data });
};

/** 完成上传任务（后端 completeMultipart 组装入池） */
export const completeUploadTask = (taskId: number | string) => {
  return http.request<Result>(
    "post",
    `/api/v1/files/upload-tasks/${taskId}/complete`
  );
};

/** 取消/中止上传任务（后端 abort S3 multipart，释放未完成分片） */
export const cancelUploadTask = (taskId: number | string) => {
  return http.request<Result>("delete", `/api/v1/files/upload-tasks/${taskId}`);
};

/** 任务列表（状态查询） */
export const listUploadTasks = (params?: {
  status?: string;
  page?: number;
  size?: number;
}) => {
  return http.request<ResultTable>("get", "/api/v1/files/upload-tasks", {
    params
  });
};

/** 任务详情 */
export const getUploadTask = (taskId: number | string) => {
  return http.request<Result>("get", `/api/v1/files/upload-tasks/${taskId}`);
};

/** 上传单片（XHR，浏览器→后端，带进度）。后端再写 S3 part 到存储池。 */
export const uploadTaskChunk = (
  taskId: number | string,
  partNumber: number,
  chunk: Blob,
  onProgress?: (loaded: number, total: number) => void
) => {
  return new Promise<Result>((resolve, reject) => {
    const form = new FormData();
    form.append("file", chunk);
    const xhr = new XMLHttpRequest();
    xhr.open(
      "POST",
      `/api/v1/files/upload-tasks/${taskId}/chunks/${partNumber}`
    );
    const t = getToken();
    if (t?.accessToken)
      xhr.setRequestHeader("Authorization", formatToken(t.accessToken));
    if (onProgress && xhr.upload) {
      xhr.upload.onprogress = e => onProgress(e.loaded, e.total);
    }
    xhr.onload = () => {
      try {
        resolve(JSON.parse(xhr.responseText));
      } catch (e) {
        reject(e);
      }
    };
    xhr.onerror = () => reject(new Error("分片上传失败"));
    xhr.send(form);
  });
};
