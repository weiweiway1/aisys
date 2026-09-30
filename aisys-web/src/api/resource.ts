import { http } from "@/utils/http";

/** 节点状态：online 在线 / offline 离线 / maintenance 维护中 */
export type NodeStatus = "online" | "offline" | "maintenance";

/** 计算节点（Compute Node，字段对齐后端 NodeResponse） */
export interface ComputeNode {
  /** 节点 ID */
  id: number | string;
  /** Agent ID */
  agentId?: string;
  /** 节点名称（后端字段） */
  nodeName?: string;
  /** 兼容别名 */
  name?: string;
  /** IP 地址（后端字段） */
  ipAddress?: string;
  /** 兼容别名 */
  ip?: string;
  /** 节点状态 */
  status: NodeStatus;
  /** Agent 版本 */
  agentVersion?: string;
  /** 操作系统信息 */
  osInfo?: string;
  /** 内存/磁盘（字节） */
  totalMemory?: number;
  totalDisk?: number;
  /** CPU 核数 */
  cpuCores?: number;
  /** GPU 数量 */
  gpuCount?: number;
  /** 运行中任务数 */
  runningTasks?: number;
  /** 标签 */
  labels?: Record<string, string> | Array<{ key: string; value: string }>;
  /** 最近心跳 */
  lastHeartbeatAt?: string;
  /** 集群/区域等附加信息（可选） */
  [key: string]: any;
}

/** 统一响应（单对象） */
export type Result = {
  code: number;
  message: string;
  data?: any;
};

/** 统一响应（分页） */
export type ResultTable = {
  code: number;
  message: string;
  data?: {
    /** 列表数据 */
    items: Array<any>;
    /** 总条目数 */
    total: number;
    /** 当前页数 */
    page: number;
    /** 每页显示条目个数 */
    size: number;
    /** 总页数 */
    totalPages?: number;
  };
};

/** 计算节点列表查询参数 */
export interface NodeListQuery {
  page?: number;
  size?: number;
  [key: string]: any;
}

/** 标签更新请求体 */
export interface NodeLabelsBody {
  /** 标签键值对 */
  labels: Record<string, string>;
}

/**
 * 获取计算节点列表（GET /api/v1/resources/nodes）。
 * 后端分页：data = { items, total, page, size, totalPages }。
 */
export const getNodeList = (params?: NodeListQuery) => {
  return http.request<ResultTable>("get", "/api/v1/resources/nodes", { params });
};

/**
 * 获取计算节点详情（GET /api/v1/resources/nodes/{id}）。
 */
export const getNodeDetail = (id: number | string) => {
  return http.request<Result>("get", `/api/v1/resources/nodes/${id}`);
};

/**
 * 更新计算节点标签（PUT /api/v1/resources/nodes/{id}/labels）。
 */
export const updateNodeLabels = (
  id: number | string,
  data: NodeLabelsBody
) => {
  return http.request<Result>("put", `/api/v1/resources/nodes/${id}/labels`, {
    data
  });
};

/**
 * 进入维护模式（POST /api/v1/resources/nodes/{id}/maintenance）。
 */
export const enterMaintenance = (id: number | string) => {
  return http.request<Result>(
    "post",
    `/api/v1/resources/nodes/${id}/maintenance`
  );
};

/**
 * 退出维护模式（DELETE /api/v1/resources/nodes/{id}/maintenance）。
 */
export const exitMaintenance = (id: number | string) => {
  return http.request<Result>(
    "delete",
    `/api/v1/resources/nodes/${id}/maintenance`
  );
};

/**
 * 获取计算节点指标（GET /api/v1/resources/nodes/{id}/metrics）。
 */
export const getNodeMetrics = (id: number | string) => {
  return http.request<Result>("get", `/api/v1/resources/nodes/${id}/metrics`);
};

/** 删除计算节点（DELETE /api/v1/resources/nodes/{id}） */
export const deleteNode = (id: number | string) => {
  return http.request<Result>("delete", `/api/v1/resources/nodes/${id}`);
};

/** 更新计算节点（分配/变更分组）（PUT /api/v1/resources/nodes/{id}） */
export const updateNode = (
  id: number | string,
  data: { nodeGroupId?: number | string | null }
) => {
  return http.request<Result>("put", `/api/v1/resources/nodes/${id}`, { data });
};

/** 远程命令执行结果 */
export interface CommandResult {
  exitCode: number;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

/** 在节点上执行远程命令（POST /api/v1/resources/nodes/{id}/command） */
export const executeCommand = (
  id: number | string,
  data: { command: string; timeout?: number }
) => {
  return http.request<Result>("post", `/api/v1/resources/nodes/${id}/command`, {
    data
  });
};

/* --------------------------------- 节点分组 -------------------------------- */

/** 节点分组 */
export interface NodeGroup {
  id: number | string;
  name: string;
  description?: string;
  createdAt?: string;
  [key: string]: any;
}

/** 节点分组创建/更新请求体 */
export interface NodeGroupBody {
  name: string;
  description?: string;
}

/** 获取节点分组列表（GET /api/v1/resources/node-groups） */
export const listNodeGroups = () => {
  return http.request<Result>("get", "/api/v1/resources/node-groups");
};

/** 获取单个节点分组（GET /api/v1/resources/node-groups/{id}） */
export const getNodeGroup = (id: number | string) => {
  return http.request<Result>("get", `/api/v1/resources/node-groups/${id}`);
};

/** 新建节点分组（POST /api/v1/resources/node-groups） */
export const createNodeGroup = (data: NodeGroupBody) => {
  return http.request<Result>("post", "/api/v1/resources/node-groups", {
    data
  });
};

/** 更新节点分组（PUT /api/v1/resources/node-groups/{id}） */
export const updateNodeGroup = (
  id: number | string,
  data: NodeGroupBody
) => {
  return http.request<Result>("put", `/api/v1/resources/node-groups/${id}`, {
    data
  });
};

/** 删除节点分组（DELETE /api/v1/resources/node-groups/{id}） */
export const deleteNodeGroup = (id: number | string) => {
  return http.request<Result>("delete", `/api/v1/resources/node-groups/${id}`);
};
