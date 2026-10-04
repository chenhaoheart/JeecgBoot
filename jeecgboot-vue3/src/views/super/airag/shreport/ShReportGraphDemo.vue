<template>
  <PageWrapper title="报告编排图" content="生成进行时，当前步骤会在图上闪烁；已经走过的步骤保持绿色。">
    <div class="graph-demo-page">
      <GraphBoard :batch-no="batchNo" :chapters="chapters" :allow-demo="false" />
    </div>
  </PageWrapper>
</template>

<script lang="ts" setup>
  import { PageWrapper } from '/@/components/Page';
  import GraphBoard from './components/GraphBoard.vue';
  import { useReportSession } from './useReportSession';

  const { batchNo, chapters } = useReportSession();

  defineOptions({ name: 'super-airag-shreport-ShReportGraphDemo' });
</script>

<style lang="less" scoped>
  .graph-demo-page {
    height: calc(100vh - 180px);
    min-height: 640px;
    background: #fff;
    border-radius: 12px;
    overflow: hidden;
    box-shadow: 0 1px 3px rgba(16, 33, 61, 0.06);
  }
</style>
