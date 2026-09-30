const Layout = () => import("@/layout/index.vue");

// 仪表盘（对齐原项目菜单首项 /dashboard）
export default {
  path: "/",
  name: "Home",
  component: Layout,
  redirect: "/dashboard",
  meta: {
    icon: "ri/dashboard-2-line",
    title: "仪表盘",
    rank: 0
  },
  children: [
    {
      path: "/dashboard",
      name: "Dashboard",
      component: () => import("@/views/home/index.vue"),
      meta: {
        title: "仪表盘",
        keepAlive: true
      }
    }
  ]
} satisfies RouteConfigsTable;
