package com.aisys.auth.service;

import com.aisys.auth.dto.MenuNode;
import com.aisys.common.core.constant.CommonConstants;
import com.aisys.common.core.context.UserContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单服务：菜单树对齐原项目（refs/aisys 侧边栏）结构，按当前用户角色过滤（DDD 6.1）。
 * <p>component 路径对应前端 src/views 下视图，前端 addAsyncRoutes 经 import.meta.glob 解析。
 * 系统管理仅平台超管可见（meta.roles=ROLE_PLATFORM_ADMIN）。
 */
@Service
public class MenuService {

    public List<MenuNode> menusForCurrentUser() {
        boolean isAdmin = UserContext.isPlatformAdmin();
        List<MenuNode> all = buildAll();
        return isAdmin ? all : filterByRole(all);
    }

    private List<MenuNode> filterByRole(List<MenuNode> nodes) {
        List<MenuNode> out = new ArrayList<>();
        for (MenuNode n : nodes) {
            if (isPlatformOnly(n)) continue;
            if (n.getChildren() != null) n.setChildren(filterByRole(n.getChildren()));
            out.add(n);
        }
        return out;
    }

    private boolean isPlatformOnly(MenuNode n) {
        Object roles = n.getMeta() == null ? null : n.getMeta().get("roles");
        if (roles instanceof List<?> list) {
            return list.stream().anyMatch(r -> CommonConstants.ROLE_PLATFORM_ADMIN.equals(String.valueOf(r)));
        }
        return false;
    }

    /** 业务菜单（仪表盘由前端常量路由 home.ts 承载，此处不含）—— 对齐原项目侧边栏分组/项/顺序 */
    private List<MenuNode> buildAll() {
        List<MenuNode> root = new ArrayList<>();

        // 模型管理（仅 1 个可见子项，子项设 showParent:true 使父级以二级菜单展示，见 pure-admin 文档）
        // path 与视图 router.push 对齐（/model/list、/model/detail/:id）
        root.add(group("/model", "ModelParent", "模型管理", "ri/box-3-line", 10, List.of(
                leaf("/model/list", "ModelList", "model/list/index", showParentMeta("模型列表")),
                detailLeaf("/model/detail/:id", "ModelDetail", "model/detail/index", "模型详情", "/model/list")
        )));

        // 数据集管理（path 与视图对齐：/dataset/list、/dataset/detail/:id）
        root.add(group("/dataset", "DatasetParent", "数据集管理", "ri/folder-open-line", 20, List.of(
                leaf("/dataset/list", "DatasetList", "dataset/list/index", meta("数据集列表")),
                leaf("/dataset/format-guide", "DatasetFormatGuide", "dataset/format-guide/index", meta("数据格式说明")),
                detailLeaf("/dataset/detail/:id", "DatasetDetail", "dataset/detail/index", "数据集详情", "/dataset/list")
        )));

        // 训练中心（path 与视图对齐：/training/list、/training/monitor/:id）
        root.add(group("/training", "TrainingParent", "训练中心", "ri/rocket-2-line", 30, List.of(
                leaf("/training/list", "TrainingTaskList", "training/list/index", meta("训练任务")),
                leaf("/training/create", "TrainingTaskCreate", "training/create/index", meta("创建任务")),
                detailLeaf("/training/monitor/:id", "TrainingTaskDetail", "training/monitor/index", "任务详情", "/training/list")
        )));

        // 评测中心
        root.add(group("/evaluation", "EvaluationParent", "评测中心", "ri/bar-chart-2-line", 40, List.of(
                leaf("/evaluation/benchmarks", "BenchmarkList", "evaluation/benchmarks/index", meta("评测基准")),
                leaf("/evaluation/tasks", "EvalTaskList", "evaluation/tasks/index", meta("评测任务")),
                leaf("/evaluation/leaderboard", "Leaderboard", "evaluation/leaderboard/index", meta("排行榜"))
        )));

        // 资源管理
        root.add(group("/resources", "ResourceParent", "资源管理", "ri/server-line", 50, List.of(
                leaf("/resources/nodes", "NodeList", "resource/nodes/index", meta("计算节点")),
                leaf("/resources/node-groups", "NodeGroupList", "resource/node-groups/index", meta("节点分组"))
        )));

        // 存储管理
        root.add(group("/storage", "StorageParent", "存储管理", "ri/database-2-line", 60, List.of(
                leaf("/storage/pools", "PoolList", "storage/pools/index", meta("存储池")),
                leaf("/storage/browser", "FileBrowser", "storage/files/index", meta("文件浏览")),
                leaf("/storage/upload-tasks", "UploadTaskList", "storage/upload-tasks/index", meta("上传任务"))
        )));

        // 系统管理（仅平台超管）—— 对齐原项目系统子项
        MenuNode sysGroup = group("/system", "SystemParent", "系统管理", "ri/settings-3-line", 70, List.of(
                leaf("/system/users", "SystemUsers", "system/user/index", meta("用户管理")),
                leaf("/system/tenants", "SystemTenants", "system/tenant/index", meta("租户管理")),
                leaf("/system/roles", "SystemRoles", "system/roles/index", meta("角色管理")),
                leaf("/system/notification-rules", "NotificationRules", "system/notification-rules/index", meta("通知规则")),
                leaf("/system/audit-log", "AuditLog", "system/audit/index", meta("审计日志"))
        ));
        sysGroup.getMeta().put("roles", List.of(CommonConstants.ROLE_PLATFORM_ADMIN));
        root.add(sysGroup);

        return root;
    }

    private MenuNode group(String path, String name, String title, String icon, int rank, List<MenuNode> children) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("icon", icon);
        meta.put("title", title);
        meta.put("rank", rank);
        return MenuNode.of(path, name, meta).children(children);
    }

    /** 单可见子项的 meta：加 showParent:true 使父级以二级菜单展示（pure-admin 文档「如何生成二级菜单 第一种」）。 */
    private Map<String, Object> showParentMeta(String title) {
        Map<String, Object> m = meta(title);
        m.put("showParent", true);
        return m;
    }

    private MenuNode leaf(String path, String name, String component, Map<String, Object> meta) {
        return MenuNode.of(path, name, component, meta);
    }

    private MenuNode detailLeaf(String path, String name, String component, String title, String activePath) {
        Map<String, Object> m = meta(title);
        m.put("showLink", false);
        m.put("activePath", activePath);
        return MenuNode.of(path, name, component, m);
    }

    private Map<String, Object> meta(String title) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        return m;
    }
}
