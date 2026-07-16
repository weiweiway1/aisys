import { http } from "@/utils/http";

export type UserResult = {
  code: number;
  message: string;
  data: {
    /** 头像 */
    avatar: string;
    /** 用户名 */
    username: string;
    /** 昵称 */
    nickname: string;
    /** 当前登录用户的角色 */
    roles: Array<string>;
    /** 按钮级别权限 */
    permissions: Array<string>;
    /** `token` */
    accessToken: string;
    /** 用于调用刷新`accessToken`的接口时所需的`token` */
    refreshToken: string;
    /** `accessToken`的过期时间（格式'xxxx/xx/xx xx:xx:xx'） */
    expires: Date;
  };
};

export type RefreshTokenResult = {
  code: number;
  message: string;
  data: {
    /** `token` */
    accessToken: string;
    /** 用于调用刷新`accessToken`的接口时所需的`token` */
    refreshToken: string;
    /** `accessToken`的过期时间（格式'xxxx/xx/xx xx:xx:xx'） */
    expires: Date;
  };
};

export type UserInfo = {
  /** 头像 */
  avatar: string;
  /** 用户名 */
  username: string;
  /** 昵称 */
  nickname: string;
  /** 邮箱 */
  email: string;
  /** 联系电话 */
  phone: string;
  /** 简介 */
  description: string;
};

export type UserInfoResult = {
  code: number;
  message: string;
  data: UserInfo;
};

type ResultTable = {
  code: number;
  message: string;
  data?: {
    /** 列表数据 */
    list: Array<any>;
    /** 总条目数 */
    total?: number;
    /** 每页显示条目个数 */
    pageSize?: number;
    /** 当前页数 */
    currentPage?: number;
  };
};

/**
 * 登录（DDD 6.2 对齐 Auth Service：POST /api/v1/auth/login）。
 * 后端返回 { accessToken, refreshToken, expiresIn(秒), username, nickname, roles, permissions }；
 * 此处映射为 pure-admin 所需的 UserResult（expires 由 expiresIn 换算）。
 */
export const getLogin = (data?: object) => {
  return http.request<UserResult>("post", "/api/v1/auth/login", { data }).then(res => {
    const d: any = (res as any)?.data ?? {};
    return {
      code: (res as any)?.code ?? 0,
      message: (res as any)?.message ?? "success",
      data: {
        avatar: "",
        username: d.username ?? "",
        nickname: d.nickname ?? "",
        roles: d.roles ?? [],
        permissions: d.permissions ?? [],
        accessToken: d.accessToken,
        refreshToken: d.refreshToken,
        expires: new Date(Date.now() + (d.expiresIn ?? 86400) * 1000)
      }
    } as UserResult;
  });
};

/**
 * 刷新 token（POST /api/v1/auth/refresh）。映射 expiresIn → expires。
 */
export const refreshTokenApi = (data?: object) => {
  return http.request<RefreshTokenResult>("post", "/api/v1/auth/refresh", { data }).then(res => {
    const d: any = (res as any)?.data ?? {};
    return {
      code: (res as any)?.code ?? 0,
      message: (res as any)?.message ?? "success",
      data: {
        accessToken: d.accessToken,
        refreshToken: d.refreshToken,
        expires: new Date(Date.now() + (d.expiresIn ?? 86400) * 1000)
      }
    } as RefreshTokenResult;
  });
};

/** 当前用户信息（GET /api/v1/auth/me） */
export const getMine = (data?: object) => {
  return http.request<UserInfoResult>("get", "/api/v1/auth/me", { data });
};

/** 更新个人资料（PUT /api/v1/auth/profile） */
export const updateMine = (data: {
  nickname?: string;
  email?: string;
  phone?: string;
}) => {
  return http.request<{ code: number; message?: string }>("put", "/api/v1/auth/profile", {
    data
  });
};

/** 修改密码（PUT /api/v1/auth/password） */
export const changePassword = (data: { oldPassword: string; newPassword: string }) => {
  return http.request<{ code: number; message?: string }>("put", "/api/v1/auth/password", {
    data
  });
};

/** 账户设置-个人安全日志（平台暂未提供，占位） */
export const getMineLogs = (params?: object) => {
  return http.request<ResultTable>("get", "/api/v1/audit-logs", { params });
};
