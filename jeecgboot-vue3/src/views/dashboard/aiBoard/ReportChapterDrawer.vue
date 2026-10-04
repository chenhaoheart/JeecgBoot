<template>
  <a-drawer
    :open="open"
    :title="title"
    width="920"
    :destroy-on-close="true"
    @close="emit('update:open', false)"
  >
    <div class="toolbar">
      <span class="hint">有正文的章节可预览、导出，不必等全部审核通过。</span>
      <a-button :loading="exporting" :disabled="!exportable.length" @click="exportAll">导出完整报告</a-button>
      <a-button :disabled="!exportable.length" @click="pickerOpen = true">选择章节导出</a-button>
    </div>

    <a-spin :spinning="loading">
      <a-empty v-if="!loading && !chapters.length" description="这一批还没有章节" />
      <div v-for="ch in chapters" :key="ch.id || ch.chapterNo" class="ch">
        <div class="ch-head">
          <span class="ch-no">{{ ch.chapterNo }}</span>
          <span class="ch-name">{{ ch.chapterName }}</span>
          <span class="st" :style="{ color: statusMeta(ch.status).color, background: statusMeta(ch.status).bg }">{{ ch.status }}</span>
          <span class="ch-time">{{ shortTime(ch.updateTime) }}</span>
        </div>
        <div v-if="ch.status === '失败' && ch.failReason" class="fail">失败原因：{{ ch.failReason }}</div>
        <div class="ch-body">
          <MarkdownViewer :value="ch.content || '（暂无内容）'" />
        </div>
      </div>
    </a-spin>

    <a-modal v-model:open="pickerOpen" title="选择要导出的章节" ok-text="导出 Word" :confirm-loading="exporting" @ok="exportPicked">
      <a-checkbox-group v-model:value="picked" class="pick-list">
        <div v-for="ch in chapters" :key="ch.chapterNo">
          <a-checkbox :value="ch.chapterNo" :disabled="!canExport(ch)">
            {{ ch.chapterName }}
            <span class="st" :style="{ color: statusMeta(ch.status).color, background: statusMeta(ch.status).bg }">{{ ch.status }}</span>
          </a-checkbox>
        </div>
      </a-checkbox-group>
    </a-modal>
  </a-drawer>
</template>

<script lang="ts" setup>
  import { computed, ref, watch } from 'vue';
  import { message } from 'ant-design-vue';
  import { MarkdownViewer } from '/@/components/Markdown';
  import { downloadReport, getChapterList, IChapter } from '/@/views/super/airag/shreport/ShReportAssistant.api';
  import { shortTime, statusMeta } from '/@/views/super/airag/shreport/components/reportStatus';
  import { ReportRecent } from './api';

  const props = defineProps<{ open: boolean; report: ReportRecent | null }>();
  const emit = defineEmits<{ 'update:open': [boolean] }>();

  const loading = ref(false);
  const exporting = ref(false);
  const chapters = ref<IChapter[]>([]);
  const pickerOpen = ref(false);
  const picked = ref<number[]>([]);

  const title = computed(() => props.report?.lymc || props.report?.lybm || '报告章节');
  const exportable = computed(() => chapters.value.filter(canExport));

  function canExport(ch: IChapter) {
    return !!String(ch.content || '').trim() && ch.status !== '生成中';
  }

  async function load() {
    const report = props.report;
    if (!report?.batchNo || !report.lybm) {
      chapters.value = [];
      return;
    }
    loading.value = true;
    try {
      chapters.value = (await getChapterList(report.batchNo, report.lybm)) || [];
      chapters.value.sort((a, b) => a.chapterNo - b.chapterNo);
      picked.value = exportable.value.map((ch) => ch.chapterNo);
    } finally {
      loading.value = false;
    }
  }

  async function saveWord(nos?: number[]) {
    const report = props.report;
    if (!report?.batchNo || !report.lybm) {
      return;
    }
    exporting.value = true;
    try {
      const blob = await downloadReport(report.batchNo, report.lybm, nos);
      const url = window.URL.createObjectURL(new Blob([blob as BlobPart]));
      const link = document.createElement('a');
      const name = report.lymc || report.lybm;
      const suffix = nos?.length && nos.length < exportable.value.length ? `_第${nos.join('-')}章` : '';
      link.href = url;
      link.download = `山洪灾害风险隐患调查评价报告_${name}${suffix}.docx`;
      link.click();
      window.URL.revokeObjectURL(url);
      pickerOpen.value = false;
    } catch (e: any) {
      const text = await e?.response?.data?.text?.();
      message.error(text || '导出失败');
    } finally {
      exporting.value = false;
    }
  }

  function exportAll() {
    saveWord(exportable.value.map((ch) => ch.chapterNo));
  }

  function exportPicked() {
    if (!picked.value.length) {
      message.warning('请至少选择一章');
      return;
    }
    saveWord([...picked.value].sort((a, b) => a - b));
  }

  watch(
    () => [props.open, props.report?.batchNo, props.report?.lybm],
    () => {
      if (props.open) {
        load();
      }
    }
  );
</script>

<style lang="less" scoped>
  .toolbar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
  }
  .hint {
    flex: 1;
    color: rgba(0, 0, 0, 0.45);
    font-size: 12px;
  }
  .ch {
    border: 1px solid #f0f0f0;
    border-radius: 8px;
    margin-bottom: 12px;
    overflow: hidden;
  }
  .ch-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 10px 12px;
    background: #fafafa;
  }
  .ch-no {
    width: 22px;
    height: 22px;
    border-radius: 50%;
    background: #1677ff;
    color: #fff;
    font-size: 12px;
    line-height: 22px;
    text-align: center;
  }
  .ch-name {
    font-weight: 600;
  }
  .st {
    padding: 0 8px;
    border-radius: 10px;
    font-size: 12px;
    line-height: 20px;
  }
  .ch-time {
    margin-left: auto;
    color: rgba(0, 0, 0, 0.45);
    font-size: 12px;
  }
  .fail {
    padding: 8px 12px;
    color: #cf1322;
    background: #fff1f0;
  }
  .ch-body {
    padding: 12px 16px 4px;
  }
  .pick-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
    margin-top: 8px;
  }
</style>
