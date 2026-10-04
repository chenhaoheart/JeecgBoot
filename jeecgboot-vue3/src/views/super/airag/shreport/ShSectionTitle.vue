<template>
  <div class="sh-sec-title" :class="{ 'is-first': first }">
    <slot />
  </div>
</template>

<script lang="ts" setup>
  defineOptions({ name: 'ShSectionTitle' });

  defineProps<{
    /** 作为内容区第一项时去掉上边距 */
    first?: boolean;
  }>();
</script>

<style lang="less" scoped>
  .sh-sec-title {
    position: relative;
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px;
    font-weight: 600;
    font-size: 13px;
    color: #0f172a;
    margin: 16px 0 10px;
    padding-left: 10px;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 50%;
      transform: translateY(-50%);
      width: 4px;
      height: 14px;
      border-radius: 2px;
      background: #1677ff;
    }

    &.is-first {
      margin-top: 0;
    }

    :deep(.sec-note) {
      font-weight: 400;
      font-size: 12px;
      color: #98a2b3;
    }
  }
</style>
