<template>
  <div class="ai-board p-4">
    <div class="head">
      <div>
        <div class="title">{{ isWork ? '报告工作台' : '概览' }}</div>
        <div class="sub">{{ isWork ? '已生成的调查评价报告和章节进度' : '知识库、小流域、隐患点与防治对象' }}</div>
      </div>
      <a-button :loading="loading" @click="load">刷新</a-button>
    </div>

    <a-row :gutter="[12, 12]">
      <a-col v-for="item in kpis" :key="item.label" :xs="12" :sm="8" :lg="isWork ? 6 : 4">
        <div class="kpi" :style="{ borderTopColor: item.color }" @click="item.path && go(item.path)">
          <div class="kpi-label">{{ item.label }}</div>
          <div class="kpi-value">{{ item.value }}</div>
          <div class="kpi-foot">{{ item.foot }}</div>
        </div>
      </a-col>
    </a-row>

    <a-row v-if="isWork" :gutter="[12, 12]" class="section">
      <a-col :xs="24" :lg="8">
        <a-card title="章节状态" :bordered="false" :loading="loading">
          <Pie v-if="reportStatus.length" :chartData="reportStatus" height="280px" />
          <a-empty v-else-if="!loading" description="还没有生成章节" />
        </a-card>
      </a-col>
      <a-col :xs="24" :lg="16">
        <a-card title="全部报告" :bordered="false" :loading="loading">
          <div class="report-tools">
            <a-input-search v-model:value="keyword" allow-clear placeholder="搜索流域或批次" style="width: 240px" />
            <a @click="go('/super/airag/shreport/ShReportAssistant')">去报告助手继续生成</a>
          </div>
          <a-table
            :columns="reportColumns"
            :data-source="filteredReports"
            :pagination="{ pageSize: 8, showSizeChanger: false }"
            size="small"
            :row-key="reportKey"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'lymc'">{{ record.lymc || record.lybm }}</template>
              <template v-else-if="column.key === 'action'">
                <a @click="openReport(record)">预览</a>
                <a-divider type="vertical" />
                <a @click="exportReport(record)">导出</a>
              </template>
            </template>
          </a-table>
        </a-card>
      </a-col>
    </a-row>
    <ReportChapterDrawer v-if="isWork" v-model:open="drawerOpen" :report="activeReport" />

    <template v-else>
      <a-row :gutter="[12, 12]" class="section">
        <a-col :xs="24" :lg="8">
          <a-card title="隐患点类型" :bordered="false" :loading="loading">
            <Bar v-if="hazardTypes.length" :chartData="hazardTypes" height="260px" :option="barOption" seriesColor="#d46b08" />
            <a-empty v-else-if="!loading" />
          </a-card>
        </a-col>
        <a-col :xs="24" :lg="8">
          <a-card title="防治对象类型" :bordered="false" :loading="loading">
            <Bar v-if="preventionTypes.length" :chartData="preventionTypes" height="260px" :option="barOption" seriesColor="#1677ff" />
            <a-empty v-else-if="!loading" />
          </a-card>
        </a-col>
        <a-col :xs="24" :lg="8">
          <a-card title="断面类型" :bordered="false" :loading="loading">
            <Pie v-if="sectionTypes.length" :chartData="sectionTypes" height="260px" />
            <a-empty v-else-if="!loading" />
          </a-card>
        </a-col>
      </a-row>

      <a-row :gutter="[12, 12]" class="section">
        <a-col :xs="24" :lg="10">
          <a-card title="知识库" :bordered="false" :loading="loading">
            <a-table :columns="kbColumns" :data-source="bases" :pagination="false" size="small" row-key="name" />
          </a-card>
        </a-col>
        <a-col :xs="24" :lg="14">
          <a-card title="快捷入口" :bordered="false">
            <div class="entries">
              <button v-for="item in entries" :key="item.title" type="button" class="entry" @click="go(item.path)">
                <div class="entry-title">{{ item.title }}</div>
                <div class="entry-desc">{{ item.desc }}</div>
              </button>
            </div>
            <div class="risk-line">
              隐患识别 {{ risk.total }} 次，成功 {{ risk.success }}，失败 {{ risk.fail }}。
              <a @click="go('/super/airag/shreport/ShMapDataAdmin?view=aiRisk')">查看识别明细</a>
            </div>
          </a-card>
        </a-col>
      </a-row>
    </template>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import Pie from '/@/components/chart/Pie.vue';
  import Bar from '/@/components/chart/Bar.vue';
  import { message } from 'ant-design-vue';
  import { downloadReport } from '/@/views/super/airag/shreport/ShReportAssistant.api';
  import { AiBoardOverview, getAiBoardOverview, NameValue, ReportRecent } from './api';
  import ReportChapterDrawer from './ReportChapterDrawer.vue';

  const props = withDefaults(defineProps<{ mode?: 'home' | 'work' }>(), { mode: 'home' });
  const isWork = computed(() => props.mode === 'work');
  const router = useRouter();
  const loading = ref(false);
  const data = ref<AiBoardOverview | null>(null);

  const barOption = {
    grid: { left: 8, right: 8, bottom: 4, top: 24, containLabel: true },
    xAxis: { type: 'category', axisLabel: { interval: 0, rotate: 28, fontSize: 11 } },
  };

  const keyword = ref('');
  const drawerOpen = ref(false);
  const activeReport = ref<ReportRecent | null>(null);
  const reportColumns = [
    { title: '流域', key: 'lymc', ellipsis: true },
    { title: '批次', dataIndex: 'batchNo', width: 150, ellipsis: true },
    { title: '章节', dataIndex: 'chapters', width: 60 },
    { title: '已通过', dataIndex: 'passed', width: 70 },
    { title: '待审核', dataIndex: 'pending', width: 70 },
    { title: '失败', dataIndex: 'failed', width: 60 },
    { title: '更新时间', dataIndex: 'updateTime', width: 140 },
    { title: '操作', key: 'action', width: 110 },
  ];
  const kbColumns = [
    { title: '知识库', dataIndex: 'name', ellipsis: true },
    { title: '文档', dataIndex: 'docCount', width: 80 },
    { title: '状态', dataIndex: 'status', width: 80 },
  ];

  const entries = [
    { title: '山洪报告助手', desc: '按流域生成调查评价报告', path: '/super/airag/shreport/ShReportAssistant' },
    { title: '山洪一张图', desc: '查看流域、隐患点与防治对象', path: '/super/airag/shreport/ShReportOneMap' },
    { title: 'AI知识库', desc: '调查成果与编制规范', path: '/super/airag/aiknowledge/AiKnowledgeBaseList' },
    { title: '识别明细', desc: '每次隐患点 AI 识别的留痕', path: '/super/airag/shreport/ShMapDataAdmin?view=aiRisk' },
  ];

  const report = computed(() => data.value?.report);
  const knowledge = computed(() => data.value?.knowledge);
  const map = computed(() => data.value?.map);
  const risk = computed(() => data.value?.aiRisk || { total: 0, success: 0, fail: 0 });
  const recent = computed(() => report.value?.recent || []);
  const filteredReports = computed(() => {
    const q = keyword.value.trim();
    if (!q) {
      return recent.value;
    }
    return recent.value.filter((item) => `${item.lymc || ''} ${item.lybm || ''} ${item.batchNo || ''}`.includes(q));
  });

  function reportKey(row: ReportRecent) {
    return `${row.batchNo || ''}-${row.lybm || ''}`;
  }

  function openReport(row: ReportRecent) {
    if (!row.batchNo) {
      message.warning('这条记录没有批次号，请刷新后再预览');
      return;
    }
    activeReport.value = row;
    drawerOpen.value = true;
  }

  async function exportReport(row: ReportRecent) {
    if (!row.batchNo || !row.lybm) {
      message.warning('这条记录缺少批次或流域，无法导出');
      return;
    }
    try {
      const blob = await downloadReport(row.batchNo, row.lybm);
      const url = window.URL.createObjectURL(new Blob([blob as BlobPart]));
      const link = document.createElement('a');
      link.href = url;
      link.download = `山洪灾害风险隐患调查评价报告_${row.lymc || row.lybm}.docx`;
      link.click();
      window.URL.revokeObjectURL(url);
    } catch (e: any) {
      const text = await e?.response?.data?.text?.();
      message.error(text || '导出失败');
    }
  }
  const bases = computed(() => knowledge.value?.bases || []);
  const reportStatus = computed(() => report.value?.byStatus || []);

  function statusCount(name: string) {
    return reportStatus.value.find((item) => item.name === name)?.value || 0;
  }

  const kpis = computed(() => {
    const m = map.value;
    const r = report.value;
    const k = knowledge.value;
    if (isWork.value) {
      return [
        { label: '已生成报告', value: n(r?.reportCount), foot: '七章都已生成的流域', color: '#1677ff', path: '/super/airag/shreport/ShReportAssistant' },
        { label: '有正文的流域', value: n(r?.watershedCount), foot: '至少写过一章', color: '#08979c' },
        { label: '章节', value: n(r?.chapterCount), foot: `已通过 ${n(statusCount('已通过'))}`, color: '#389e0d' },
        { label: '待审核 / 失败', value: `${n(statusCount('待审核'))} / ${n(statusCount('失败'))}`, foot: '按章节计', color: '#d46b08' },
      ];
    }
    return [
      { label: '知识库文档', value: n(k?.docCount), foot: `${n(k?.baseCount)} 个知识库`, color: '#531dab', path: '/super/airag/aiknowledge/AiKnowledgeBaseList' },
      { label: '小流域', value: n(m?.watershedCount), foot: `调查对象 ${n(m?.surveyObjectCount)}`, color: '#08979c' },
      { label: '隐患点', value: n(m?.hazardCount), foot: `识别 ${n(risk.value.total)} 次`, color: '#d46b08', path: '/super/airag/shreport/ShMapDataAdmin?view=aiRisk' },
      { label: '防治对象', value: n(m?.preventionCount), foot: '有空间位置的对象', color: '#389e0d' },
      { label: '断面', value: n(m?.sectionCount), foot: `测点 ${n((m?.crossPointCount || 0) + (m?.longPointCount || 0))}`, color: '#0958d9' },
      { label: '已生成报告', value: n(r?.reportCount), foot: '到工作台查看进度', color: '#1677ff', path: '/dashboard/workbench' },
    ];
  });

  const hazardTypes = computed(() => topItems(map.value?.hazardByType));
  const preventionTypes = computed(() => topItems(map.value?.preventionByType));
  const sectionTypes = computed(() => topItems(map.value?.sectionByType, 6));

  function n(v?: number) {
    return Number(v || 0).toLocaleString('zh-CN');
  }

  function topItems(map: Record<string, number> | undefined, limit = 8): NameValue[] {
    const items = Object.entries(map || {}).map(([name, value]) => ({ name: name || '未知', value: Number(value) || 0 }));
    items.sort((a, b) => b.value - a.value);
    if (items.length <= limit) {
      return items;
    }
    const head = items.slice(0, limit);
    const rest = items.slice(limit).reduce((sum, item) => sum + item.value, 0);
    if (rest > 0) {
      head.push({ name: '其他', value: rest });
    }
    return head;
  }

  function go(path: string) {
    router.push(path);
  }

  async function load() {
    loading.value = true;
    try {
      data.value = await getAiBoardOverview();
    } finally {
      loading.value = false;
    }
  }

  onMounted(load);
