<template>
  <div ref="rootRef" class="markdown-body sh-md" v-html="parsedText"></div>
</template>

<script lang="ts" setup>
  // 与系统 AI 助手（aiapp/chat/chatText.vue）保持同一套 markdown-it 渲染管线与 github-markdown 样式，
  // 使对话内容支持表格、列表、标题、代码块等多种格式，而不是 showdown 的紧凑纯文本
  import { nextTick, ref, watch } from 'vue';
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

  const rootRef = ref<HTMLElement>();
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

  /** 给代码块挂复制按钮：v-html 内容不归 Vue 管，每次渲染后直接在 DOM 上补（ZCode 代码块习惯） */
  function decorateCodeBlocks() {
    const root = rootRef.value;
    if (!root) {
      return;
    }
    root.querySelectorAll('pre').forEach((pre) => {
      const el = pre as HTMLElement;
      if (el.dataset.copyBtn === '1') {
        return;
      }
      el.dataset.copyBtn = '1';
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'md-copy-btn';
      btn.textContent = '复制';
      btn.addEventListener('click', async (ev) => {
        ev.stopPropagation();
        const code = el.querySelector('code');
        const text = code ? code.textContent || '' : '';
        let ok = true;
        try {
          await navigator.clipboard.writeText(text);
        } catch {
          try {
            const range = document.createRange();
            range.selectNodeContents(code || el);
            const sel = window.getSelection();
            sel?.removeAllRanges();
            sel?.addRange(range);
            ok = document.execCommand('copy');
            sel?.removeAllRanges();
          } catch {
            ok = false;
          }
        }
        btn.textContent = ok ? '已复制' : '复制失败';
        setTimeout(() => (btn.textContent = '复制'), 1200);
      });
      el.appendChild(btn);
    });
  }

  // 流式输出期间 token 高频到达，节流 100ms 再重新渲染，与 /ai 助手节奏一致
  const update = throttle(() => {
    parsedText.value = sanitizeRichText(mdi.render(props.value || ''));
    nextTick(decorateCodeBlocks);
  }, 100);

  watch(() => props.value, update, { immediate: true });
</script>

<style lang="less" scoped>
  .sh-md {
    background: transparent;

    :deep(pre) {
      position: relative;

      > .md-copy-btn {
        position: absolute;
        top: 6px;
        right: 6px;
        padding: 1px 8px;
        font-size: 11px;
        line-height: 18px;
        color: #c9d1d9;
        background: rgba(110, 118, 129, 0.45);
        border: none;
        border-radius: 6px;
        cursor: pointer;
        opacity: 0;
        transition: opacity 0.15s, background-color 0.15s;

        &:hover {
          background: rgba(110, 118, 129, 0.75);
          color: #fff;
        }
      }

      &:hover > .md-copy-btn {
        opacity: 1;
      }
    }

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
