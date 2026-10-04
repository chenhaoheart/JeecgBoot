<template>
  <header class="rt-header">
    <!-- 品牌区 -->
    <div class="hd-brand">
      <div class="hd-logo"><FileDoneOutlined /></div>
      <div class="hd-text">
        <div class="hd-name">山洪报告助手</div>
        <div class="hd-sub">调查评价报告智能生成工作台</div>
      </div>
    </div>

    <span class="hd-divider"></span>

    <div class="hd-flex"></div>

    <!-- 迷你进度：7 格章节状态 + 已通过数，点击跳到章节视图 -->
    <a-tooltip v-if="chapters.length" title="查看章节审核进度">
      <div class="hd-progress" @click="emit('update:viewMode', 'chapter')">
        <span class="pg-segs">
          <span
            v-for="ch in sortedChapters"
            :key="ch.chapterNo"
            class="pg-seg"
            :style="{ background: statusMeta(ch.status).color }"
          ></span>
        </span>
        <span class="pg-text">
          已通过 <b :class="{ done: passedCount === chapters.length }">{{ passedCount }}</b
          >/{{ chapters.length }}
        </span>
      </div>
    </a-tooltip>

    <span v-if="chapters.length" class="hd-divider"></span>

    <!-- 视图切换 -->
    <Segmented
      :value="viewMode"
      size="small"
      :options="[
        { label: '三栏', value: 'all' },
        { label: '对话', value: 'chat' },
        { label: '章节', value: 'chapter' },
      ]"
      @change="emit('update:viewMode', $event as any)"
    />

    <a-tooltip title="悬浮查看生成流水线，不离开当前页面">
      <a-button size="small" :type="graphOpen ? 'primary' : 'default'" @click="emit('update:graphOpen', !graphOpen)">流水线</a-button>
    </a-tooltip>

    <a-button size="small" type="primary" class="hd-export" :disabled="!canExport" :loading="exporting" @click="openExportPicker">
      <template #icon><DownloadOutlined /></template>
      导出 Word
    </a-button>
  </header>
</template>

<script lang="ts" setup>
  import { computed } from 'vue';
  import { Segmented } from 'ant-design-vue';
  import { DownloadOutlined, FileDoneOutlined } from '@ant-design/icons-vue';
  import { statusMeta } from './reportStatus';
  import { useReportSession } from '../useReportSession';

  defineOptions({ name: 'ShReportToolbar' });

  const props = defineProps<{ viewMode: 'all' | 'chat' | 'chapter'; graphOpen?: boolean }>();
  const emit = defineEmits<{
    (e: 'update:viewMode', v: 'all' | 'chat' | 'chapter'): void;
    (e: 'update:graphOpen', v: boolean): void;
  }>();

  const {
    chapters,
    passedCount,
    canExport,
    exporting,
    openExportPicker,
  } = useReportSession();

  const sortedChapters = computed(() => chapters.value.slice().sort((a, b) => a.chapterNo - b.chapterNo));
</script>

<style lang="less" scoped>
  /* 单行工作台头部：品牌 + 报告上下文 + 进度 / 模型 / 视图 / 导出 */
  .rt-header {
    flex: 0 0 auto;
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 8px 10px;
    padding: 8px 14px;
    background: #fff;
    border-bottom: 1px solid #eef1f6;

    .hd-brand {
      display: flex;
      align-items: center;
      gap: 8px;

      .hd-logo {
        width: 30px;
        height: 30px;
        border-radius: 8px;
        display: flex;
        align-items: center;
        justify-content: center;
        color: #fff;
        font-size: 16px;
        background: linear-gradient(135deg, @primary-color 0%, #5b8ff9 100%);
        box-shadow: 0 2px 6px fade(@primary-color, 35%);
      }

      .hd-name {
        font-size: 14px;
        font-weight: 600;
        color: rgba(0, 0, 0, 0.88);
        line-height: 18px;
      }

      .hd-sub {
        font-size: 11px;
        color: #98a2b3;
        line-height: 14px;
        white-space: nowrap;
      }
    }

    .hd-divider {
      width: 1px;
      height: 18px;
      background: #eef1f6;
    }

    .hd-flex {
      flex: 1 1 0;
      min-width: 0;
    }

    /* 迷你进度：7 格章节状态 */
    .hd-progress {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      padding: 3px 10px;
      border-radius: 999px;
      background: #f5f7fa;
      border: 1px solid #eef1f6;
      cursor: pointer;
      transition: border-color 0.2s, background 0.2s;

      &:hover {
        border-color: fade(@primary-color, 40%);
        background: fade(@primary-color, 4%);
      }

      .pg-segs {
        display: inline-flex;
        gap: 3px;

        .pg-seg {
          width: 9px;
          height: 8px;
          border-radius: 2px;
        }
      }

      .pg-text {
        font-size: 12px;
        color: #667085;
        white-space: nowrap;

        b {
          color: @primary-color;
          font-weight: 600;

          &.done {
            color: #52c41a;
          }
        }
      }
    }

    .hd-export {
      border-radius: 6px;
    }
  }

  /* 窄屏隐藏副标题，保住主操作 */
  @media (max-width: 1500px) {
    .hd-sub {
      display: none;
    }
  }
</style>
