export const meta = {
  name: 'aisys-fe-audit',
  description: '通读 vue-pure-admin 官方文档(refs/web-docs)，全面审查 aisys-web 前端，找出与文档/官方模式/后端契约不符的问题',
  phases: [{ title: 'Audit', detail: '每个 agent 读对应文档+审查对应前端区域，返回结构化问题清单' }]
};
const DOCS = '/root/aisys/refs/web-docs/01.指南';
const WEB = '/root/aisys/aisys-web';
const COMMON = [
  '官方文档在 ' + DOCS + '（中文 md）。前端工程在 ' + WEB + '。后端已运行，统一响应 {code:0成功,data}，分页 data={items,total,page,size}；登录 POST /api/v1/auth/login 返回 {accessToken,refreshToken,expiresIn(秒),username,nickname,roles[],permissions[]}；菜单 GET /api/v1/auth/menus 返回 pure-admin 动态路由格式（后端按角色过滤）。前端已接入真实后端（无 mock）。',
  '审查目标：找出会导致【运行时崩溃 / 功能不可用 / 与官方文档明确不符 / 与后端契约不匹配】的问题。不要报告纯风格/可读性问题。',
  '每个问题给出：file(相对WEB的路径或绝对路径):行号、问题、具体修复方案(可直接动手改的最小改动)。只报高置信度、确定的问题。',
  '用 Read/Grep/Bash 读文档与前端代码，不要改代码（修复由编排器统一做）。返回 schema。',
];

const SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: {
    area: { type: 'string' },
    issues: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          file: { type: 'string' },
          line: { type: 'string' },
          problem: { type: 'string' },
          fix: { type: 'string' },
          severity: { type: 'string' }
        },
        required: ['file', 'problem', 'fix']
      }
    },
    notes: { type: 'string' }
  },
  required: ['area', 'issues']
};

phase('Audit');

const A1 = () => agent(COMMON.join('\n') + '\n\n## 你的任务：路由与菜单\n先读 ' + DOCS + '/01.指南/07.路由和菜单.md 全文。然后审查：' + WEB + '/src/router/utils.ts(initRouter/addAsyncRoutes/handleAsyncRoutes)、src/router/index.ts、src/router/modules/home.ts 与 remaining.ts、src/api/routes.ts(getAsyncRoutes→/api/v1/auth/menus)、以及后端菜单契约(aisys-auth/src/main/java/com/aisys/auth/dto/MenuNode.java 与 service/MenuService.java)。重点核对：动态路由的叶子是否缺 children、父级 redirect 是否由前端自动处理、getAsyncRoutes 返回结构、initRouter 对失败的容错。返回问题清单。', { label: 'audit:router', phase: 'Audit', schema: SCHEMA });

const A2 = () => agent(COMMON.join('\n') + '\n\n## 你的任务：HTTP与鉴权\n先读 ' + DOCS + '/01.指南/08.http请求.md 全文。审查：' + WEB + '/src/utils/http/index.ts(PureHttp 请求/响应拦截、白名单、token注入、刷新逻辑)、src/utils/auth.ts(getToken/setToken/expires)、src/api/user.ts(getLogin/refreshTokenApi/getMe 的路径与 expiresIn→expires 映射)、src/store/modules/user.ts(loginByUsername/handRefreshToken)。核对：白名单是否含 /api/v1/auth/login 与 /refresh、刷新接口路径、过期字段换算、刷新轮换。返回问题清单。', { label: 'audit:http', phase: 'Audit', schema: SCHEMA });

const A3 = () => agent(COMMON.join('\n') + '\n\n## 你的任务：RBAC权限\n先读 ' + DOCS + '/02.进阶/05.RBAC权限.md 全文。审查：' + WEB + '/src/directives/、src/components/ReAuth、src/components/RePerms(若存在)、以及 router/modules 中 meta.roles / meta.auths 的使用。核对：菜单级(roles)与按钮级(auths/perms)是否符合 pure-admin 机制；后端返回的 roles 形如 ROLE_PLATFORM_ADMIN，前端 hasRole/hasPerms 是否匹配。返回问题清单。', { label: 'audit:rbac', phase: 'Audit', schema: SCHEMA });

const A4 = () => agent(COMMON.join('\n') + '\n\n## 你的任务：业务视图与API契约\n读必要文档(布局 06.md、平台配置 05.md)。审查 ' + WEB + '/src/api/{model,dataset,training,evaluation,resource,storage,notification,system}.ts 与 src/views 下各业务页面，逐个核对前端调用路径与后端实际接口(见 aisys-*/controller 与 DDD.md)是否一致(路径、方法、请求体、分页字段 items/total)。找出：调用了不存在的端点、字段名不匹配、会导致页面报错的问题。返回问题清单(可分组列)。', { label: 'audit:views', phase: 'Audit', schema: SCHEMA });

const A5 = () => agent(COMMON.join('\n') + '\n\n## 你的任务：图标/构建/国际化\n先读 ' + DOCS + '/02.进阶/01.图标.md 与 ' + DOCS + '/01.指南/09.打包和部署.md。审查：' + WEB + '/src/plugins/echarts.ts(是否注册了用到的图表组件如 RadarChart)、router modules 的 meta.icon 是否用合法的 iconify 名(如 ri:xxx)、build/plugins.ts(mock 是否已彻底移除)、是否有未安装依赖被引用。返回问题清单。', { label: 'audit:build', phase: 'Audit', schema: SCHEMA });

const results = (await parallel([A1, A2, A3, A4, A5])).filter(Boolean);
const all = results.flatMap(r => (r.issues || []).map(i => ({ area: r.area, ...i })));
log('审查完成：共 ' + all.length + ' 个问题，按严重度：' + all.filter(i => i.severity === 'critical').length + ' critical');
return { areas: results, totalIssues: all.length, critical: all.filter(i => i.severity === 'critical') };
