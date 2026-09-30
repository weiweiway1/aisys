import { http } from "@/utils/http";

/**
 * 系统管理相关接口（DDD 6.x 对齐 Platform Admin / Admin Service）。
 *
 * 后端统一响应：{ code:number, message:string, data:T }；code===0 为成功。
 * pure-admin 的 http 响应拦截器已 return response.data，因此 http.request
 * 的返回值就是该对象，其 .data 即业务数据。
 *
 * 分页：data = { items:[...], total:number, page:number, size:number, totalPages:number }。
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

/* -------------------------------------------------------------------------- */
/*                                  用户管理                                    */
/* -------------------------------------------------------------------------- */

/** 用户列表查询参数 */
export interface UserQuery {
  page?: number;
  size?: number;
  keyword?: string;
  tenantId?: number;
  role?: string;
  status?: string;
}

/** 用户分页列表 GET /api/v1/users */
export const getUserPage = (params?: UserQuery) => {
  return http.request<ResultTable>("get", "/api/v1/users", { params });
};

/** 新建用户 POST /api/v1/users */
export const createUser = (data?: object) => {
  return http.request<Result>("post", "/api/v1/users", { data });
};

/** 用户详情 GET /api/v1/users/:id */
export const getUserDetail = (id: number | string) => {
  return http.request<Result>("get", `/api/v1/users/${id}`);
};

/** 更新用户 PUT /api/v1/users/:id */
export const updateUser = (id: number | string, data?: object) => {
  return http.request<Result>("put", `/api/v1/users/${id}`, { data });
};

/** 删除用户 DELETE /api/v1/users/:id */
export const deleteUser = (id: number | string) => {
  return http.request<Result>("delete", `/api/v1/users/${id}`);
};

/** 分配用户角色（覆盖） PUT /api/v1/users/:id/roles，body { roleIds: [...] } */
export const assignUserRoles = (
  id: number | string,
  roleIds: Array<number | string>
) => {
  return http.request<Result>("put", `/api/v1/users/${id}/roles`, {
    data: { roleIds }
  });
};

/* -------------------------------------------------------------------------- */
/*                                  租户管理                                    */
/* -------------------------------------------------------------------------- */

export interface TenantQuery {
  page?: number;
  size?: number;
  keyword?: string;
}

/** 租户分页列表 GET /api/v1/tenants */
export const getTenantPage = (params?: TenantQuery) => {
  return http.request<ResultTable>("get", "/api/v1/tenants", { params });
};

/** 新建租户 POST /api/v1/tenants */
export const createTenant = (data?: object) => {
  return http.request<Result>("post", "/api/v1/tenants", { data });
};

/** 更新租户 PUT /api/v1/tenants/:id */
export const updateTenant = (id: number | string, data?: object) => {
  return http.request<Result>("put", `/api/v1/tenants/${id}`, { data });
};

/** 删除租户 DELETE /api/v1/tenants/:id */
export const deleteTenant = (id: number | string) => {
  return http.request<Result>("delete", `/api/v1/tenants/${id}`);
};

/* -------------------------------------------------------------------------- */
/*                                  角色管理                                    */
/* -------------------------------------------------------------------------- */

/** 角色列表 GET /api/v1/roles */
export const getRoleList = () => {
  return http.request<Result>("get", "/api/v1/roles");
};

/** 角色查询参数 */
export interface RoleQuery {
  page?: number;
  size?: number;
  keyword?: string;
}

/** 角色分页列表 GET /api/v1/roles */
export const getRolePage = (params?: RoleQuery) => {
  return http.request<ResultTable>("get", "/api/v1/roles", { params });
};

/** 新建角色 POST /api/v1/roles */
export const createRole = (data?: object) => {
  return http.request<Result>("post", "/api/v1/roles", { data });
};

/** 更新角色 PUT /api/v1/roles/:id */
export const updateRole = (id: number | string, data?: object) => {
  return http.request<Result>("put", `/api/v1/roles/${id}`, { data });
};

/** 删除角色 DELETE /api/v1/roles/:id */
export const deleteRole = (id: number | string) => {
  return http.request<Result>("delete", `/api/v1/roles/${id}`);
};

/** 获取角色已分配的权限 ID 列表 GET /api/v1/roles/:id/permissions */
export const getRolePermissions = (id: number | string) => {
  return http.request<Result>("get", `/api/v1/roles/${id}/permissions`);
};

/** 更新角色的权限（覆盖） PUT /api/v1/roles/:id/permissions */
export const updateRolePermissions = (
  id: number | string,
  permissionIds: Array<number | string>
) => {
  return http.request<Result>("put", `/api/v1/roles/${id}/permissions`, {
    data: { permissionIds }
  });
};

/** 获取全部权限列表 GET /api/v1/roles/permissions */
export const getAllPermissions = () => {
  return http.request<Result>("get", "/api/v1/roles/permissions");
};

/* -------------------------------------------------------------------------- */
/*                                  审计日志                                    */
/* -------------------------------------------------------------------------- */

export interface AuditLogQuery {
  page?: number;
  size?: number;
  actorType?: string;
  actorId?: string;
  resource?: string;
  action?: string;
  startTime?: string;
  endTime?: string;
}

/** 审计日志分页列表 GET /api/v1/audit-logs */
export const getAuditLogPage = (params?: AuditLogQuery) => {
  return http.request<ResultTable>("get", "/api/v1/audit-logs", { params });
};
