export const meta = {
  name: 'aisys-puretable',
  description: '将 aisys-web 列表页从原生 el-table+el-pagination 转换为 pure-admin-table(PureTable)',
  phases: [{ title: 'Convert', detail: '每个 agent 转换 2-3 个视图为 PureTable' }]
};
const WEB = '/root/aisys/aisys-web';

const PATTERN = '转换规则：用 pure-admin-table(PureTable) 替换原生 el-table+el-pagination。PureTable 已全局注册(无需 import)。转换步骤：1.把 el-table-column 抽成 TableColumnList 数组(columns)，状态 tag/操作按钮用 cellRenderer 返回 h() 渲染(需 import { h } from vue)。2.模板里把 <el-table>...</el-table> + <el-pagination> 替换为 <pure-table row-key="id" :loading="loading" :data="dataList" :columns="columns" :pagination="pagination" />。3.脚本里加 const pagination = reactive({ total: 0, currentPage: 1, pageSize: 20, background: true, layout: "total, sizes, prev, pager, next, jumper" }); 在 fetchList 成功后同步 pagination.total=total, pagination.currentPage=query.page, pagination.pageSize=query.size。4.通过 @page-size-change="onSizeChange" @page-current-change="onCurrentChange" 驱动(或直接用 PureTable 内置)。保留原有搜索栏、新建弹窗等。参考实现：' + WEB + '/src/views/account-settings/components/SecurityLog.vue。必须保留所有现有功能(搜索/创建/删除/状态tag/操作按钮)。确保 h() 中使用 ElTag/ElButton/ElMessageBox 等 Element Plus 组件(需 import)。不改 API 调用、不改路由逻辑。';

const SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { views: { type: 'array', items: { type: 'string' } }, notes: { type: 'string' } },
  required: ['views']
};

phase('Convert');

const R1 = () => agent('你在重构 ' + WEB + ' 的前端视图，从原生 el-table+el-pagination 改为 pure-admin-table(PureTable)。\n\n' + PATTERN + '\n\n## 任务：转换以下 3 个视图\n' + WEB + '/src/views/model/list/index.vue\n' + WEB + '/src/views/dataset/list/index.vue\n' + WEB + '/src/views/training/list/index.vue\n\n用 Read 读每个文件全文 → 用 Write 重写(替换 el-table/el-pagination 为 pure-table，抽 columns 数组，加 pagination reactive)。保留所有现有功能。', { label: 'convert:model-dataset-training', phase: 'Convert', schema: SCHEMA });

const R2 = () => agent('你在重构 ' + WEB + ' 的前端视图，从原生 el-table+el-pagination 改为 pure-admin-table(PureTable)。\n\n' + PATTERN + '\n\n## 任务：转换以下 3 个视图\n' + WEB + '/src/views/evaluation/benchmarks/index.vue\n' + WEB + '/src/views/evaluation/tasks/index.vue\n' + WEB + '/src/views/evaluation/leaderboard/index.vue\n\n用 Read 读每个文件全文 → 用 Write 重写。保留所有现有功能。', { label: 'convert:evaluation', phase: 'Convert', schema: SCHEMA });

const R3 = () => agent('你在重构 ' + WEB + ' 的前端视图，从原生 el-table+el-pagination 改为 pure-admin-table(PureTable)。\n\n' + PATTERN + '\n\n## 任务：转换以下 4 个视图\n' + WEB + '/src/views/system/user/index.vue\n' + WEB + '/src/views/system/tenant/index.vue\n' + WEB + '/src/views/notification/list/index.vue\n' + WEB + '/src/views/resource/nodes/index.vue\n\n用 Read 读每个文件全文 → 用 Write 重写。保留所有现有功能。', { label: 'convert:system-notif-resource', phase: 'Convert', schema: SCHEMA });

const results = (await parallel([R1, R2, R3])).filter(Boolean);
log('PureTable 转换完成');
return { results };
