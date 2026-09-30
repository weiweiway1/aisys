package com.aisys.auth.init;

import com.aisys.auth.entity.*;
import com.aisys.auth.mapper.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 平台默认数据初始化（DDD 5.2）：幂等创建系统角色、权限目录、默认租户、平台超管与租户管理员账号。
 * <p>默认账号：admin / admin123（平台超管）、tenantadmin / admin123（租户管理员）。
 */
@Component
@Order(10)
public class AuthDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AuthDataSeeder.class);

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public AuthDataSeeder(RoleMapper roleMapper, PermissionMapper permissionMapper,
                          TenantMapper tenantMapper, UserMapper userMapper) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.tenantMapper = tenantMapper;
        this.userMapper = userMapper;
    }

    @Override
    public void run(String... args) {
        seedPermissions();
        seedRolesAndBindings();
        seedDefaultTenant();
        seedUsers();
        log.info("[AuthDataSeeder] 平台默认数据初始化完成");
    }

    private void seedPermissions() {
        // 资源:权限码:名称
        String[][] perms = {
                {"system", "system:user:manage", "用户管理"},
                {"system", "system:tenant:manage", "租户管理"},
                {"system", "system:role:manage", "角色管理"},
                {"system", "system:audit:read", "审计日志查看"},
                {"model", "model:create", "创建模型"},
                {"model", "model:read", "查看模型"},
                {"model", "model:update", "更新模型"},
                {"model", "model:delete", "删除模型"},
                {"model", "model:publish", "发布/下线模型"},
                {"dataset", "dataset:create", "创建数据集"},
                {"dataset", "dataset:read", "查看数据集"},
                {"dataset", "dataset:update", "更新数据集"},
                {"dataset", "dataset:delete", "删除数据集"},
                {"training", "training:create", "创建训练任务"},
                {"training", "training:read", "查看训练任务"},
                {"training", "training:update", "更新训练任务"},
                {"training", "training:delete", "删除训练任务"},
                {"training", "training:control", "启动/停止/暂停任务"},
                {"evaluation", "evaluation:create", "创建评测任务"},
                {"evaluation", "evaluation:read", "查看评测"},
                {"evaluation", "evaluation:update", "更新评测"},
                {"evaluation", "evaluation:delete", "删除评测"},
                {"resource", "resource:read", "查看计算节点"},
                {"resource", "resource:manage", "管理计算节点"},
                {"storage", "storage:read", "查看存储池"},
                {"storage", "storage:manage", "管理存储池/文件"},
                {"notification", "notification:read", "查看通知"},
                {"notification", "notification:manage", "管理通知规则"},
                {"monitor", "monitor:read", "查看监控"}
        };
        for (String[] p : perms) {
            if (permissionMapper.selectByCode(p[1]) == null) {
                Permission perm = new Permission();
                perm.setCode(p[1]);
                perm.setName(p[2]);
                perm.setResource(p[0]);
                permissionMapper.insert(perm);
            }
        }
    }

    private void seedRolesAndBindings() {
        ensureRole("ROLE_PLATFORM_ADMIN", "平台超管", "拥有全部权限，跨租户", true);
        ensureRole("ROLE_TENANT_ADMIN", "租户管理员", "本租户管理权限", true);
        ensureRole("ROLE_USER", "普通用户", "基本读写权限", true);
        ensureRole("ROLE_AGENT", "Agent", "计算节点 Agent 身份", true);

        Map<String, Long> permIds = permissionMapper.selectAll().stream()
                .collect(Collectors.toMap(Permission::getCode, Permission::getId));

        Role platform = roleMapper.selectByCode("ROLE_PLATFORM_ADMIN");
        roleMapper.deleteRolePermissions(platform.getId());
        roleMapper.insertRolePermissions(platform.getId(), List.copyOf(permIds.values()));

        // 租户管理员：除 system:* 与 monitor:read 外的业务权限
        List<Long> tenantAdminPerms = permIds.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("system:") && !e.getKey().equals("monitor:read"))
                .map(Map.Entry::getValue).toList();
        Role tenantAdmin = roleMapper.selectByCode("ROLE_TENANT_ADMIN");
        roleMapper.deleteRolePermissions(tenantAdmin.getId());
        roleMapper.insertRolePermissions(tenantAdmin.getId(), tenantAdminPerms);

        // 普通用户：读 + 创建/更新业务
        List<String> userPermCodes = List.of(
                "model:read", "model:create", "model:update",
                "dataset:read", "dataset:create", "dataset:update",
                "training:read", "training:create", "training:update", "training:control",
                "evaluation:read", "evaluation:create",
                "resource:read", "storage:read", "notification:read");
        List<Long> userPerms = userPermCodes.stream().map(permIds::get).filter(java.util.Objects::nonNull).toList();
        Role user = roleMapper.selectByCode("ROLE_USER");
        roleMapper.deleteRolePermissions(user.getId());
        roleMapper.insertRolePermissions(user.getId(), userPerms);
    }

    private void ensureRole(String code, String name, String desc, boolean system) {
        if (roleMapper.selectByCode(code) == null) {
            Role r = new Role();
            r.setCode(code);
            r.setName(name);
            r.setDescription(desc);
            r.setIsSystem(system);
            roleMapper.insert(r);
        }
    }

    private void seedDefaultTenant() {
        if (tenantMapper.selectByCode("default") == null) {
            Tenant t = new Tenant();
            t.setName("默认租户");
            t.setCode("default");
            t.setStatus("active");
            t.setMaxQuotaBytes(100L * 1024 * 1024 * 1024L); // 100GB
            tenantMapper.insert(t);
            log.info("[AuthDataSeeder] 创建默认租户 id={}", t.getId());
        }
    }

    /** 默认账号密码（与前端登录表单默认值一致） */
    private static final String DEFAULT_PASSWORD = "admin888";

    private void seedUsers() {
        Role platform = roleMapper.selectByCode("ROLE_PLATFORM_ADMIN");
        Role tenantAdmin = roleMapper.selectByCode("ROLE_TENANT_ADMIN");
        Tenant defaultTenant = tenantMapper.selectByCode("default");
        String hashed = encoder.encode(DEFAULT_PASSWORD);

        User admin = userMapper.selectByUsername("admin");
        if (admin == null) {
            admin = new User();
            admin.setUsername("admin");
            admin.setPasswordHash(hashed);
            admin.setNickname("平台管理员");
            admin.setEmail("admin@aisys.io");
            admin.setStatus("active");
            admin.setTenantId(defaultTenant.getId()); // 平台超管归属默认租户：租户作用域写入需 tenant_id；读时仍 BYPASSRLS 跨租户
            userMapper.insert(admin);
            userMapper.insertUserRole(admin.getId(), platform.getId());
            log.info("[AuthDataSeeder] 创建平台超管账号 admin/{} id={}", DEFAULT_PASSWORD, admin.getId());
        } else {
            // 启动时同步默认密码（便于调整默认口令时无需重置库）
            userMapper.updatePassword(admin.getId(), hashed);
        }

        User ta = userMapper.selectByUsername("tenantadmin");
        if (ta == null) {
            ta = new User();
            ta.setUsername("tenantadmin");
            ta.setPasswordHash(hashed);
            ta.setNickname("租户管理员");
            ta.setEmail("tenantadmin@aisys.io");
            ta.setStatus("active");
            ta.setTenantId(defaultTenant.getId());
            userMapper.insert(ta);
            userMapper.insertUserRole(ta.getId(), tenantAdmin.getId());
            log.info("[AuthDataSeeder] 创建租户管理员账号 tenantadmin/{} id={}", DEFAULT_PASSWORD, ta.getId());
        } else {
            userMapper.updatePassword(ta.getId(), hashed);
        }
    }
}
