<template>
  <PageWrapper dense content-full-height fixed-height>
    <div class="onemap-page">
      <ShOneMapBoard />
    </div>
  </PageWrapper>
</template>

<script lang="ts" setup>
  import { PageWrapper } from '/@/components/Page';
  import ShOneMapBoard from './ShOneMapBoard.vue';

  defineOptions({ name: 'super-airag-shreport-ShReportOneMap' });
</script>

<style lang="less" scoped>
  .onemap-page {
    height: 100%;
    min-height: 0;
    padding: 0;
  }
</style>
