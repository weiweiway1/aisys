import { http } from "@/utils/http";

type Result = {
  code: number;
  message: string;
  data?: any;
};

type ResultTable = {
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

/** 通知项 */
export interface NotificationItem {
  id: number | string;
  title: string;
  content?: string;
  type?: string;
  level?: string;
  isRead?: boolean;
  read?: boolean;
  createdAt?: string;
  [key: string]: any;
}

/** 获取通知列表 */
export const getNotificationList = (params?: object) => {
  return http.request<ResultTable>("get", "/api/v1/notifications", { params });
};

/** 标记单条通知为已读 */
export const markNotificationRead = (id: number | string) => {
  return http.request<Result>("put", `/api/v1/notifications/${id}/read`);
};

/** 标记全部通知为已读 */
export const markAllNotificationsRead = () => {
  return http.request<Result>("put", "/api/v1/notifications/read-all");
};

/** 获取未读通知数量 */
export const getUnreadCount = () => {
  return http.request<Result>("get", "/api/v1/notifications/unread-count");
};

/* -------------------------------------------------------------------------- */
/*                                通知规则                                      */
/* -------------------------------------------------------------------------- */

/** 通知规则列表 GET /api/v1/notification-rules */
export const listRules = (params?: object) => {
  return http.request<Result>("get", "/api/v1/notification-rules", { params });
};

/** 新建通知规则 POST /api/v1/notification-rules */
export const createRule = (data?: object) => {
  return http.request<Result>("post", "/api/v1/notification-rules", { data });
};

/** 更新通知规则 PUT /api/v1/notification-rules/:id */
export const updateRule = (id: number | string, data?: object) => {
  return http.request<Result>("put", `/api/v1/notification-rules/${id}`, {
    data
  });
};

/** 删除通知规则 DELETE /api/v1/notification-rules/:id */
export const deleteRule = (id: number | string) => {
  return http.request<Result>("delete", `/api/v1/notification-rules/${id}`);
};
