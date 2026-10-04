<template>
  <div class="chapter-panel">
    <!-- 报告进度总览条 -->
    <div v-if="chapters.length" class="cp-summary" :class="{ complete: allPassed }">
      <div class="sum-main">
        <div class="sum-head">
          <span class="sum-label">报告进度</span>
          <span v-if="boundWatershed" class="sum-ws" :title="boundWatershed.lymc">{{ boundWatershed.lymc }}</span>
        </div>
        <div class="sum-bar">
          <span
            v-for="ch in sortedChapters"
            :key="ch.chapterNo"
            class="sum-seg"
            :style="{ background: statusMeta(ch.status).color }"
            :title="`${ch.chapterName}：${ch.status}`"
          ></span>
        </div>
        <div class="sum-stats">
          <template v-for="st in statusCounts" :key="st.status">
            <span class="st-item" :style="{ color: st.color }">
              <span class="st-dot" :style="{ background: st.color }"></span>{{ st.status }} {{ st.count }}
            </span>
          </template>
        </div>
      </div>
      <div class="sum-side">
        <span v-if="allPassed" class="sum-done"><CheckCircleFilled /> 全部章节已通过</span>
        <span v-else class="sum-hint">可选择任意章节导出 Word</span>
        <a-button size="small" :loading="refreshing" @click="onRefresh">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <a-button size="small" type="primary" :disabled="!canExport" :loading="exporting" @click="openExportPicker">
          <template #icon><DownloadOutlined /></template>
          导出 Word
        </a-button>
      </div>
    </div>

    <!-- 空态：章节流水线占位 + 刷新 / 单独查询 -->
    <div v-if="chapters.length === 0" class="cp-empty">
      <div class="empty-icon"><FileTextOutlined /></div>
      <div class="empty-title">暂无章节</div>
      <div class="empty-desc">
        {{ boundWatershed?.lybm ? '当前流域还没有生成章节，对话写完后可点刷新拉取，或按批次单独查询' : '请先在顶部选择流域，或按批次号单独查询已生成的章节' }}
      </div>
      <div class="empty-actions">
        <a-button type="primary" :loading="refreshing" @click="onRefresh">
          <template #icon><ReloadOutlined /></template>
          刷新章节
        </a-button>
      </div>
      <div class="empty-query">
        <div class="eq-title">单独查询已生成内容</div>
        <a-input v-model:value="queryBatch" size="small" placeholder="批次号，例如 B1790882445918" allow-clear />
        <a-input v-model:value="queryLybm" size="small" placeholder="流域编码（可空，按整批次查）" allow-clear />
        <a-button size="small" :loading="refreshing" @click="onQuery">查询</a-button>
      </div>
      <div class="empty-pipeline">
        <div v-for="name in PIPELINE" :key="name" class="ep-chip">{{ name }}</div>
      </div>
    </div>

    <div class="chapter-list">
      <div v-for="ch in sortedChapters" :key="ch.id || ch.chapterNo" class="chapter-card" :class="{ open: expanded[ch.id || ch.chapterNo] }">
        <span class="card-rail" :style="{ background: statusMeta(ch.status).color }"></span>
        <div class="card-header" @click="toggleExpand(ch)">
          <span class="ch-no">{{ ch.chapterNo }}</span>
          <span class="ch-name">{{ ch.chapterName }}</span>
          <!-- 质检问题徽标（收起时也能看到） -->
          <a-tooltip v-if="issueMap[ch.chapterNo]?.length" :title="qcTooltip(ch.chapterNo)">
            <span class="qc-badge" :class="{ block: hasBlock(ch.chapterNo) }">
              <WarningFilled v-if="hasBlock(ch.chapterNo)" />
              <BulbOutlined v-else />
              {{ issueMap[ch.chapterNo].length }}
            </span>
          </a-tooltip>
          <span class="st-tag" :style="{ color: statusMeta(ch.status).color, background: statusMeta(ch.status).bg }">
            <LoadingOutlined v-if="ch.status === '生成中'" spin />
            <span v-else class="st-dot" :style="{ background: statusMeta(ch.status).color }"></span>
            {{ ch.status }}
          </span>
          <span class="ch-time">{{ shortTime(ch.updateTime) }}</span>
          <span v-if="genCostText(ch.genCostMs)" class="ch-cost" title="本章从开始生成到最终完成的耗时">
            耗时 {{ genCostText(ch.genCostMs) }}
          </span>
          <CaretDownOutlined class="ch-caret" :class="{ open: expanded[ch.id || ch.chapterNo] }" />
        </div>
        <div v-if="expanded[ch.id || ch.chapterNo]" class="card-body">
          <div v-if="ch.status === '失败' && ch.failReason" class="body-alert qc">
            <div class="qc-item block">
              <WarningFilled />
              失败原因：{{ ch.failReason }}
            </div>
          </div>
          <div v-if="ch.auditNote" class="body-alert audit">
            <ExclamationCircleFilled />
            审核意见：{{ ch.auditNote }}
          </div>
          <!-- 自动质检问题 -->
          <div v-if="issueMap[ch.chapterNo] && issueMap[ch.chapterNo].length" class="body-alert qc">
            <div v-for="(q, qi) in issueMap[ch.chapterNo]" :key="qi" class="qc-item" :class="q.level === 'BLOCK' ? 'block' : 'hint'">
              <WarningFilled v-if="q.level === 'BLOCK'" />
              <BulbOutlined v-else />
              {{ q.message }}
            </div>
          </div>
          <div class="content-box">
            <MarkdownViewer :value="ch.content || '（暂无内容）'" />
          </div>
          <div class="card-actions">
            <a-button size="small" class="btn-pass" :disabled="ch.status === '已通过'" @click="openAudit(ch, '已通过')">
              <template #icon><CheckOutlined /></template>
              通过
            </a-button>
            <a-button size="small" class="btn-reject" :disabled="ch.status === '已驳回'" @click="openAudit(ch, '已驳回')">
              <template #icon><CloseOutlined /></template>
              驳回
            </a-button>
            <span class="act-divider"></span>
            <a-button size="small" :loading="isRegenerating(ch.chapterNo)" @click="openRegenerate(ch)">
              <template #icon><RedoOutlined /></template>
              重新生成
            </a-button>
            <a-button size="small" type="text" @click="openEdit(ch)">
              <template #icon><EditOutlined /></template>
              在线编辑
            </a-button>
            <a-button size="small" type="text" :disabled="!ch.contentPrev" @click="openCompare(ch)">
              <template #icon><DiffOutlined /></template>
              对比上一版
            </a-button>
            <a-button size="small" type="text" :disabled="!isExportable(ch)" :loading="exporting" @click="exportOne(ch)">
              <template #icon><DownloadOutlined /></template>
              导出 Word
            </a-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 审核（驳回/通过）弹窗：通过时可附带意见，驳回时必填 -->
    <a-modal v-model:open="auditOpen" :title="auditStatus === '已通过' ? '通过审核' : '驳回章节'" @ok="submitAudit">
      <div v-if="auditStatus === '已驳回'">驳回后请填写审核意见，重新生成时会作为修改要求传给 AI：</div>
      <a-textarea v-model:value="auditNote" :rows="3" placeholder="审核意见（驳回时建议填写）" />
    </a-modal>

    <!-- 重新生成弹窗 -->
    <a-modal v-model:open="regenOpen" title="重新生成章节" @ok="submitRegenerate">
      <div style="margin-bottom: 8px">
        将重新调用 AI 生成《{{ regenChapter?.chapterName }}》，修改意见会作为补充要求传给 AI：
      </div>
      <a-textarea v-model:value="regenNote" :rows="3" placeholder="例如：表格再细化、补充风险等级分析、语气更正式" />
    </a-modal>

    <!-- 在线编辑弹窗：左侧编辑 / 右侧实时预览 -->
    <a-modal v-model:open="editOpen" title="在线编辑章节" width="1100px" @ok="submitEdit">
      <div style="margin-bottom: 8px; color: #999; font-size: 12px">
        保存后章节状态将回到「待审核」，支持 Markdown 语法，右侧实时预览。
      </div>
      <div class="edit-split">
        <a-textarea v-model:value="editContent" :rows="18" class="edit-pane" placeholder="在此编辑章节内容（Markdown）" />
        <div class="preview-pane">
          <div class="preview-title">预览</div>
          <div class="preview-body">
            <MarkdownViewer :value="editContent || '（暂无内容）'" />
          </div>
        </div>
      </div>
    </a-modal>

    <!-- 版本对比弹窗 -->
    <a-modal v-model:open="compareOpen" :title="`版本对比 - ${compareChapter?.chapterName || ''}`" width="900px" :footer="null">
      <div class="diff-box">
        <div v-for="(l, li) in diffLines" :key="li" class="diff-line" :class="l.type">
          <span class="diff-sign">{{ l.type === 'add' ? '+' : l.type === 'del' ? '-' : ' ' }}</span>
          <span class="diff-text">{{ l.text || ' ' }}</span>
        </div>
      </div>
    </a-modal>

    <!-- 选择要导出的章节：不要求全部审核通过 -->
    <a-modal
      v-model:open="exportPickerOpen"
      title="选择要导出的章节"
      ok-text="导出 Word"
      :confirm-loading="exporting"
      @ok="confirmExportPicker"
    >
      <div class="export-hint">有正文的章节都可导出，不必等全部审核通过。生成中或空章节不可选。</div>
      <a-checkbox :checked="exportAllChecked" :indeterminate="exportIndeterminate" @change="toggleExportAll">全选可导出章节</a-checkbox>
      <a-checkbox-group v-model:value="exportChapterNos" class="export-chapters">
        <div v-for="ch in sortedChapters" :key="ch.chapterNo" class="export-ch">
          <a-checkbox :value="ch.chapterNo" :disabled="!isExportable(ch)">
            <span class="export-ch-name">{{ ch.chapterName }}</span>
            <span class="st-tag" :style="{ color: statusMeta(ch.status).color, background: statusMeta(ch.status).bg }">{{ ch.status }}</span>
          </a-checkbox>
        </div>
      </a-checkbox-group>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
  import {
    BulbOutlined,
    CaretDownOutlined,
    CheckCircleFilled,
    CheckOutlined,
    CloseOutlined,
    DiffOutlined,
    DownloadOutlined,
    EditOutlined,
    ExclamationCircleFilled,
    FileTextOutlined,
    LoadingOutlined,
    RedoOutlined,
    ReloadOutlined,
    WarningFilled,
  } from '@ant-design/icons-vue';
  import {
    message,
    Modal as AModal,
    Textarea,
  } from 'ant-design-vue';
  import { MarkdownViewer } from '/@/components/Markdown';
  import { auditChapter, IChapter, regenerateChapter, saveChapter } from '../ShReportAssistant.api';
  import { lineDiff, IDiffLine } from './versionDiff';
  import { shortTime, statusMeta, genCostText } from './reportStatus';
  import { useReportSession } from '../useReportSession';

  defineOptions({ name: 'ShReportChapterPanel' });

  const ATextarea = Textarea;

  /** 空态展示的报告流水线（与 7 个章节子流程一一对应） */
  const PIPELINE = ['一、流域概况', '二、前期准备', '三、外业调查成果', '四、影响分析', '五、历史灾害', '六、防治区分析', '七、成果与建议'];

  // 批次 / 流域 / 模型 / 章节统一从共享上下文读取，顶部工具栏切换后此处自动联动
  const {
    batchNo,
    boundWatershed,
    currentModelId,
    chapters,
    issueMap,
    canExport,
    allPassed,
    exportableChapters,
    exporting,
    refreshing,
    exportPickerOpen,
    exportChapterNos,
    isExportable,
    openExportPicker,
    exportReport,
    loadChapters,
    refreshChapters,
    queryChapters,
    currentLymc,
  } = useReportSession();

  const queryBatch = ref(batchNo.value || '');
  const queryLybm = ref(boundWatershed.value?.lybm || '');

  watch(
    () => [batchNo.value, boundWatershed.value?.lybm],
    () => {
      queryBatch.value = batchNo.value || queryBatch.value;
      queryLybm.value = boundWatershed.value?.lybm || queryLybm.value;
    }
  );

  async function onRefresh() {
    await refreshChapters();
  }

  async function onQuery() {
    await queryChapters(queryBatch.value, queryLybm.value);
  }

  const sortedChapters = computed(() => chapters.value.slice().sort((a, b) => a.chapterNo - b.chapterNo));

  /** 汇总条的状态计数（仅显示非零项） */
  const statusCounts = computed(() => {
    const order = ['已通过', '待审核', '已驳回', '生成中', '失败'];
    const counts: Record<string, number> = {};
    for (const ch of chapters.value) {
      counts[ch.status] = (counts[ch.status] || 0) + 1;
    }
    return order
      .filter((s) => counts[s])
      .map((s) => ({ status: s, count: counts[s], color: statusMeta(s).color }));
  });

  const expanded = reactive<Record<string, boolean>>({});
  /** 正在重新生成（等待轮询结果）的章节号 */
  const regenerating = ref<number[]>([]);
  /** 组件卸载后停止轮询，避免切页签后仍在刷列表 */
  let alive = true;

  function isRegenerating(chapterNo: number) {
    return regenerating.value.includes(chapterNo);
  }

  function hasBlock(chapterNo: number) {
    return (issueMap.value[chapterNo] || []).some((q) => q.level === 'BLOCK');
  }

  function qcTooltip(chapterNo: number) {
    return (issueMap.value[chapterNo] || [])
      .slice(0, 3)
      .map((q) => (q.level === 'BLOCK' ? '⛔ ' : '💡 ') + q.message)
      .join('\n');
  }

  const auditOpen = ref(false);
  const auditStatus = ref('已通过');
  const auditNote = ref('');
  const auditChapterRow = ref<IChapter>();

  const regenOpen = ref(false);
  const regenNote = ref('');
  const regenChapter = ref<IChapter>();

  const editOpen = ref(false);
  const editContent = ref('');
  const editChapter = ref<IChapter>();

  const compareOpen = ref(false);
  const compareChapter = ref<IChapter>();
  const diffLines = ref<IDiffLine[]>([]);

  function toggleExpand(ch: IChapter) {
    const key = ch.id || String(ch.chapterNo);
    expanded[key] = !expanded[key];
  }

  function openAudit(ch: IChapter, status: string) {
    auditChapterRow.value = ch;
    auditStatus.value = status;
    auditNote.value = status === '已驳回' ? ch.auditNote || '' : '';
    auditOpen.value = true;
  }

  async function submitAudit() {
    if (auditStatus.value === '已驳回' && !auditNote.value.trim()) {
      message.warning('驳回时请填写审核意见');
      return;
    }
    await auditChapter({
      id: auditChapterRow.value!.id,
      status: auditStatus.value,
      auditNote: auditNote.value || undefined,
    });
    message.success('审核完成');
    auditOpen.value = false;
    loadChapters();
  }

  function openRegenerate(ch: IChapter) {
    if (!boundWatershed.value?.lybm) {
      message.warning('请先选择流域');
      return;
    }
    // 后端对「生成中」的章节会直接跳过且不改状态，提前拦截避免前端空轮询
    if (ch.status === '生成中') {
      message.info(`《${ch.chapterName}》正在生成中，请稍候`);
      return;
    }
    regenChapter.value = ch;
    regenNote.value = ch.auditNote || '';
    regenOpen.value = true;
  }

  async function submitRegenerate() {
    const ch = regenChapter.value!;
    const no = ch.chapterNo;
    regenOpen.value = false;
    regenerating.value = [...regenerating.value, no];
    try {
      await regenerateChapter({
        batchNo: batchNo.value,
        lybm: boundWatershed.value!.lybm,
        lymc: currentLymc(),
        chapterNo: no,
        auditNote: regenNote.value || undefined,
        modelId: currentModelId.value || undefined,
      });
      message.success(`已提交，《${ch.chapterName}》生成完成后自动刷新`);
      await watchRegenerate(no);
    } catch {
      stopWatch(no);
      message.error('重新生成失败，请稍后重试');
    }
  }

  function stopWatch(chapterNo: number) {
    regenerating.value = regenerating.value.filter((n) => n !== chapterNo);
  }

  /**
   * 等待重新生成完成：后端 /regenerate 是异步执行且没有 SSE 通道，
   * 单章成文通常几十秒到数分钟，必须轮询到章节脱离「生成中」才能展示结果。
   */
  async function watchRegenerate(chapterNo: number) {
    const deadline = Date.now() + 10 * 60 * 1000;
    while (alive && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 5000));
      if (!alive) {
        return;
      }
      await loadChapters();
      const cur = chapters.value.find((c) => c.chapterNo === chapterNo);
      if (cur && cur.status !== '生成中') {
        stopWatch(chapterNo);
        message.success(`《${cur.chapterName}》生成结束，当前状态：${cur.status}`);
        return;
      }
    }
    stopWatch(chapterNo);
    message.info('生成耗时较长，请稍后手动刷新查看');
  }

  function openEdit(ch: IChapter) {
    if (!boundWatershed.value?.lybm) {
      message.warning('请先选择流域');
      return;
    }
    editChapter.value = ch;
    editContent.value = ch.content || '';
    editOpen.value = true;
  }

  async function submitEdit() {
    if (!editContent.value.trim()) {
      message.warning('内容不能为空');
      return;
    }
    await saveChapter({
      batchNo: batchNo.value,
      lybm: boundWatershed.value!.lybm,
      lymc: currentLymc(),
      chapterNo: editChapter.value!.chapterNo,
      content: editContent.value,
    });
    message.success('已保存，状态回到待审核');
    editOpen.value = false;
    loadChapters();
  }

  function openCompare(ch: IChapter) {
    if (!ch.contentPrev) {
      message.info('该章节没有上一版内容');
      return;
    }
    compareChapter.value = ch;
    diffLines.value = lineDiff(ch.contentPrev, ch.content);
    compareOpen.value = true;
  }

  function exportOne(ch: IChapter) {
    if (!isExportable(ch)) {
      message.warning('该章节还没有可导出的内容');
      return;
    }
    exportReport([ch.chapterNo]).catch(() => undefined);
  }

  const exportAllChecked = computed(
    () => exportableChapters.value.length > 0 && exportChapterNos.value.length === exportableChapters.value.length
  );
  const exportIndeterminate = computed(
    () => exportChapterNos.value.length > 0 && exportChapterNos.value.length < exportableChapters.value.length
  );

  function toggleExportAll(e: any) {
    const checked = e?.target?.checked ?? !exportAllChecked.value;
    exportChapterNos.value = checked ? exportableChapters.value.map((c) => c.chapterNo) : [];
  }

  async function confirmExportPicker() {
    if (!exportChapterNos.value.length) {
      message.warning('请至少选择一章');
      return Promise.reject();
    }
    await exportReport(exportChapterNos.value);
  }

  onMounted(() => {
    // 面板晚于会话创建挂载时（如切换页签回来）补齐一次章节加载
    loadChapters();
  });

  onUnmounted(() => {
    alive = false;
  });
