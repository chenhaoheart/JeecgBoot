<template>
  <a-modal
    :open="open"
    title="报告大纲确认（两阶段生成）"
    width="900px"
    ok-text="确认大纲并生成"
    cancel-text="取消"
    @ok="onConfirm"
    @cancel="onCancel"
  >
    <div class="outline-tip">
      已根据该流域的实测数据生成写作提纲。请勾选需要的小节、补充本章要求，确认后我将严格按提纲分章撰写。
    </div>
    <div class="outline-body">
      <div v-for="ch in draft" :key="ch.chapterNo" class="outline-chapter">
        <div class="oc-title">{{ ch.chapterName }}</div>
        <div class="oc-sections">
          <a-checkbox
            v-for="(sec, i) in ch.allSections"
            :key="i"
            :checked="ch.sections.includes(sec)"
            @change="(e) => onToggle(ch, sec, e.target.checked)"
          >
            {{ sec }}
          </a-checkbox>
        </div>
        <div v-if="ch.tables && ch.tables.length" class="oc-tables">应有表格：{{ ch.tables.join('、') }}</div>
        <a-input v-model:value="ch.note" size="small" placeholder="本章额外要求（可选），如：重点补充风险等级分析" />
      </div>
    </div>
  </a-modal>
</template>

<script lang="ts" setup>
  import { ref, watch } from 'vue';
  import { Checkbox as ACheckbox, Input as AInput } from 'ant-design-vue';
  import { IOutlineChapter } from '../ShReportAssistant.api';

  const props = defineProps<{ open: boolean; outline: IOutlineChapter[] }>();
  const emit = defineEmits<{
    (e: 'confirm', outline: IOutlineChapter[]): void;
    (e: 'cancel'): void;
  }>();

  /** 深拷贝，编辑期间不污染父组件数据；关闭后保留勾选用下次重开 */
  const draft = ref<IOutlineChapter[]>([]);

  watch(
    () => props.open,
    (v) => {
      if (v) {
        draft.value = JSON.parse(JSON.stringify(props.outline || [])).map((c: any) => ({
          ...c,
          // 全量可选项独立保留，勾选结果仍放在 sections
          allSections: [...(c.sections || [])],
          sections: [...(c.sections || [])],
        }));
      }
    }
  );

  function onToggle(ch: any, sec: string, on: boolean) {
    if (on) {
      if (!ch.sections.includes(sec)) {
        ch.sections.push(sec);
      }
    } else {
      ch.sections = ch.sections.filter((s: string) => s !== sec);
    }
  }

  function onConfirm() {
    const out: IOutlineChapter[] = draft.value
      .map((c: any) => ({
        chapterNo: c.chapterNo,
        chapterName: c.chapterName,
        sections: [...(c.sections || [])],
        tables: [...(c.tables || [])],
        note: c.note,
      }))
      .filter((c) => c.sections.length > 0 || (c.note && c.note.trim()));
    if (!out.length) {
      return;
    }
    emit('confirm', out);
  }

  function onCancel() {
    emit('cancel');
  }
</script>

<style lang="less" scoped>
  .outline-tip {
    background: #e6f4ff;
    border: 1px solid #91caff;
    border-radius: 8px;
    padding: 8px 12px;
    font-size: 12px;
    color: #0958d9;
    margin-bottom: 10px;
  }

  .outline-body {
    max-height: 520px;
    overflow-y: auto;

    .outline-chapter {
      border: 1px solid #eef1f6;
      border-radius: 10px;
      padding: 10px 12px;
      margin-bottom: 10px;
      background: #fafbfd;

      .oc-title {
        font-weight: 500;
        margin-bottom: 8px;
      }

      .oc-sections {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 6px 12px;
        margin-bottom: 8px;
        font-size: 13px;
      }

      .oc-tables {
        font-size: 12px;
        color: #98a2b3;
        margin-bottom: 8px;
      }
    }
  }
</style>
