export const meta = {
  name: 'aisys-component-audit',
  description: '审查 aisys-web 每个页面的组件使用是否正确、是否重复实现了组件库已有组件',
  phases: [{ title: 'Audit', detail: '4 agent 并行审查全部视图' }]
};

const DOCS = '/root/aisys/refs/web-docs/01.指南';
const TPL = '/root/aisys/refs/vue-pure-admin-main';
const WEB = '/root/aisys/aisys-web';

const COMMON = [
  '你在审查 vue-pure-admin(v7) 前端项目的组件使用质量。前端工程在 ' + WEB + '，官方文档在 ' + DOCS + '，模板参考在 ' + TPL + '。',
  '',
  '## 组件库关键组件（已安装 @pureadmin/table ^3.3.0 / @pureadmin/descriptions ^1.2.1）',
  '1. **pure-admin-table (PureTable)**：二次封装 el-table，columns 配置驱动，模板示例 ' + TPL + '/src/views/table/base/status.vue。',
  '2. **ReDialog (addDialog)**：函数式弹框，模板示例 ' + TPL + '/src/views/components/dialog/index.vue。',
  '3. **Element Plus 内置组件**：el-table/el-form/el-dialog/el-upload/el-pagination 等。',
  '',
  '## 审查目标',
  'A. 组件使用错误（Element Plus props/slots/events 误用，导致渲染异常或功能缺失）。',
  'B. 重复实现已有组件（手写 el-table+el-pagination 而应用 PureTable；template 手写 el-dialog 而应用 addDialog）。',
  'C. 漏用已有组件（搜索表单/CRUD弹框/文件上传等可用内置组件更简洁实现）。',
  '',
  '输出每个问题：file, line, category(A/B/C), problem, fix(推荐组件+最小方案), severity。只报确定的。不改代码。返回 schema。'
].join('\n');

const SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: {
    area: { type: 'string' },
    issues: { type: 'array', items: { type: 'object', additionalProperties: true,
      properties: { file: {type:'string'}, line: {type:'string'}, category: {type:'string'}, problem: {type:'string'}, fix: {type:'string'}, severity: {type:'string'} },
      required: ['file','problem','fix'] } },
    summary: { type: 'string' }
  },
  required: ['area','issues']
};

phase('Audit');

const views = (grp) => '审查 ' + WEB + '/src/views/' + grp + '/ 下所有 .vue。';

const R1 = () => agent(COMMON + '\n\n## 任务\n读 ' + TPL + '/src/views/table/base/status.vue 和 ' + TPL + '/src/views/components/dialog/index.vue 学 PureTable/addDialog。审查 ' + views('model') + views('dataset'), { label: 'audit:model-dataset', phase: 'Audit', schema: SCHEMA });
const R2 = () => agent(COMMON + '\n\n## 任务\n审查 ' + views('training') + views('evaluation'), { label: 'audit:training-eval', phase: 'Audit', schema: SCHEMA });
const R3 = () => agent(COMMON + '\n\n## 任务\n审查 ' + views('resource') + views('storage') + views('notification'), { label: 'audit:res-store-notif', phase: 'Audit', schema: SCHEMA });
const R4 = () => agent(COMMON + '\n\n## 任务\n审查 ' + views('system') + views('home') + views('login'), { label: 'audit:system-home', phase: 'Audit', schema: SCHEMA });

const results = (await parallel([R1, R2, R3, R4])).filter(Boolean);
const all = results.flatMap(r => (r.issues || []).map(i => ({ area: r.area, ...i })));
log('组件审查完成：共 ' + all.length + ' 个问题');
return { areas: results, total: all.length };
