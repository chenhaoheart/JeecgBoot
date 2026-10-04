<template>
  <div class="markdown-body sh-md" v-html="parsedText"></div>
</template>

<script lang="ts" setup>
  // 与系统 AI 助手（aiapp/chat/chatText.vue）保持同一套 markdown-it 渲染管线与 github-markdown 样式，
  // 使对话内容支持表格、列表、标题、代码块等多种格式，而不是 showdown 的紧凑纯文本
  import { ref, watch } from 'vue';
  import { throttle } from 'lodash-es';
  import MarkdownIt from 'markdown-it';
  import mila from 'markdown-it-link-attributes';
  import hljs from 'highlight.js';
  import '/@/views/super/airag/aiapp/chat/style/github-markdown.less';
  import '/@/views/super/airag/aiapp/chat/style/highlight.less';
  import { sanitizeRichText } from '/@/utils/htmlSanitizer';

  const props = defineProps({
    value: { type: String, default: '' },
  });

  const parsedText = ref('');

  const mdi = new MarkdownIt({
    html: true,
    linkify: true,
    highlight(code, language) {
      const validLang = !!(language && hljs.getLanguage(language));
      if (validLang) {
        return `<pre class="code-block-wrapper"><code class="hljs ${language}">${hljs.highlight(code, { language }).value}</code></pre>`;
      }
      return `<pre class="code-block-wrapper"><code class="hljs">${hljs.highlightAuto(code).value}</code></pre>`;
    },
  });
  mdi.use(mila, { attrs: { target: '_blank', rel: 'noopener' } });

  // 流式输出期间 token 高频到达，节流 100ms 再重新渲染，与 /ai 助手节奏一致
  const update = throttle(() => {
    parsedText.value = sanitizeRichText(mdi.render(props.value || ''));
  }, 100);

  watch(() => props.value, update, { immediate: true });
</script>

<style lang="less" scoped>
  .sh-md {
    background: transparent;

    :deep(table) {
      display: block;
      width: max-content;
      max-width: 100%;
      overflow: auto;
      font-size: 13px;
    }

    :deep(th),
    :deep(td) {
      white-space: nowrap;
    }

    :deep(p:last-child),
    :deep(ul:last-child),
    :deep(ol:last-child),
    :deep(table:last-child) {
      margin-bottom: 0;
    }
  }
</style>
