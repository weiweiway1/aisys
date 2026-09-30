export const meta = {
  name: 'aisys-frontend',
  description: 'Generate aisys-web business modules (api + router + views) on vue-pure-admin, wired to the verified backend API',
  phases: [{ title: 'Build Frontend', detail: 'one agent per business module' }]
};
const ROOT = '/root/aisys/aisys-web';

const CONV = [
'你在为 vue-pure-admin (v7, Vue3+Vite+TS+Element Plus+Pinia) 项目 aisys-web 添加业务模块。后端已运行并验证，API 契约如下，务必严格对齐。',
'',
'## 后端统一响应（已验证）',
'- 所有接口返回 { code:number, message:string, data:T }；code===0 为成功。pure-admin 的 http 响应拦截器已 return response.data，所以 http.request 的结果就是该对象（即 .data 为业务数据）。',
'- 分页：data = { items:[...], total:number, page:number, size:number, totalPages:number }。',
'- 鉴权：登录 POST /api/v1/auth/login 返回 { accessToken, refreshToken, expiresIn(秒), userId, username, nickname, roles:[...], permissions:[...] }。前端已配置（src/api/user.ts 的 getLogin 已做 expiresIn→expires 映射；http 白名单已含 /api/v1/auth/login、/refresh）。其余请求带 Authorization: Bearer <token>（pure-admin 自动注入）。',
'- 平台超管 admin/admin123（无租户，做系统管理）；租户管理员 tenantadmin/admin123（tenant=1，做业务 CRUD）。业务页面用 tenantadmin 登录。',
'',
'## 通用约定',
'- API 文件放 src/api/<module>.ts：`import { http } from "@/utils/http";` 用 `http.request<ResultType>("get"|"post"|..., "/api/v1/...", { params } 或 { data })`。返回 Promise<ApiResponse>。',
'- 定义类型：`type Result = { code:number; message:string; data?: any }; type ResultTable = { code:number; message:string; data?: { items:any[]; total:number; page:number; size:number } };`',
'- 路由模块放 src/router/modules/<module>.ts，导出 default 一个路由对象（参考已有 home.ts/list.ts 的结构）：父级 path + meta{icon(ri/图标名),title,rank}，children 每项 { path,name,component:()=>import("@/views/..."), meta:{title,keepAlive:true,auths:[...]} }。父级不写 component（layout 由根路由承载）。rank 用数字（参考 src/router/enums.ts 的枚举，或直接给数字，越小越靠前）。',
'- 页面放 src/views/<module>/<page>/index.vue。优先用 Element Plus（el-table/el-form/el-dialog/el-pagination/el-button/el-input/el-select/el-tag）+ @pureadmin/table（可选）。用 `<script setup lang="ts">`。',
'- 权限：按钮级用 v-auth 或 hasAuth（基于 meta.auths）或 v-perms（基于 permissions）。平台管理类页面（system）用 meta.roles 限定 PLATFORM_ADMIN。',
'- 统一用 reactive 表单 + onMounted 加载列表。删除/创建成功后 ElMessage.success + reload。',
'',
'## 重要：示例路由已由编排器删除',
'src/router/modules/ 下示例模块（able/board/chatai/...）已删除，保留 home.ts/error.ts/remaining.ts。你只负责新增自己模块的文件，**不要**删除文件、**不要**修改 home.ts 或其它模块的文件（home 模块例外）。rank 用数字字面量（如 10/20/30...，越小越靠前；home 用 0）。不要 import src/router/enums.ts（避免耦合）。',
'',
'## 输出',
'把文件写到 ' + ROOT + '/src/... 下。不要运行 pnpm build（由编排器统一构建）。完成后用 schema 汇报写了哪些文件、删除了哪些示例、风险。',
].join('\n');

