import { http } from "@/utils/http";

// 菜单由后端动态下发（GET /api/v1/auth/menus，DDD 6.1）：auth 服务按当前用户角色返回菜单树，
// 前端 getAsyncRoutes→initRouter→addAsyncRoutes 经 import.meta.glob 解析 component 路径为视图。
// 不再使用 mock；业务菜单不再在前端静态定义。
type Result = {
  code: number;
  message: string;
  data: Array<any>;
};

export const getAsyncRoutes = () => {
  return http.request<Result>("get", "/api/v1/auth/menus");
};