</script>

<style lang="less" scoped>
  .ai-board {
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 12px;
    }
    .title {
      font-size: 18px;
      font-weight: 600;
      line-height: 1.3;
    }
    .sub {
      margin-top: 4px;
      color: rgba(0, 0, 0, 0.45);
    }
    .section {
      margin-top: 12px;
    }
    .kpi {
      background: #fff;
      border-radius: 8px;
      padding: 14px 16px 12px;
      border-top: 3px solid #1677ff;
      cursor: pointer;
      min-height: 104px;
    }
    .kpi-label {
      color: rgba(0, 0, 0, 0.45);
    }
    .kpi-value {
      margin-top: 6px;
      font-size: 26px;
      font-weight: 600;
      line-height: 1.2;
    }
    .kpi-foot {
      margin-top: 6px;
      color: rgba(0, 0, 0, 0.45);
      font-size: 12px;
    }
    .entries {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 10px;
    }
    .entry {
      text-align: left;
      border: 1px solid #f0f0f0;
      background: #fafafa;
      border-radius: 8px;
      padding: 12px 14px;
      cursor: pointer;
    }
    .entry:hover {
      border-color: #1677ff;
    }
    .entry-title {
      font-weight: 600;
    }
    .entry-desc {
      margin-top: 4px;
      color: rgba(0, 0, 0, 0.45);
      font-size: 12px;
    }
    .risk-line {
      margin-top: 12px;
      color: rgba(0, 0, 0, 0.65);
    }
    .report-tools {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 12px;
    }
  }
</style>
