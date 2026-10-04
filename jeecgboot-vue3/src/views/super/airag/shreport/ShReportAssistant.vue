<template>
  <PageWrapper dense content-full-height fixed-height>
    <div class="report-workbench">
      <div class="wb-card">
        <ReportToolbar v-model:viewMode="viewMode" :graph-open="graphOpen" @update:graph-open="onToggleGraph" />
        <div class="wb-body">
          <!-- 第一栏：历史会话 -->
          <aside class="wb-side">
            <SessionSide />
          </aside>
          <!-- 第二栏：AI 对话 -->
          <section v-show="showChat" class="wb-pane">
            <AgentChat ref="chatRef" @refresh="onRefresh" @progress="onProgress" @outline="onOutline" @graph="onGraph" />
          </section>
          <!-- 第三栏：章节审核 -->
          <section v-show="showChapter" class="wb-pane">
            <ChapterPanel />
          </section>
        </div>
      </div>
    </div>

    <GraphFloat
      :open="graphOpen"
      :minimized="graphMinimized"
      :running="graphRunning"
      :batch-no="batchNo"
      :live-event="graphEvent"
      :chapters="chapters"
      @close="onToggleGraph(false)"
      @minimize="graphMinimized = !graphMinimized"
    />

    <OutlineConfirm :open="outlineOpen" :outline="outlineData" @confirm="onOutlineConfirm" @cancel="outlineOpen = false" />
  </PageWrapper>
</template>

<script lang="ts" setup>
  import { computed, nextTick, ref, watch } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { PageWrapper } from '/@/components/Page';
  import AgentChat from './components/AgentChat.vue';
  import ChapterPanel from './components/ChapterPanel.vue';
  import GraphFloat from './components/GraphFloat.vue';
  import OutlineConfirm from './components/OutlineConfirm.vue';
  import ReportToolbar from './components/ReportToolbar.vue';
  import SessionSide from './components/SessionSide.vue';
  import { IOutlineChapter } from './ShReportAssistant.api';
  import { useReportSession } from './useReportSession';

  // 组件名必须与路由名一致，供 keep-alive 缓存（切换页签后返回不丢失会话）
  defineOptions({ name: 'super-airag-shreport-ShReportAssistant' });

  const { batchNo, chapters, refreshChapters, setProgress, conversationId, consumePendingGenerate } = useReportSession();
  const route = useRoute();
  const router = useRouter();

  /** 视图切换：三栏 / 仅对话 / 仅章节。流水线是悬浮窗，不占一栏 */
  const viewMode = ref<'all' | 'chat' | 'chapter'>('all');
  const showChat = computed(() => viewMode.value !== 'chapter');
  const showChapter = computed(() => viewMode.value !== 'chat');

  const chatRef = ref<InstanceType<typeof AgentChat>>();
  const graphEvent = ref<Record<string, any> | null>(null);
  const graphOpen = ref(false);
  const graphMinimized = ref(false);
  /** 用户主动关掉后，这一轮不再自动弹出，下一轮生成再打开 */
  const graphDismissed = ref(false);

  const graphRunning = computed(() => {
    const ev = graphEvent.value;
    if (ev?.phase === 'enter') {
      return ev.node !== 'end';
    }
    return chapters.value.some((c) => String(c.status).includes('生成中'));
  });

  function onToggleGraph(open: boolean) {
    graphOpen.value = open;
    if (open) {
      graphDismissed.value = false;
      graphMinimized.value = false;
    } else {
      graphDismissed.value = true;
      graphMinimized.value = false;
    }
  }

  function onGraph(p: Record<string, any>) {
    graphEvent.value = p;
    if (p?.phase === 'enter' && (p.node === 'prepare' || p.node === 'start')) {
      graphDismissed.value = false;
    }
    if (!graphDismissed.value && p?.phase === 'enter' && p.node !== 'end') {
      graphOpen.value = true;
    }
  }

  watch(
    chapters,
    () => {
      const live = chapters.value.some((c) => String(c.status).includes('生成中'));
      if (live && !graphDismissed.value) {
        graphOpen.value = true;
      }
    },
    { deep: true }
  );

  // 大纲两阶段
  const outlineOpen = ref(false);
  const outlineData = ref<IOutlineChapter[]>([]);

  /**
   * 流程完成后的刷新：对齐会话上下文（批次/流域）再拉章节。
   * 不依赖事件批次号必须和当前一致，避免漏掉刚写完的内容。
   */
  function onRefresh(_evtBatchNo: string) {
    refreshChapters({ silent: true });
  }

  /** SSE 章节级进度：乐观更新卡片状态 */
  function onProgress(p: { chapterNo: number; status: string; batchNo: string }) {
    if (!p.batchNo || p.batchNo === batchNo.value) {
      setProgress(p.chapterNo, p.status);
    }
  }

  function onOutline(outline: IOutlineChapter[]) {
    outlineData.value = outline || [];
    outlineOpen.value = true;
  }

  async function onOutlineConfirm(outline: IOutlineChapter[]) {
    outlineOpen.value = false;
    // 复用 AgentChat 的 SSE 读取链路，进度/刷新/下载等事件与普通对话一致
    await (chatRef.value as any)?.submitOutline(outline);
  }

  /** 一张图独立页确认后：?lybm=&lymc=&action=generate */
  async function applyMapQuery() {
    const q = route.query;
    if (q.action !== 'generate' || !q.lybm) {
      return;
    }
    if (!conversationId.value) {
      return;
    }
    const lybm = String(q.lybm);
    const lymc = String(q.lymc || q.lybm);
    await router.replace({ path: route.path, query: {} });
    await consumePendingGenerate(lybm, lymc);
    await nextTick();
    await (chatRef.value as any)?.sendText(`帮我生成${lymc}的整份报告`);
  }

  watch(
    () => [conversationId.value, route.query.action, route.query.lybm],
    () => {
      applyMapQuery();
    }
  );
</script>

<style lang="less" scoped>
  .report-workbench {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 460px;
    padding: 8px 12px 12px;
  }

  .wb-card {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    background: #fff;
    border-radius: 12px;
    box-shadow: 0 1px 3px rgba(16, 33, 61, 0.06), 0 4px 16px rgba(16, 33, 61, 0.04);
    overflow: hidden;

    .wb-body {
      flex: 1;
      min-height: 0;
      display: flex;
      background: #f7f9fc;

      /* 除第一栏外都用一条竖分隔线连接，而不是各自再包一层边框 */
      > * + * {
        border-left: 1px solid #eef1f6;
      }

      /* 第一栏：会话列表，固定宽度 */
      .wb-side {
        flex: 0 0 208px;
        min-width: 0;
        display: flex;
        flex-direction: column;
        overflow: hidden;
      }

      .wb-pane {
        flex: 1 1 50%;
        min-width: 0;
        display: flex;
        flex-direction: column;
        overflow: hidden;
      }
    }
  }
</style>