const MODS = {
home: '## 模块：首页仪表盘（home.ts + views/home/index.vue）\n改造 src/router/modules/home.ts 指向新仪表盘 views/home/index.vue。仪表盘展示：4 个统计卡片（模型数 GET /api/v1/models?size=1 取 total、数据集数 GET /api/v1/datasets?size=1 取 total、训练任务数 GET /api/v1/training/tasks?size=1 取 total、评测数 GET /api/v1/evaluation/tasks?size=1 取 total），用 el-row/el-col + el-card；下方一个 ECharts 折线占位（训练 loss 趋势，可静态示例数据）。rank 最小（菜单第一）。标题"工作台"。',

model: '## 模块：模型管理（model.ts + api/model.ts + views/model/list + views/model/detail）\nAPI：GET /api/v1/models (params: page,size,keyword,status,projectId) → ResultTable items=模型{ id,tenantId,name,type,framework,description,status,visibility,createdBy,createdAt }；POST /api/v1/models (body{name,type,framework,description})；GET /api/v1/models/{id}；PUT /api/v1/models/{id}；DELETE /api/v1/models/{id}；POST /api/v1/models/{id}/publish；POST /api/v1/models/{id}/deprecate。\n页面：列表页 el-table（列：名称/类型/框架/状态tag/创建时间/操作[详情/发布/删除]）+ 顶部搜索(keyword)+新建按钮(弹窗表单)+ el-pagination。状态用 el-tag 着色(draft灰/published绿/deprecated橙)。详情页 views/model/detail/index.vue（路由 /model/detail/:id，showLink:false）展示模型基本信息 + 版本列表 GET /api/v1/models/{id}/versions。按钮用 v-perms="model:create"等（permissions 已有这些码）。',

dataset: '## 模块：数据集（dataset.ts + api/dataset.ts + views/dataset/list）\nAPI：GET /api/v1/datasets (page,size,keyword)；POST /api/v1/datasets (body{name,type,format,description})；DELETE /api/v1/datasets/{id}；GET /api/v1/datasets/{id}/versions；GET /api/v1/datasets/{id}/versions/{vid}/preview?page&size。\n列表页：表格(名称/类型/格式/状态/行数/操作) + 搜索 + 新建弹窗 + 分页。可选详情页展示版本 + 预览表格(preview 的 rows+columns)。',

training: '## 模块：训练任务（training.ts + api/training.ts + views/training/list + views/training/monitor）\nAPI：GET /api/v1/training/tasks (page,size,keyword,status)；POST /api/v1/training/tasks (body{name,image,command,resourceSpec:{gpuCount,cpu,memoryBytes},hyperparameters})；POST /api/v1/training/tasks/{id}/start；POST /api/v1/training/tasks/{id}/stop；GET /api/v1/training/tasks/{id}/logs?page&size；GET /api/v1/training/tasks/{id}/metrics?startTimestamp&endTimestamp。\n列表页：表格(名称/状态tag[queued蓝/running绿running动画/completed绿/failed红]/进度/节点/创建时间/操作[启动/停止/监控]) + 新建弹窗(name/image/command/resourceSpec.gpuCount=0) + 分页。监控页 views/training/monitor/index.vue (路由 /training/monitor/:id, showLink:false)：顶部任务状态条；ECharts 折线（用 @/plugins/echarts 的 useECharts，从 GET metrics 取 loss/lr 画线，metrics 是 [{ts,step,metrics:{loss,lr,accuracy}}]，若无数据画示例）；右侧/下方训练日志面板（GET logs，滚动展示，level 着色）。',

evaluation: '## 模块：评测（evaluation.ts + api/evaluation.ts + views/evaluation/benchmarks + views/evaluation/leaderboard）\nAPI：GET /api/v1/benchmarks (page,size)；POST /api/v1/benchmarks (body{name,category,description,promptTemplate})；GET /api/v1/evaluation/tasks (page,size)；POST /api/v1/evaluation/tasks (body{name,benchmarkId,modelVersionIds:[1]})；POST /api/v1/evaluation/tasks/{id}/start；GET /api/v1/evaluation/leaderboard?benchmarkId=&sortBy=accuracy；GET /api/v1/evaluation/tasks/{id}/results。\n页面：benchmark 列表(名称/类别/操作) + 新建弹窗；排行榜页：GET leaderboard 展示表格(排名/模型/accuracy 等 overallScores 字段) + 顶部 ECharts 雷达图(注意：需在 src/plugins/echarts.ts 的 use([...]) 里追加 RadarChart 注册，否则雷达空白)对比 top 模型。',

resource: '## 模块：计算节点（resource.ts + api/resource.ts + views/resource/nodes）\nAPI：GET /api/v1/resources/nodes (page,size)；GET /api/v1/resources/nodes/{id}；PUT /api/v1/resources/nodes/{id}/labels；POST /api/v1/resources/nodes/{id}/maintenance；DELETE /api/v1/resources/nodes/{id}/maintenance；GET /api/v1/resources/nodes/{id}/metrics。\n列表页：卡片或表格(节点名/agentId/IP/状态tag[online绿/offline灰/maintenance橙]/GPU信息/标签) + 维护模式切换按钮。',

storage: '## 模块：存储（storage.ts + api/storage.ts + views/storage/pools + views/storage/files）\nAPI：GET /api/v1/storage/pools；GET /api/v1/storage/pools/{id}/usage；GET /api/v1/files/browse?poolId&prefix&limit；POST /api/v1/files/upload-url (body{poolId,path,fileName})；GET /api/v1/files/download-url?poolId&path。\n页面：存储池列表(名称/类型/配额/用量)；文件浏览页（browse 返回对象列表，表格展示 key/size/lastModified + 下载按钮）。',

notification: '## 模块：通知（notification.ts + api/notification.ts + views/notification/list）\nAPI：GET /api/v1/notifications (page,size)；PUT /api/v1/notifications/{id}/read；PUT /api/v1/notifications/read-all；GET /api/v1/notifications/unread-count。\n列表页：通知表格(标题/类型/级别tag/时间/已读) + 标记已读/全部已读按钮。可在顶栏加未读数 badge（可选）。',

system: '## 模块：系统管理（system.ts，meta.roles: ["ROLE_PLATFORM_ADMIN"]）\nAPI：用户 GET /api/v1/users (page,size,keyword)；租户 GET /api/v1/tenants (page,size)；角色 GET /api/v1/roles；审计 GET /api/v1/audit-logs (page,size,actorType,resource,action,startTime,endTime)。\n页面：views/system/user/index.vue（用户表格+新建弹窗）；views/system/tenant/index.vue（租户表格+新建）；views/system/audit/index.vue（审计日志只读表格，按 actorType/resource/时间过滤）。这些页面仅平台超管可见。',
};

const SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: {
    module: { type: 'string' },
    filesWritten: { type: 'array', items: { type: 'string' } },
    removedDemos: { type: 'array', items: { type: 'string' } },
    risks: { type: 'array', items: { type: 'string' } }
  },
  required: ['module','filesWritten']
};

phase('Build Frontend');
log('Generating frontend business modules in parallel...');
const keys = ['home','model','dataset','training','evaluation','resource','storage','notification','system'];
const results = await parallel(keys.map((k) => () =>
  agent(CONV + '\n\n' + MODS[k] + '\n\n## 本任务\n完整实现 ' + k + ' 模块（api + router + views）。文件写到 ' + ROOT + '/src/ 下。务必产出可被 vite 构建通过的 TS/Vue 代码（类型自洽，不要 import 不存在的符号）。不要运行构建。',
    { label: 'fe:' + k, phase: 'Build Frontend', schema: SCHEMA })
));
const ok = results.filter(Boolean);
log('Generated ' + ok.length + '/9 modules: ' + ok.map((r) => r.module).join(', '));
return { modules: ok };
