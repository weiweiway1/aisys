export const meta = {
  name: 'aisys-crud-impl',
  description: '实现系统管理(角色CRUD+通知规则CRUD)、存储管理(存储池CRUD+文件操作)、资源管理(节点分组CRUD)的完整功能',
  phases: [{ title: 'Implement', detail: '后端端点+前端API+前端视图并行实现' }]
};
const ROOT = '/root/aisys';

const COMMON_FE = '前端工程在 ' + ROOT + '/aisys-web。后端统一响应 {code:0成功,data}。已全局注册 pure-table(PureTable)，columns 配置驱动 + reactive<PaginationProps> 内置分页（参考 src/views/account-settings/components/SecurityLog.vue）。CRUD 弹窗用 template 内联 el-dialog（项目约定）。按钮级权限用 v-perms 指令。';

phase('Implement');

const R1 = () => agent('你在 ' + ROOT + '/aisys-auth 和 ' + ROOT + '/aisys-resource 添加缺失的后端 CRUD 端点。\n\n## 任务1：角色 CRUD（aisys-auth）\n读 aisys-auth/src/main/java/com/aisys/auth/controller/RoleController.java（当前只有 GET list + GET permissions）。添加：\n- POST /api/v1/roles — 创建角色（body: {code,name,description}），调用 RoleMapper.insert\n- PUT /api/v1/roles/{id} — 更新角色\n- DELETE /api/v1/roles/{id} — 删除角色（检查 isSystem，系统内置不可删）\n- GET /api/v1/roles/{id}/permissions — 返回该角色的 permission ID 列表\n- PUT /api/v1/roles/{id}/permissions — 分配权限（body: {permissionIds:[...]}），调用 RoleMapper.deleteRolePermissions + insertRolePermissions\n读 RoleMapper + RoleMapper.xml 确认方法签名。@AuditLog 标注 mutating 方法。用 ApiResponse 包装。添加 @PreAuthorize("hasRole(\'PLATFORM_ADMIN\')") 到类级。\n\n## 任务2：节点分组 CRUD（aisys-resource）\n读 aisys-resource/src/main/java/com/aisys/resource/controller/NodeGroupController.java（当前只有 GET list）。添加：\n- POST /api/v1/resources/node-groups — 创建\n- PUT /api/v1/resources/node-groups/{id} — 更新\n- DELETE /api/v1/resources/node-groups/{id} — 删除\n读 NodeGroupMapper + 其 XML，添加 insert/update/delete 方法（如缺）。NodeGroup entity 字段参考已有。\n\n用 Read 读代码 → 用 Write/Edit 添加。不改已有方法。', { label: 'backend:crud', phase: 'Implement' });

const R2 = () => agent(COMMON_FE + '\n\n## 任务：系统管理 CRUD 视图\n### 1. api/system.ts — 添加角色 CRUD API\n读 ' + ROOT + '/aisys-web/src/api/system.ts，添加：createRole(data), updateRole(id,data), deleteRole(id), getRolePermissions(id), updateRolePermissions(id, permissionIds), getAllPermissions()。全部用 http.request 调 /api/v1/roles 对应端点。\n### 2. views/system/roles/index.vue — 完整重写为 CRUD\n读现有文件 → 重写：PureTable 列表(isSystem tag + 操作列含编辑/删除/权限按钮) + 搜索栏 + 新增/编辑弹窗(el-dialog + el-form: code/name/description) + 权限分配弹窗(el-dialog + checkbox-group 按模块分组显示权限)。从 getAllPermissions 获取权限列表按 resource 分组，getRolePermissions 获取已选，updateRolePermissions 提交。\n### 3. api/notification.ts — 添加通知规则 CRUD\n读现有文件，添加：listRules(), createRule(data), updateRule(id,data), deleteRule(id)。调 /api/v1/notification-rules。\n### 4. views/system/notification-rules/index.vue — 完整重写为 CRUD\nPureTable 列表(eventType tag + channels tag + enabled tag + 编辑/删除操作) + 新增/编辑弹窗(el-form: eventType/targetType/channels(多选)/webhookUrl/enabled开关)。\n\n用 Read 读 → Write 重写。', { label: 'fe:system', phase: 'Implement' });

const R3 = () => agent(COMMON_FE + '\n\n## 任务：存储管理 + 资源管理 CRUD 视图\n### 1. api/storage.ts — 补全存储 API\n读 ' + ROOT + '/aisys-web/src/api/storage.ts，添加缺失函数：createPool(data), updatePool(id,data), deletePool(id), getUploadUrl(params), deleteFiles(params)。调 /api/v1/storage/pools 和 /api/v1/files 对应端点。\n### 2. views/storage/pools/index.vue — 重写为 CRUD + 用量\nPureTable 列表(name/type/endpoint/quota + el-progress 用量条/status tag/操作含详情/编辑/删除) + 新增/编辑弹窗(name/type/endpoint/bucket/accessKey/secretKey/quotaBytes)。getStoragePoolUsage 获取用量显示进度条。\n### 3. views/storage/files/index.vue — 添加面包屑+上传+下载+删除\n读现有 → 增强：el-select 选存储池 + el-breadcrumb 路径导航 + 上传按钮(getUploadUrl→presigned PUT) + 下载按钮(getDownloadUrl) + 删除按钮(deleteFiles) + 文件列表表格。\n### 4. api/resource.ts — 添加节点分组 CRUD\n添加：listNodeGroups(), createNodeGroup(data), updateNodeGroup(id,data), deleteNodeGroup(id)。调 /api/v1/resources/node-groups。\n### 5. views/resource/node-groups/index.vue — 重写为 CRUD\nPureTable 列表(name/description/createdAt + 编辑/删除操作) + 新增/编辑弹窗(name/description)。\n\n用 Read 读 → Write 重写。', { label: 'fe:storage-resource', phase: 'Implement' });

const results = (await parallel([R1, R2, R3])).filter(Boolean);
log('CRUD 实现完成');
return { results };