</script>

<style lang="less" scoped>
  .chapter-panel {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;
    background: #fff;

    /* 报告进度总览条 */
    .cp-summary {
      flex: 0 0 auto;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin: 12px 14px 4px;
      padding: 10px 14px;
      border-radius: 10px;
      background: #f7f9fc;
      border: 1px solid #eef1f6;
      transition: background 0.3s, border-color 0.3s;

      &.complete {
        background: #f6ffed;
        border-color: #b7eb8f;
      }

      .sum-main {
        flex: 1;
        min-width: 0;
      }

      .sum-head {
        display: flex;
        align-items: baseline;
        gap: 8px;
        margin-bottom: 6px;

        .sum-label {
          font-size: 13px;
          font-weight: 600;
          color: rgba(0, 0, 0, 0.85);
        }

        .sum-ws {
          font-size: 12px;
          color: #667085;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }
      }

      .sum-bar {
        display: flex;
        gap: 3px;
        margin-bottom: 6px;

        .sum-seg {
          flex: 1;
          max-width: 64px;
          height: 6px;
          border-radius: 3px;
          transition: background 0.3s;
        }
      }

      .sum-stats {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 12px;
        font-size: 12px;

        .st-item {
          display: inline-flex;
          align-items: center;
          gap: 4px;

          .st-dot {
            width: 6px;
            height: 6px;
            border-radius: 50%;
          }
        }
      }

      .sum-side {
        flex: 0 0 auto;
        display: flex;
        align-items: center;
        gap: 10px;

        .sum-done {
          color: #389e0d;
          font-size: 12px;
          display: inline-flex;
          align-items: center;
          gap: 4px;
          white-space: nowrap;
        }

        .sum-hint {
          color: #98a2b3;
          font-size: 12px;
          white-space: nowrap;
        }
      }
    }

    .export-hint {
      color: #667085;
      font-size: 12px;
      margin-bottom: 10px;
    }

    .export-chapters {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin-top: 10px;
      width: 100%;

      .export-ch {
        .export-ch-name {
          margin-right: 8px;
        }

        .st-tag {
          display: inline-flex;
          align-items: center;
          font-size: 11px;
          padding: 0 8px;
          border-radius: 999px;
        }
      }
    }

    /* 空态：报告流水线占位 */
    .cp-empty {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 24px;

      .empty-icon {
        width: 56px;
        height: 56px;
        border-radius: 16px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 26px;
        color: @primary-color;
        background: fade(@primary-color, 8%);
        margin-bottom: 14px;
      }

      .empty-title {
        font-size: 15px;
        font-weight: 600;
        color: rgba(0, 0, 0, 0.85);
        margin-bottom: 6px;
      }

      .empty-desc {
        font-size: 12px;
        color: #98a2b3;
        margin-bottom: 14px;
        text-align: center;
        max-width: 360px;
      }

      .empty-actions {
        margin-bottom: 14px;
      }

      .empty-query {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        justify-content: center;
        gap: 8px;
        width: 100%;
        max-width: 460px;
        margin-bottom: 18px;
        padding: 12px;
        border-radius: 10px;
        background: #f7f9fc;
        border: 1px dashed #e4e9f0;

        .eq-title {
          flex: 0 0 100%;
          font-size: 12px;
          color: #667085;
          margin-bottom: 2px;
        }

        :deep(.ant-input) {
          flex: 1 1 140px;
          min-width: 140px;
        }
      }

      .empty-pipeline {
        display: flex;
        flex-wrap: wrap;
        justify-content: center;
        gap: 8px;
        max-width: 460px;

        .ep-chip {
          padding: 4px 12px;
          border-radius: 999px;
          border: 1px dashed #d4dae3;
          color: #98a2b3;
          font-size: 12px;
          background: #fafbfd;
        }
      }
    }

    .chapter-list {
      flex: 1;
      min-height: 0;
      overflow-y: auto;
      padding: 12px 14px 16px;

      .chapter-card {
        position: relative;
        border: 1px solid #eef1f6;
        border-radius: 10px;
        margin-bottom: 10px;
        overflow: hidden;
        transition: box-shadow 0.2s, border-color 0.2s;

        /* 状态色轨（颜色由模板内联注入） */
        .card-rail {
          position: absolute;
          left: 0;
          top: 0;
          bottom: 0;
          width: 3px;
        }

        &:hover {
          border-color: #dbe2ec;
          box-shadow: 0 2px 10px rgba(16, 33, 61, 0.06);
        }

        .card-header {
          display: flex;
          align-items: center;
          gap: 8px;
          padding: 9px 12px 9px 15px;
          cursor: pointer;

          .ch-no {
            flex: 0 0 22px;
            height: 22px;
            border-radius: 6px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            font-size: 12px;
            font-weight: 600;
            color: #475467;
            background: #f2f4f7;
          }

          .ch-name {
            flex: 1;
            min-width: 0;
            font-size: 13px;
            font-weight: 500;
            color: rgba(0, 0, 0, 0.85);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
          }

          .qc-badge {
            display: inline-flex;
            align-items: center;
            gap: 3px;
            font-size: 11px;
            padding: 1px 7px;
            border-radius: 999px;
            color: #ad6800;
            background: #fffbe6;
            border: 1px solid #ffe58f;

            &.block {
              color: #cf1322;
              background: #fff1f0;
              border-color: #ffa39e;
            }
          }

          .st-tag {
            display: inline-flex;
            align-items: center;
            gap: 5px;
            font-size: 12px;
            padding: 2px 9px;
            border-radius: 999px;
            white-space: nowrap;

            .st-dot {
              width: 6px;
              height: 6px;
              border-radius: 50%;
            }
          }

          .ch-time {
            flex: 0 0 auto;
            color: #b8c0cc;
            font-size: 12px;
            white-space: nowrap;
          }

          .ch-cost {
            flex: 0 0 auto;
            color: #8c93a3;
            font-size: 11px;
            white-space: nowrap;
          }

          .ch-caret {
            font-size: 11px;
            color: #b8c0cc;
            transition: transform 0.2s;

            &.open {
              transform: rotate(180deg);
            }
          }
        }

        .card-body {
          padding: 0 12px 12px 15px;

          .body-alert {
            display: flex;
            flex-direction: column;
            gap: 3px;
            border-radius: 8px;
            padding: 7px 11px;
            margin-bottom: 10px;
            font-size: 12px;

            &.audit {
              color: #d46b08;
              background: #fff7e6;
              border: 1px solid #ffd591;
            }

            &.qc {
              color: #ad6800;
              background: #fffbe6;
              border: 1px dashed #ffe58f;

              .qc-item {
                display: flex;
                align-items: flex-start;
                gap: 5px;
                line-height: 20px;

                &.block {
                  color: #cf1322;
                }
              }
            }
          }

          .content-box {
            max-height: 46vh;
            overflow-y: auto;
            border: 1px solid #eef1f6;
            border-radius: 8px;
            padding: 12px 14px;
            margin-bottom: 12px;
            background: #fafbfd;
          }

          .card-actions {
            display: flex;
            align-items: center;
            gap: 8px;
            flex-wrap: wrap;

            .btn-pass {
              background: #52c41a;
              border-color: #52c41a;
              color: #fff;

              &:not(:disabled):hover {
                background: #73d13d;
                border-color: #73d13d;
              }

              &:disabled {
                color: #fff;
                background: #b7eb8f;
                border-color: #b7eb8f;
              }
            }

            .btn-reject:not(:disabled) {
              color: #ff4d4f;
              border-color: #ffccc7;

              &:hover {
                color: #ff7875;
                border-color: #ff7875;
              }
            }

            .act-divider {
              width: 1px;
              height: 14px;
              background: #eef1f6;
            }
          }
        }
      }
    }

    .diff-box {
      max-height: 520px;
      overflow: auto;
      border: 1px solid #f0f0f0;
      border-radius: 6px;
      background: #fafafa;
      font-family: Consolas, 'Courier New', monospace;
      font-size: 12px;

      .diff-line {
        display: flex;
        line-height: 18px;
        white-space: pre-wrap;
        word-break: break-all;

        .diff-sign {
          flex: 0 0 18px;
          text-align: center;
          user-select: none;
        }

        .diff-text {
          flex: 1;
        }

        &.add {
          background: #f6ffed;
          color: #389e0d;
        }

        &.del {
          background: #fff1f0;
          color: #cf1322;
        }
      }
    }
  }

  /* 在线编辑：左编辑 / 右预览（弹窗被 teleport 到组件外，样式必须挂在顶层类上） */
  .edit-split {
    display: flex;
    gap: 12px;
    align-items: stretch;

    .edit-pane {
      flex: 1 1 50%;
      min-width: 0;
      font-size: 13px;
      line-height: 1.7;
      height: 540px;
      resize: vertical;
    }

    .preview-pane {
      flex: 1 1 50%;
      min-width: 0;
      display: flex;
      flex-direction: column;
      border: 1px solid #f0f0f0;
      border-radius: 6px;
      background: #fafafa;

      .preview-title {
        flex: 0 0 auto;
        padding: 4px 10px;
        font-size: 11px;
        color: #98a2b3;
        border-bottom: 1px solid #f0f0f0;
        background: #f5f7fa;
        border-radius: 6px 6px 0 0;
      }

      .preview-body {
        flex: 1;
        min-height: 0;
        overflow: auto;
        padding: 8px 14px;
        font-size: 13px;
        line-height: 1.7;

        /* 预览与编辑两侧字号保持一致，标题按比例收敛 */
        :deep(.vditor-reset) {
          font-size: 13px;

          h1 {
            font-size: 1.45em;
          }
          h2 {
            font-size: 1.25em;
          }
          h3 {
            font-size: 1.12em;
          }
          h4,
          h5,
          h6 {
            font-size: 1.02em;
          }
          table {
            font-size: 12px;
          }
        }
      }
    }
  }
</style>
