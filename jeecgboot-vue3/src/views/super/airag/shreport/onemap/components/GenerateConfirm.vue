<template>
  <a-modal :open="open" title="生成小流域报告" ok-text="确认生成" cancel-text="取消" @ok="emit('confirm')" @cancel="emit('cancel')">
    <p>
      确认为「<b>{{ lymc }}</b
      >」生成《山洪灾害风险隐患调查评价报告》？
    </p>
    <p class="meta">
      流域编码 {{ lybm }}
      <template v-if="objectCount != null"> · 防治对象 {{ objectCount }}</template>
      <template v-if="population != null"> · 受威胁人口 {{ population }}</template>
    </p>
    <p class="tip">确认后将绑定该流域并开始整份报告生成（7 章）。</p>
  </a-modal>
</template>

<script lang="ts" setup>
  defineOptions({ name: 'ShOneMapGenerateConfirm' });

  defineProps<{
    open: boolean;
    lybm?: string;
    lymc?: string;
    objectCount?: number;
    population?: number;
  }>();

  const emit = defineEmits<{
    (e: 'confirm'): void;
    (e: 'cancel'): void;
  }>();
</script>

<style lang="less" scoped>
  .meta {
    color: #64748b;
    font-size: 13px;
  }

  .tip {
    color: #94a3b8;
    font-size: 12px;
    margin-bottom: 0;
  }
</style>
