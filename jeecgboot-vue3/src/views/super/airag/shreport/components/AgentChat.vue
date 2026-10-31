<template>
  <div class="agent-chat">
    <div class="chat-scroll" ref="messagesRef" @scroll="onChatScroll">
      <!-- 欢迎区：仅剩欢迎语时展示，提供可点选的快捷指令 -->
      <div v-if="showHero" class="chat-hero">
        <div class="hero-icon"><RobotOutlined /></div>
        <div class="hero-title">山洪报告生成助手</div>
        <div class="hero-desc">
          用自然语言生成《山洪灾害风险隐患调查评价报告》：可先出大纲确认，再分章撰写、逐章审核；任意章节都可选择导出 Word。
        </div>
        <div class="hero-tips">
          <div v-for="t in heroTips" :key="t.title" class="tip-card" @click="t.run()">
            <component :is="t.icon" class="tip-icon" />
            <div class="tip-body">
              <div class="tip-name">{{ t.title }}</div>
              <div class="tip-desc">{{ t.desc }}</div>
            </div>
          </div>
        </div>
      </div>

      <template v-for="g in renderGroups" :key="g.key">
        <!-- 执行过程块：连续的章节进度消息折叠成一张 ZCode 风格的「工具调用」卡片 -->
        <div v-if="g.kind === 'steps'" class="chat-message assistant">
          <div class="msg-avatar ai"><RobotOutlined /></div>
          <div class="msg-main">
            <div class="steps-block">
              <div class="steps-head" @click="toggleSteps(g)">
                <span v-if="g.running" class="steps-spin"><LoadingOutlined /></span>
                <span v-else-if="g.failed" class="steps-badge fail"><CloseOutlined /></span>
                <span v-else class="steps-badge ok"><CheckOutlined /></span>
                <span class="steps-title">执行过程</span>
                <span class="steps-sub">{{ stepsDone(g) }}/{{ g.items.length }} 项完成</span>
                <DownOutlined class="steps-caret" :class="{ open: !stepsCollapsed(g) }" />
              </div>
              <div v-show="!stepsCollapsed(g)" class="steps-body">
                <div v-for="s in g.items" :key="s.id" class="step-row" :class="'is-' + rowState(s)">
                  <span class="step-icon">
                    <LoadingOutlined v-if="rowState(s) === 'run'" />
                    <CloseOutlined v-else-if="rowState(s) === 'fail'" />
                    <ClockCircleOutlined v-else-if="rowState(s) === 'halt'" />
                    <CheckOutlined v-else />
                  </span>
                  <span class="step-text">{{ stepText(s) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
        <!-- AI 回复：白底气泡，底部角色/时间/悬浮操作 -->
        <div v-else-if="g.msg.role === 'assistant'" class="chat-message assistant">
          <div class="msg-avatar ai"><RobotOutlined /></div>
          <div class="msg-main">
            <div class="chat-bubble" :class="{ 'is-error': isError(g.msg) }">
              <!-- 推理过程（思考深度开启时）：ZCode 式折叠块，回答开始后自动收起 -->
              <div v-if="g.msg.__thinking" class="think-block">
                <div class="think-head" @click="toggleThink(g.msg)">
                  <BulbOutlined class="think-ic" :class="{ run: g.msg.__thinkingActive }" />
                  <span class="think-title">{{ g.msg.__thinkingActive ? '思考中…' : '已深度思考' }}</span>
                  <DownOutlined class="think-caret" :class="{ open: thinkIsOpen(g.msg) }" />
                </div>
                <div v-show="thinkIsOpen(g.msg)" class="think-body">{{ g.msg.__thinking }}</div>
              </div>
              <ShMarkdown :value="g.msg.content" />
              <span v-if="g.msg.__streaming" class="stream-caret"></span>
              <!-- 一期成果知识库参考来源 -->
              <div v-if="g.msg.sources && g.msg.sources.length" class="kb-sources">
                <div class="kb-sources-title"><FileSearchOutlined /> 一期调查成果参考来源</div>
                <div v-for="(s, si) in g.msg.sources" :key="si" class="kb-source-item">
                  <span class="kb-source-name">《{{ s.docName }}》</span>
                  <span v-if="s.score != null" class="kb-source-score">相似度 {{ s.score }}</span>
                </div>
              </div>
              <!-- 流域多候选点选 -->
              <div v-if="g.msg.choices && g.msg.choices.length" class="choose-list">
                <a-button v-for="c in g.msg.choices" :key="c.lybm" size="small" class="choose-btn" @click="chooseWatershed(c)">
                  <template #icon><EnvironmentOutlined /></template>
                  {{ c.lymc }}（{{ c.lybm }}）
                </a-button>
              </div>
            </div>
            <p class="msg-date assistant">
              <span>AI助手</span>
              <span v-if="msgDate(g.msg)">{{ msgDate(g.msg) }}</span>
              <span class="msg-actions">
                <a-tooltip title="复制内容">
                  <span class="act" @click="copyText(g.msg.content)"><CopyOutlined /></span>
                </a-tooltip>
                <a-tooltip v-if="canRegenerate(g.msg)" title="重新生成（重发上一条消息）">
                  <span class="act" @click="regenerateFrom(g.msg)"><RedoOutlined /></span>
                </a-tooltip>
              </span>
            </p>
          </div>
        </div>
        <!-- 用户消息：蓝色气泡靠右 -->
        <div v-else class="chat-message user">
          <div class="msg-main">
            <div class="chat-bubble user-bubble">{{ g.msg.content }}</div>
            <p class="msg-date user">
              <span>我</span>
              <span v-if="msgDate(g.msg)">{{ msgDate(g.msg) }}</span>
              <span class="msg-actions">
                <a-tooltip title="复制内容">
                  <span class="act" @click="copyText(g.msg.content)"><CopyOutlined /></span>
                </a-tooltip>
                <a-tooltip title="重发这条消息">
                  <span class="act" @click="resendUser(g.msg)"><RedoOutlined /></span>
                </a-tooltip>
              </span>
            </p>
          </div>
          <div class="msg-avatar me"><UserOutlined /></div>
        </div>
      </template>

      <!-- 处理中：打字动画气泡（内容已开始流式输出时隐藏） -->
      <div v-if="showTyping" class="chat-message assistant">
        <div class="msg-avatar ai"><RobotOutlined /></div>
        <div class="msg-main">
          <div class="chat-bubble typing-bubble">
            <span class="typing-dots"><i></i><i></i><i></i></span>
            <span class="typing-text">{{ typingText }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 回到顶部 / 回到最新消息：上翻或下翻查看历史时显示对应箭头 -->
    <div class="jump-wrap">
      <div v-if="showJumpTop" class="jump-btn jump-top" title="回到聊天顶部" @click="jumpToTop">
        <ArrowUpOutlined />
      </div>
      <div v-if="showJumpBottom" class="jump-btn jump-latest" title="回到最新消息" @click="jumpToLatest">
        <ArrowDownOutlined />
      </div>
    </div>

    <div class="composer">
      <div v-if="attachChips.length" class="attach-chips">
        <a-tag v-for="c in attachChips" :key="c" closable @close="detachChip(c)">{{ c }}</a-tag>
      </div>
      <!-- 排队任务：生成中输入并回车的消息在此排队，当前轮次结束后自动依次发出 -->
      <div v-if="pendingQueue.length" class="queue-chips">
        <div v-for="q in pendingQueue" :key="q.id" class="queue-chip">
          <ClockCircleOutlined class="queue-icon" />
          <span class="queue-text" :title="q.text">{{ q.text }}</span>
          <CloseOutlined class="queue-remove" @click="removeQueued(q.id)" />
        </div>
      </div>
      <a-textarea
        v-model:value="inputText"
        :bordered="false"
        :auto-size="{ minRows: 2, maxRows: 5 }"
        :placeholder="loading ? '正在生成…现在输入并按 Enter 会排队，本轮结束后自动发出（Esc 停止生成）' : composerPlaceholder"
        @keydown.enter="onComposerEnter"
        @keydown.esc="onComposerEsc"
      />
      <!-- 隐藏的文件/文件夹选择入口（+ 号菜单触发） -->
      <input ref="fileInputRef" type="file" multiple :accept="materialAccept" style="display: none" @change="onFilesPicked" />
      <input ref="folderInputRef" type="file" multiple webkitdirectory style="display: none" @change="onFilesPicked" />
      <div class="composer-foot">
        <div class="foot-left">
          <!-- + 号：添加文件/文件夹作为流域资料（ZCode 上下文添加习惯） -->
          <a-dropdown :trigger="['click']" placement="topLeft">
            <a-button size="small" type="text" class="foot-btn" title="添加文件 / 文件夹">
              <PlusOutlined />
            </a-button>
            <template #overlay>
              <a-menu @click="onPlusMenu">
                <a-menu-item key="file">
                  <FileTextOutlined />
                  上传文件
                </a-menu-item>
                <a-menu-item key="folder">
                  <FolderOutlined />
                  上传文件夹
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
          <!-- 模型 + 思考深度：合并到一个面板（新版 ZCode 习惯），chip 显示当前组合 -->
          <a-popover v-model:open="modelPanelOpen" trigger="click" placement="topLeft" overlay-class-name="sh-model-panel">
            <template #content>
              <div class="model-panel">
                <div class="mp-section">
                  <div class="mp-title">模型<span class="mp-sub">按当前会话生效，章节生成自动套用</span></div>
                  <div class="mp-row" :class="{ active: !currentModelId }" @click="onModelChange(undefined)">
                    <span class="mp-name">默认模型</span>
                    <CheckOutlined v-if="!currentModelId" class="mp-check" />
                  </div>
                  <div v-for="m in modelOptions" :key="m.id" class="mp-row" :class="{ active: m.id === currentModelId }" @click="onModelChange(m.id)">
                    <span class="mp-name">{{ m.name }}</span>
                    <CheckOutlined v-if="m.id === currentModelId" class="mp-check" />
                  </div>
                </div>
                <div class="mp-section">
                  <div class="mp-title">思考深度</div>
                  <div class="mp-row" :class="{ active: thoughtLevel === 'off' }" @click="onThoughtChange('off')">
                    <BulbOutlined class="opt-ic" />
                    <span class="mp-name">关闭思考</span>
                    <span class="mp-desc">直接回答，不展示推理</span>
                    <CheckOutlined v-if="thoughtLevel === 'off'" class="mp-check" />
                  </div>
                  <div class="mp-row" :class="{ active: thoughtLevel === 'standard' }" @click="onThoughtChange('standard')">
                    <BulbOutlined class="opt-ic on" />
                    <span class="mp-name">思考 · 标准</span>
                    <span class="mp-desc">回答前展示推理过程</span>
                    <CheckOutlined v-if="thoughtLevel === 'standard'" class="mp-check" />
                  </div>
                  <div class="mp-row" :class="{ active: thoughtLevel === 'deep' }" @click="onThoughtChange('deep')">
                    <BulbOutlined class="opt-ic deep" />
                    <span class="mp-name">思考 · 深度</span>
                    <span class="mp-desc">引导更充分的分析推理</span>
                    <CheckOutlined v-if="thoughtLevel === 'deep'" class="mp-check" />
                  </div>
                </div>
              </div>
            </template>
            <button type="button" class="model-chip" :disabled="loading" title="模型与思考深度">
              <span class="chip-model">{{ currentModelLabel }}</span>
              <span class="chip-sep">·</span>
              <span class="chip-thought" :class="'lv-' + thoughtLevel">
                <BulbOutlined />
                {{ thoughtLabel }}
              </span>
              <DownOutlined class="chip-caret" />
            </button>
          </a-popover>
          <!-- Token 消耗圆环：紧随模型设置之后（ZCode 布局），点击弹出用量明细 -->
          <a-popover v-model:open="usageOpen" trigger="click" placement="top" overlay-class-name="sh-usage-popover">
            <template #content>
              <div class="usage-panel">
                <div class="usage-head">
                  <span class="usage-title">Token 消耗统计</span>
                  <span class="usage-sub">本会话累计</span>
                </div>
                <template v-if="usageTotal > 0">
                  <div class="usage-total">
                    <span class="usage-total-num">{{ formatTokens(usageData?.totalTokens) }}</span>
                    <span class="usage-calls">{{ usageData?.calls || 0 }} 次模型调用</span>
                  </div>
                  <div class="usage-bar">
                    <span class="usage-bar-in" :style="{ width: usageInPercent + '%' }"></span>
                  </div>
                  <div class="usage-breakdown">
                    <span class="dot in"></span>输入 {{ formatTokens(usageData?.inputTokens) }}
                    <span class="dot out"></span>输出 {{ formatTokens(usageData?.outputTokens) }}
                  </div>
                  <div class="usage-detail">
                    <div v-if="usageData?.scenes?.intent" class="usage-row">
                      <span>意图解析</span><span>{{ usageData.scenes.intent.calls }} 次 · {{ formatTokens(sumTokens(usageData.scenes.intent)) }}</span>
                    </div>
                    <div v-if="usageData?.scenes?.outline" class="usage-row">
                      <span>大纲润色</span><span>{{ usageData.scenes.outline.calls }} 次 · {{ formatTokens(sumTokens(usageData.scenes.outline)) }}</span>
                    </div>
                    <div v-for="(item, no) in usageData?.chapters || {}" :key="no" class="usage-row">
                      <span>{{ chapterName(Number(no)) }}</span>
                      <span>{{ item.calls }} 次 · {{ formatTokens(sumTokens(item)) }}</span>
                    </div>
                  </div>
                  <div v-if="usageData?.lastModel" class="usage-model">最近模型：{{ modelLabel(usageData.lastModel) }}</div>
                  <div v-if="usageData?.estimated" class="usage-est">部分调用未返回用量，已按字符数估算</div>
                </template>
                <div v-else class="usage-empty">本会话还没有模型调用记录</div>
              </div>
            </template>
            <span class="usage-ring" :class="{ hot: usagePercent >= 80 }" title="Token 消耗统计">
              <svg viewBox="0 0 20 20">
                <circle class="ring-bg" cx="10" cy="10" r="7.5" />
                <circle class="ring-in" cx="10" cy="10" r="7.5" :stroke-dasharray="ringDash" />
              </svg>
            </span>
          </a-popover>
        </div>
        <div class="foot-right">
          <!-- 发送/停止共用一个按钮，按状态切换 -->
          <a-tooltip v-if="loading" title="停止生成（Esc）">
            <a-button size="small" danger class="send-btn" @click="onCancel">
              <template #icon><StopOutlined /></template>
              停止
            </a-button>
          </a-tooltip>
          <a-tooltip v-else :title="pendingQueue.length ? `Enter 发送 · 已排队 ${pendingQueue.length} 条` : 'Enter 发送，Shift + Enter 换行'">
            <span class="send-btn-wrap">
              <a-button type="primary" class="send-btn" :disabled="!inputText.trim()" @click="onSend">
                <template #icon><SendOutlined /></template>
                发送
              </a-button>
            </span>
          </a-tooltip>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { computed, nextTick, onMounted, ref, triggerRef, watch } from 'vue';
  import {
    ArrowDownOutlined,
    ArrowUpOutlined,
    BulbOutlined,
    CheckOutlined,
    ClockCircleOutlined,
    CloseOutlined,
    CopyOutlined,
    DatabaseOutlined,
    DownOutlined,
    EnvironmentOutlined,
    FileSearchOutlined,
    FileTextOutlined,
    FolderOutlined,
    LoadingOutlined,
    PlusOutlined,
    RedoOutlined,
    RobotOutlined,
    SendOutlined,
    StopOutlined,
    ThunderboltOutlined,
    UserOutlined,
  } from '@ant-design/icons-vue';
  import ShMarkdown from './ShMarkdown.vue';
  import {
    cancelGenerate,
    confirmOutlineMessage,
    IOutlineChapter,
    IUsageItem,
    materialAccept,
    reconnectStream,
    sendChatMessage,
    uploadMaterial,
  } from '../ShReportAssistant.api';
  import { message } from 'ant-design-vue';
  import { IChatMsg, stampMsg, useReportSession } from '../useReportSession';

  defineOptions({ name: 'ShReportAgentChat' });

  const emit = defineEmits<{
    (e: 'refresh', batchNo: string): void;
    (e: 'progress', p: { chapterNo: number; status: string; message: string; batchNo: string }): void;
    (e: 'outline', outline: IOutlineChapter[]): void;
    (e: 'graph', p: Record<string, any>): void;
  }>();

  const {
    init,
    loading,
    conversationId,
    batchNo,
    activeMessages,
    activeSession,
    applyWatershed,
    boundWatershed,
    downloadWord,
    modelOptions,
    currentModelId,
    onModelChange,
    thoughtLevel,
    onThoughtChange,
    usage,
    applyUsage,
  } = useReportSession();

  const inputText = ref('');
  const attachChips = ref<string[]>([]);
  const messagesRef = ref<HTMLElement>();
  const usageOpen = ref(false);
  const fileInputRef = ref<HTMLInputElement>();
  const folderInputRef = ref<HTMLInputElement>();

  /** + 号菜单：上传文件 / 上传文件夹（作为流域资料，需先绑定流域） */
  function onPlusMenu({ key }: { key: string | number }) {
    if (!boundWatershed.value?.lybm) {
      message.warning('请先绑定流域后再上传资料');
      return;
    }
    (key === 'folder' ? folderInputRef : fileInputRef).value?.click();
  }

  /** 批量上传选中的文件/文件夹内容（逐个走资料上传接口并挂 chips） */
  async function onFilesPicked(e: Event) {
    const input = e.target as HTMLInputElement;
    const files = Array.from(input.files || []);
    input.value = '';
    if (!files.length) {
      return;
    }
    if (!boundWatershed.value?.lybm) {
      message.warning('请先绑定流域');
      return;
    }
    const MAX_FILES = 30;
    const MAX_TOTAL = 200 * 1024 * 1024;
    if (files.length > MAX_FILES) {
      message.warning(`一次最多上传 ${MAX_FILES} 个文件，已截断`);
    }
    const list = files.slice(0, MAX_FILES);
    const total = list.reduce((n, f) => n + f.size, 0);
    if (total > MAX_TOTAL) {
      message.error('文件总大小超过 200MB，请分批上传');
      return;
    }
    let ok = 0;
    let fail = 0;
    for (const f of list) {
      try {
        await uploadMaterial(f, conversationId.value);
        attachChips.value = [...attachChips.value, f.name];
        ok++;
      } catch {
        fail++;
      }
    }
    if (ok) {
      message.success(`已上传 ${ok} 个资料，正在解析`);
    }
    if (fail) {
      message.warning(`${fail} 个文件上传失败`);
    }
  }

  function detachChip(name: string) {
    attachChips.value = attachChips.value.filter((n) => n !== name);
  }
  let abortCtl: AbortController | null = null;
  let streamReader: ReadableStreamDefaultReader<Uint8Array> | null = null;
  let userStopped = false;

  function startTurnSignal(): AbortSignal {
    abortCtl = new AbortController();
    userStopped = false;
    return abortCtl.signal;
  }

  const usageData = computed(() => usage.value);
  const usageTotal = computed(() => usage.value?.totalTokens || 0);
  const usageInPercent = computed(() => {
    const total = usageTotal.value;
    if (!total) {
      return 0;
    }
    return Math.round(((usage.value?.inputTokens || 0) / total) * 100);
  });

  /** Token 用量圆环（ZCode 上下文圆环）：按 128k 上下文窗口估算占用比例 */
  const CONTEXT_BUDGET = 128000;
  const RING_CIRC = 2 * Math.PI * 7.5;
  const usagePercent = computed(() => Math.min(100, Math.round((usageTotal.value / CONTEXT_BUDGET) * 100)));
  const ringDash = computed(() => `${((RING_CIRC * usagePercent.value) / 100).toFixed(2)} ${RING_CIRC.toFixed(2)}`);

  // ------------------------------------------------ 推理过程折叠块（思考深度开启时）

  const thinkManual = ref<Record<string, boolean>>({});

  /** 模型+思考深度合并面板（新版 ZCode 习惯） */
  const modelPanelOpen = ref(false);

  const currentModelLabel = computed(() => {
    if (currentModelId.value) {
      return modelOptions.value.find((m) => m.id === currentModelId.value)?.name || currentModelId.value;
    }
    return '默认模型';
  });

  const THOUGHT_LABELS: Record<string, string> = { off: '关闭思考', standard: '思考·标准', deep: '思考·深度' };
  const thoughtLabel = computed(() => THOUGHT_LABELS[thoughtLevel.value] || '关闭思考');

  /** 展开状态：用户手动切换过用手动值，否则跟随接收状态（接收中展开，结束收起） */
  function thinkIsOpen(m: IChatMsg) {
    const k = m.id || '';
    return thinkManual.value[k] !== undefined ? thinkManual.value[k] : !!m.__thinkingActive;
  }

  function toggleThink(m: IChatMsg) {
    const k = m.id || '';
    thinkManual.value[k] = !thinkIsOpen(m);
  }

  /** 1 万以上按「万」展示，与 zcode 网页版习惯一致 */
  function formatTokens(n?: number) {
    const v = n || 0;
    if (v >= 10000) {
      return (v / 10000).toFixed(1) + '万';
    }
    return v.toLocaleString();
  }

  function sumTokens(item: IUsageItem) {
    return (item?.inputTokens || 0) + (item?.outputTokens || 0);
  }

  const CHAPTER_NAMES = ['流域概况', '前期准备', '外业调查成果', '影响分析', '历史灾害', '防治区分析', '成果与建议'];

  function chapterName(no: number) {
    return `第${no}章 ${CHAPTER_NAMES[no - 1] || ''}`.trim();
  }

  function modelLabel(modelId: string) {
    return modelOptions.value.find((m) => m.id === modelId)?.name || modelId;
  }

  /** 展示用消息：欢迎语由欢迎区代替，不再重复渲染气泡 */
  const visibleMessages = computed(() =>
    activeMessages.value.filter((m) => !(m.content || '').startsWith('你好，我是**山洪报告生成助手**'))
  );

  type RenderItem =
    | { kind: 'msg'; key: string; msg: IChatMsg }
    | { kind: 'steps'; key: string; items: IChatMsg[]; running: boolean; failed: boolean };

  /** 轮次是否仍在进行：停止/断开后，进度行停留在"生成中"的过程块要按已结束收尾 */
  const turnActive = computed(() => loading.value || !!activeSession()?.generating);

  /**
   * 渲染分组：连续的章节进度消息（__progress）折叠成一个「执行过程」卡片，
   * 与 ZCode 的工具调用块一致；其余消息按原样渲染。
   */
  const renderGroups = computed<RenderItem[]>(() => {
    const groups: RenderItem[] = [];
    let cur: Extract<RenderItem, { kind: 'steps' }> | null = null;
    for (const m of visibleMessages.value) {
      if (m.__progress) {
        if (!cur) {
          cur = { kind: 'steps', key: 'steps-' + (m.id || 't' + m.time), items: [], running: false, failed: false };
          groups.push(cur);
        }
        cur.items.push(m);
        const st = stepState(m);
        cur.running = cur.running || (st === 'run' && turnActive.value);
        cur.failed = cur.failed || st === 'fail';
      } else {
        cur = null;
        groups.push({ kind: 'msg', key: m.id || 't' + m.time, msg: m });
      }
    }
    return groups;
  });

  /** 进度行状态：从文本里的 emoji/关键词推断（消息内容保持原样，保证多窗口合并兼容） */
  function stepState(m: IChatMsg): 'run' | 'fail' | 'done' {
    const c = m.content || '';
    if (c.includes('❌') || c.includes('失败')) {
      return 'fail';
    }
    if (c.includes('⏳') || c.includes('生成中')) {
      return 'run';
    }
    return 'done';
  }

  /** 行渲染状态：轮次已结束但进度行还停在"生成中"时按中断（halt）展示，不继续转圈 */
  function rowState(m: IChatMsg): 'run' | 'fail' | 'done' | 'halt' {
    const st = stepState(m);
    return st === 'run' && !turnActive.value ? 'halt' : st;
  }

  function stepText(m: IChatMsg): string {
    return (m.content || '').replace(/^[⏳✅❌]+\s*/, '');
  }

  function stepsDone(g: { items: IChatMsg[] }) {
    return g.items.filter((s) => stepState(s) === 'done').length;
  }

  const stepsCollapsedMap = ref<Record<string, boolean>>({});
  const autoCollapsed = new Set<string>();

  function stepsCollapsed(g: { key: string }) {
    return !!stepsCollapsedMap.value[g.key];
  }

  function toggleSteps(g: { key: string }) {
    stepsCollapsedMap.value[g.key] = !stepsCollapsedMap.value[g.key];
  }

  /** ZCode 习惯：进行中的过程块保持展开，全部结束后自动收起；已自动收起的组不再反复弹开 */
  watch(renderGroups, (groups) => {
    for (const g of groups) {
      if (g.kind !== 'steps') {
        continue;
      }
      if (g.running) {
        if (autoCollapsed.has(g.key)) {
          autoCollapsed.delete(g.key);
          stepsCollapsedMap.value[g.key] = false;
        }
      } else if (!autoCollapsed.has(g.key)) {
        autoCollapsed.add(g.key);
        stepsCollapsedMap.value[g.key] = true;
      }
    }
  });

  /** 无任何可见消息（新会话）时展示欢迎区 */
  const showHero = computed(() => visibleMessages.value.length === 0);

  /** 生成中且当前还没有正在往外蹦字的气泡时，才显示底部「处理中」占位 */
  const showTyping = computed(() => {
    if (!activeSession()?.generating) {
      return false;
    }
    const last = [...visibleMessages.value].reverse().find((m) => m.role === 'assistant' && !m.__progress);
    return !(last && last.__streaming && last.content);
  });

  /** 生成阶段提示文字：收到章节进度事件时更新为具体章节 */
  const stageText = ref('');
  const typingText = computed(() => stageText.value || '助手正在处理，整份报告生成可能需要几分钟到十几分钟…');

  /** 快捷指令：统一走对话链路，未绑定流域时由助手在对话中引导确认 */
  const heroTips = computed(() => {
    const ws = boundWatershed.value;
    const hasWs = !!ws?.lybm;
    return [
      {
        icon: ThunderboltOutlined,
        title: '生成整份报告',
        desc: hasWs ? `为「${ws!.lymc}」依次生成 7 个章节` : '发送后助手会引导你确认流域',
        run: () => {
          const text = hasWs ? `帮我生成${ws!.lymc}的整份报告` : '帮我生成整份报告';
          routeSend(text, (signal, requestId) => sendChatMessage(conversationId.value, text, batchNo.value, signal, requestId));
        },
      },
      {
        icon: FileSearchOutlined,
        title: '先出大纲',
        desc: hasWs ? `确认提纲后再分章撰写「${ws!.lymc}」` : '发送后助手会引导你确认流域',
        run: () => {
          const text = hasWs ? `先出一份${ws!.lymc}的报告大纲` : '先出一份报告大纲';
          routeSend(text, (signal, requestId) => sendChatMessage(conversationId.value, text, batchNo.value, signal, requestId));
        },
      },
      {
        icon: FileTextOutlined,
        title: '查看当前进度',
        desc: '查询当前批次的章节状态清单',
        run: () => {
          routeSend('查看当前报告进度', (signal, requestId) => sendChatMessage(conversationId.value, '查看当前报告进度', batchNo.value, signal, requestId));
        },
      },
      {
        icon: DatabaseOutlined,
        title: '查流域数据',
        desc: '查询小流域数量与防治对象县区分布',
        run: () => {
          const text = '当前有多少小流域？防治对象按县区分布是怎样的？';
          routeSend(text, (signal, requestId) => sendChatMessage(conversationId.value, text, batchNo.value, signal, requestId));
        },
      },
    ];
  });

  /** 滚离底部/顶部超过阈值时显示对应箭头；用户上翻看历史期间暂停自动跟随 */
  const showJumpBottom = ref(false);
  const showJumpTop = ref(false);
  let stickBottom = true;

  function onChatScroll() {
    const el = messagesRef.value;
    if (!el) {
      return;
    }
    const fromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    showJumpBottom.value = fromBottom > 150;
    showJumpTop.value = el.scrollTop > 150;
    stickBottom = fromBottom <= 150;
  }

  /**
   * 滚动到会话底部。force：用户发消息/切会话等明确想看最新的场景；
   * 非强制时若用户正在上翻看历史则不跟随，避免流式输出把人强行拽回底部。
   * 一律瞬时跳转：平滑动画的中间位置会被滚动监听误判为"用户上翻"，打断自动跟随。
   */
  function scrollToBottom(force = false) {
    if (!force && !stickBottom) {
      return;
    }
    stickBottom = true;
    nextTick(() => {
      messagesRef.value?.scrollTo({ top: messagesRef.value.scrollHeight });
    });
  }

  function jumpToLatest() {
    scrollToBottom(true);
  }

  /** 跳到会话最顶部；此时离开底部，自动跟随暂停，由滚动监听就地更新箭头状态 */
  function jumpToTop() {
    messagesRef.value?.scrollTo({ top: 0 });
  }

  // 切换会话后回到底部并恢复自动跟随（新会话视图从最新记录开始）
  watch(conversationId, () => {
    scrollToBottom(true);
  });

  /** 多候选择流域：发送规则解析可识别的确认文本，走正常对话链路 */
  function chooseWatershed(c: { lybm: string; lymc: string }) {
    inputText.value = `确认使用流域：${c.lymc}|${c.lybm}`;
    onSend();
  }

  const composerPlaceholder =
    '告诉我你的需求，例如：帮我生成XX流域的整份报告 / 当前有多少小流域 / 修改第三章，表格再细化一些 / 查看当前报告进度';

  /** Enter 直接发送（ZCode 习惯）；输入法组合中不拦截；Shift+Enter 保留换行 */
  function onComposerEnter(e: KeyboardEvent) {
    if (e.shiftKey || e.isComposing || e.keyCode === 229) {
      return;
    }
    e.preventDefault();
    onSend();
  }

  /** Esc 停止当前生成（ZCode 习惯） */
  function onComposerEsc() {
    if (loading.value) {
      onCancel();
    }
  }

  async function onSend() {
    const content = inputText.value.trim();
    if (!content) {
      return;
    }
    inputText.value = '';
    routeSend(content);
  }

  // ------------------------------------------------ 消息操作：复制 / 重发 / 重新生成

  function isError(m: IChatMsg) {
    return (m.content || '').startsWith('⚠️');
  }

  async function copyText(text: string) {
    if (!text) {
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // 非安全上下文（http）下剪贴板 API 不可用，退回 execCommand
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      try {
        document.execCommand('copy');
      } catch {
        // 忽略
      }
      document.body.removeChild(ta);
    }
    message.success('已复制');
  }

  /** 最后一条 AI 回复才提供「重新生成」，避免历史消息误触 */
  const lastAssistantId = computed(() => {
    for (let i = visibleMessages.value.length - 1; i >= 0; i--) {
      const m = visibleMessages.value[i];
      if (m.role === 'assistant' && !m.__progress) {
        return m.id;
      }
    }
    return undefined;
  });

  function canRegenerate(m: IChatMsg) {
    return !!m.id && m.id === lastAssistantId.value && !loading.value;
  }

  /** 重新生成：重发该回复前最近的一条用户消息（ZCode 的 retry 语义） */
  function regenerateFrom(m: IChatMsg) {
    const msgs = activeMessages.value;
    const idx = msgs.findIndex((x) => x.id === m.id);
    if (idx < 0) {
      return;
    }
    for (let i = idx - 1; i >= 0; i--) {
      const text = msgs[i].role === 'user' ? (msgs[i].content || '').trim() : '';
      if (text) {
        routeSend(text);
        return;
      }
    }
    message.warning('没有找到可以重发的消息');
  }

  function resendUser(m: IChatMsg) {
    const text = (m.content || '').trim();
    if (text) {
      routeSend(text);
    }
  }

  // ------------------------------------------------ 排队发送：生成中的输入不丢，本轮结束自动发出

  const pendingQueue = ref<{ id: string; text: string }[]>([]);

  function enqueueQueued(text: string) {
    pendingQueue.value.push({ id: `q-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`, text });
  }

  function removeQueued(id: string) {
    pendingQueue.value = pendingQueue.value.filter((q) => q.id !== id);
  }

  function pumpQueue() {
    if (loading.value || !pendingQueue.value.length) {
      return;
    }
    const next = pendingQueue.value.shift()!;
    consume(next.text, (signal, requestId) =>
      sendChatMessage(conversationId.value, next.text, batchNo.value, signal, requestId)
    );
  }

  /** 统一发送入口：空闲直接发，生成中入队（ZCode queued messages） */
  function routeSend(content: string, starter?: (signal: AbortSignal, requestId: string) => Promise<Response | any>) {
    const text = (content || '').trim();
    if (!text) {
      return;
    }
    if (loading.value) {
      enqueueQueued(text);
      return;
    }
    consume(text, starter || ((signal, requestId) => sendChatMessage(conversationId.value, text, batchNo.value, signal, requestId)));
  }

  /** 外部触发（地图点选生成 / 路由 query）：走同一条 SSE 消费链路 */
  async function sendText(content: string) {
    const text = (content || '').trim();
    if (!text) {
      return;
    }
    if (!conversationId.value) {
      await init();
    }
    routeSend(text);
  }

  /** 大纲确认后放行生成：用户侧追加确认语并复用同一条 SSE 读取链路 */
  async function submitOutline(outline: IOutlineChapter[]) {
    if (loading.value) {
      return;
    }
    await consume('大纲已确认，请按提纲开始生成。', (signal, requestId) => confirmOutlineMessage(conversationId.value, outline, signal, requestId, '大纲已确认，请按提纲开始生成。'));
  }

  /** 本轮请求标识的本地存根：断线/刷新后凭它调 /receive/{requestId} 重连接口补发错过的事件 */
  function reqKey(convId: string) {
    return `shreport_chat_request__${convId}`;
  }

  function msgDate(m: IChatMsg): string {
    const t = m.time || 0;
    // 历史数据合并时的占位时间（idx 序号，远小于真实毫秒时间戳）不展示
    if (!t || t < 1000000000000) {
      return '';
    }
    const d = new Date(t);
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  }

  async function consume(userText: string, startStream: (signal: AbortSignal, requestId: string) => Promise<Response | any>) {
    loading.value = true;
    stageText.value = '';
    const signal = startTurnSignal();
    // 捕获当前会话的消息数组：流式回复期间切换会话不会串写其他会话
    const msgs = activeMessages.value;
    msgs.push(stampMsg({ role: 'user', content: userText }));
    const session = activeSession();
    const convId = session?.conversationId || conversationId.value;
    const requestId = `req-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
    try {
      localStorage.setItem(reqKey(convId), requestId);
    } catch {
      // localStorage 不可用时不影响对话，仅失去断线重连能力
    }
    if (session) {
      session.generating = true;
      session.updateTime = Date.now();
      session.messages = msgs;
      if (session.archived) {
        // 归档会话继续对话：自动恢复到主列表
        session.archived = false;
      }
      if (session.title === '新会话') {
        session.title = userText.length > 15 ? userText.slice(0, 15) + '…' : userText;
      }
    }
    scrollToBottom(true);
    try {
      const stream = await startStream(signal, requestId);
      if (!stream || !stream.getReader) {
        throw new Error('无返回流');
      }
      const finished = await readStream(stream, msgs);
      if (finished) {
        // 走到 done 才清标记；连接异常中断时保留，供下次进入页面重连补拉
        localStorage.removeItem(reqKey(convId));
      }
    } catch (e: any) {
      if (userStopped || e?.name === 'AbortError') {
        // 用户点停止：不把中断当成失败气泡
      } else {
        msgs.push(stampMsg({ role: 'assistant', content: '⚠️ 请求失败：' + (e?.message || '未知错误') }));
      }
    } finally {
      streamReader = null;
      loading.value = false;
      // done 成功时已摘掉流式标记；异常中断（如 network error）走到这里也要摘，
      // 否则这条气泡在本地永远闪"生成中"光标，还会随存储/同步扩散到其他窗口
      const lastMsg = msgs[msgs.length - 1] as any;
      if (lastMsg?.__streaming) {
        delete lastMsg.__streaming;
      }
      for (const m of msgs) {
        if ((m as any).__thinkingActive) {
          delete (m as any).__thinkingActive;
        }
      }
      // 必须写回归属会话：流式期间用户可能已切到其他会话，
      // 走 activeSession() 会把本会话的消息数组整个赋给别的会话，造成串会话
      if (session) {
        session.generating = false;
        session.messages = msgs;
        session.updateTime = Date.now();
      }
      // SSE 可能漏掉 refresh：流结束后再拉一次章节，避免右侧一直空白
      emit('refresh', batchNo.value);
      scrollToBottom();
      // ZCode 习惯：本轮结束自动发出排队中的下一条消息
      pumpQueue();
    }
  }

  async function onCancel() {
    if (!conversationId.value || userStopped) {
      return;
    }
    userStopped = true;
    abortCtl?.abort();
    try {
      await streamReader?.cancel();
    } catch {
      // 流已结束时 cancel 会报错
    }
    streamReader = null;
    loading.value = false;
    localStorage.removeItem(reqKey(conversationId.value));
    const session = activeSession();
    if (session) {
      session.generating = false;
      session.updateTime = Date.now();
    }
    const last = activeMessages.value[activeMessages.value.length - 1];
    if (last && (last as any).__streaming) {
      delete (last as any).__streaming;
    }
    try {
      await cancelGenerate(conversationId.value);
    } catch {
      // 后端停止失败仍以前端中断为准
    }
    const recent = activeMessages.value[activeMessages.value.length - 1];
    if (!recent || !String(recent.content || '').includes('已停止')) {
      activeMessages.value.push(stampMsg({ role: 'assistant', content: '已停止。已生成的章节会保留。' }));
    }
    // 与 done 一致：清掉本轮所有流式标记，避免进度行挡住叙述气泡的标记清理
    for (const m of activeMessages.value) {
      if ((m as any).__streaming) {
        delete (m as any).__streaming;
      }
      if ((m as any).__thinkingActive) {
        delete (m as any).__thinkingActive;
      }
    }
    emit('refresh', batchNo.value);
    scrollToBottom();
  }

  /** SSE download 事件：对话内导出的 Word 直接触发浏览器下载 */
  async function triggerDownload(payload: string) {
    const [bn, lybm, nos] = payload.split('|');
    if (!bn || !lybm) {
      return;
    }
    const chapterNos = nos
      ? nos
          .split(',')
          .map((n) => Number(n))
          .filter((n) => n >= 1 && n <= 7)
      : undefined;
    try {
      await downloadWord(bn, lybm, `山洪报告_${bn}.docx`, chapterNos);
    } catch {
      // 下载失败由面板的错误提示兜底
    }
  }

  /** 解析 SSE 流：event: xxx \n data: yyy，事件名 text/refresh/watershed/choose/progress/outline/download/error/done。返回是否收到 done */
  async function readStream(stream: ReadableStream, msgs: IChatMsg[]): Promise<boolean> {
    const reader = stream.getReader();
    streamReader = reader;
    const decoder = new TextDecoder('UTF-8');
    let buffer = '';
    let reply = '';
    let finished = false;
    let deltaStarted = false;
    // 章节生成进度 → 聊天内状态行：每个章节一行，随 SSE progress 事件就地更新，
    // 让用户在对话区就能看到「正在生成第几章」，而不是长时间毫无反馈
    const progressLines = new Map<number, IChatMsg>();
    // 流式期间内容在涨而 updateTime 不涨的话，推送水印（syncedTs）会挡住所有中途推送，
    // 其他浏览器要等整轮 done 才能看到对话；每次内容变化就地刷新时间戳放行推送
    const touchSession = () => {
      const s = activeSession();
      if (s) {
        s.updateTime = Date.now();
      }
    };
    const applyProgress = (p: any) => {
      const no = Number(p?.chapterNo);
      if (!no) {
        return;
      }
      const status = String(p.status || '');
      let line = progressLines.get(no);
      if (!line) {
        line = stampMsg({ role: 'assistant', content: '', __progress: true });
        msgs.push(line);
        progressLines.set(no, line);
      }
      const icon = status === '生成中' ? '⏳' : status === '失败' ? '❌' : '✅';
      line.content = `${icon} ${chapterName(no)}：${status}${p.message ? '（' + p.message + '）' : ''}`;
      touchSession();
      if (status === '生成中') {
        stageText.value = `正在生成${chapterName(no)}…`;
      }
      scrollToBottom();
    };
    const ensureAssistant = () => {
      const last = msgs[msgs.length - 1];
      if (last && last.role === 'assistant' && (last as any).__streaming && !last.choices) {
        return last;
      }
      const msg: any = stampMsg({ role: 'assistant', content: '', __streaming: true });
      msgs.push(msg);
      return msg;
    };
    const handleEvent = (event: string, data: string) => {
      if (event === 'text') {
        const msg = ensureAssistant();
        if ((msg as any).__thinkingActive) {
          (msg as any).__thinkingActive = false;
        }
        reply += (reply ? '\n\n' : '') + data;
        msg.content = reply;
        deltaStarted = false;
        touchSession();
        triggerRef(activeMessages);
        scrollToBottom();
      } else if (event === 'delta') {
        // 模型 token：接到同一条气泡末尾，不额外空行
        const msg = ensureAssistant();
        if ((msg as any).__thinkingActive) {
          // 回答开始，推理结束：思考块自动收起
          (msg as any).__thinkingActive = false;
        }
        if (!deltaStarted && msg.content) {
          msg.content += '\n\n';
          reply = msg.content;
        }
        deltaStarted = true;
        reply += data;
        msg.content = reply;
        touchSession();
        triggerRef(activeMessages);
        scrollToBottom();
      } else if (event === 'thinking') {
        // 思考深度开启时模型的推理过程：累积到 __thinking，回答开始前保持展开
        const msg = ensureAssistant();
        (msg as any).__thinking = ((msg as any).__thinking || '') + data;
        (msg as any).__thinkingActive = true;
        touchSession();
        triggerRef(activeMessages);
        scrollToBottom();
      } else if (event === 'refresh') {
        emit('refresh', data);
      } else if (event === 'watershed') {
        const [lybm, lymc] = data.split('|');
        if (lybm) {
          applyWatershed({ lybm, lymc: lymc || lybm }, true);
        }
      } else if (event === 'choose') {
        reply = '';
        try {
          const choices = JSON.parse(data);
          const last = msgs[msgs.length - 1];
          // 后端在 choose 前会先发 text 说明（如"没有精确匹配到…"），
          // 此时把点选按钮直接挂到那条消息上，避免出现两条引导语；
          // 进度状态行不是引导语，不能挂
          if (last && last.role === 'assistant' && !last.choices && last.content && !(last as any).__progress) {
            last.choices = choices;
          } else {
            msgs.push(stampMsg({ role: 'assistant', content: '找到多个同名/相近流域，请点选一个：', choices }));
          }
        } catch {
          const msg = ensureAssistant();
          msg.content = (msg.content ? msg.content + '\n\n' : '') + data;
        }
      } else if (event === 'kb_sources') {
        // 知识库问答的参考来源：附加到当前流式回复上，done 后保留展示
        try {
          const sources = JSON.parse(data);
          if (Array.isArray(sources) && sources.length) {
            const msg = ensureAssistant();
            (msg as any).sources = sources;
            scrollToBottom();
          }
        } catch {
          // 来源事件解析失败忽略，不影响正文
        }
      } else if (event === 'progress') {
        try {
          const p = JSON.parse(data);
          emit('progress', p);
          applyProgress(p);
        } catch {
          // 进度事件解析失败忽略
        }
      } else if (event === 'graph') {
        try {
          emit('graph', JSON.parse(data));
        } catch {
          // 图节点事件解析失败忽略
        }
      } else if (event === 'usage') {
        // 每次模型调用后推送的累计 token 用量快照
        try {
          applyUsage(JSON.parse(data));
        } catch {
          // 用量事件解析失败忽略
        }
      } else if (event === 'outline') {
        try {
          emit('outline', JSON.parse(data));
        } catch {
          // 大纲解析失败忽略
        }
      } else if (event === 'download') {
        triggerDownload(data);
      } else if (event === 'error') {
        const msg = ensureAssistant();
        msg.content = (msg.content ? msg.content + '\n\n' : '') + '⚠️ ' + data;
      } else if (event === 'done') {
        // 清理本轮所有流式标记：进度行会排在叙述气泡之后，只清最后一条
        // 会让生成叙述气泡永久卡住"生成中"光标，并随推送把标记带到其他窗口
        for (const m of msgs) {
          if ((m as any).__streaming) {
            delete (m as any).__streaming;
          }
          if ((m as any).__thinkingActive) {
            delete (m as any).__thinkingActive;
          }
        }
        finished = true;
      }
    };
    // 挂起的 read() 必须跨轮次复用：章节生成间隔常超过看门狗时长，
    // 若每轮都新建 read()，数据会被填入上一轮被遗弃的 promise，导致推送事件全部丢失
    let pendingRead: Promise<any> | null = null;
    const readChunk = async (): Promise<any> => {
      // 8 秒空闲看门狗：代理可能不转发流结束信号，空闲时解析残留 buffer 判断是否已结束
      if (!pendingRead) {
        pendingRead = reader.read();
      }
      let timer: any = null;
      const idle = new Promise((resolve) => {
        timer = setTimeout(() => resolve({ idle: true }), 8000);
      });
      try {
        const result: any = await Promise.race([pendingRead, idle]);
        // 仅真正读到数据时清空挂起 promise；超时后继续等待同一个 read()
        if (!result?.idle) {
          pendingRead = null;
        }
        return result;
      } finally {
        if (timer) {
          clearTimeout(timer);
        }
      }
    };
    // 连续空闲上限：8s × 24 ≈ 3.2 分钟收不到任何事件（含进度）即视为连接失效，
    // 主动断开并释放输入区，避免 done 被代理吞掉后 loading 永久卡死；后台生成不受影响
    let idleCount = 0;
    const MAX_IDLE_TICKS = 24;
    const closeStreamingMsg = () => {
      const last = msgs[msgs.length - 1];
      if (last && (last as any).__streaming) {
        delete (last as any).__streaming;
      }
      if (last && (last as any).__thinkingActive) {
        delete (last as any).__thinkingActive;
      }
    };
    while (!finished) {
      if (userStopped) {
        closeStreamingMsg();
        break;
      }
      const result: any = await readChunk();
      if (result?.idle) {
        if (buffer.includes('event:done')) {
          handleEvent('done', 'ok');
        }
        if (finished) {
          break;
        }
        idleCount++;
        if (idleCount >= MAX_IDLE_TICKS) {
          try {
            await reader.cancel();
          } catch {
            // 流已结束/已关闭时 cancel 会报错，忽略
          }
          closeStreamingMsg();
          msgs.push(
            stampMsg({
              role: 'assistant',
              content:
                '⚠️ 连接超过 3 分钟没有收到新事件，已自动断开。生成仍在后台进行，请点右侧章节面板的「刷新章节」，或发送「查看当前报告进度」。',
            })
          );
          emit('refresh', batchNo.value);
          scrollToBottom();
          break;
        }
        continue; // 流程执行中长时间无事件属正常，继续等待
      }
      idleCount = 0;
      const { done, value } = result;
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop() || '';
      for (const part of parts) {
        if (!part.trim()) {
          continue;
        }
        let event = 'message';
        const dataLines: string[] = [];
        for (const line of part.split('\n')) {
          if (line.startsWith('event:')) {
            event = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).trim());
          }
        }
        if (dataLines.length) {
          handleEvent(event, dataLines.join('\n'));
        }
        if (finished) {
          return true;
        }
      }
      // 兜底：SSE 经代理后最后一个事件可能缺少结尾空行而滞留在 buffer 中
      if (!finished && buffer.includes('event:done')) {
        const donePart = buffer.trim();
        buffer = '';
        let ev = 'message';
        const lines: string[] = [];
        for (const line of donePart.split('\n')) {
          if (line.startsWith('event:')) {
            ev = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            lines.push(line.slice(5).trim());
          }
        }
        handleEvent(ev, lines.join('\n'));
      }
    }
    buffer += decoder.decode();
    if (buffer.trim()) {
      const last = msgs[msgs.length - 1];
      if (last && (last as any).__streaming) {
        delete (last as any).__streaming;
      }
    }
    return finished;
  }

  /**
   * 断线重连：上一窗口的对话轮还在后台进行时（本地留有 requestId 存根），
   * 进页面即调 /receive/{requestId} 接回同一条 SSE，服务端按序补发错过的消息。
   * 重连会完整重放本轮事件，先丢掉本地已渲染的本轮半截内容，避免重复气泡。
   */
  async function tryReconnect() {
    const convId = conversationId.value;
    const rid = localStorage.getItem(reqKey(convId));
    if (!rid || loading.value) {
      return;
    }
    const session = activeSession();
    if (!session) {
      return;
    }
    loading.value = true;
    session.generating = true;
    stageText.value = '正在重新接入生成进度…';
    try {
      const stream = await reconnectStream(rid);
      const msgs = activeMessages.value;
      const lastUserIdx = msgs.map((m) => m.role).lastIndexOf('user');
      if (lastUserIdx >= 0) {
        msgs.splice(lastUserIdx + 1);
      }
      await readStream(stream, msgs);
    } catch {
      // 重连失败（轮次已结束/网络不可用）：清掉标记，走轮询兜底
    } finally {
      localStorage.removeItem(reqKey(convId));
      loading.value = false;
      session.generating = false;
      emit('refresh', batchNo.value);
      scrollToBottom();
      pumpQueue();
    }
  }

  onMounted(async () => {
    await init();
    scrollToBottom(true);
    await tryReconnect();
  });

  defineExpose({ getBatchNo: () => batchNo.value, submitOutline, sendText });
</script>

<style lang="less" scoped>
  .agent-chat {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;
    background: #f7f9fc;

    /* 回顶部/回最新箭头：悬浮在输入框上方右侧，零高度占位不挤压布局 */
    .jump-wrap {
      position: relative;
      height: 0;
      z-index: 5;

      .jump-btn {
        position: absolute;
        right: 16px;
        width: 34px;
        height: 34px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        background: #fff;
        border: 1px solid #e5eaf2;
        box-shadow: 0 4px 12px rgba(16, 33, 61, 0.12);
        color: @primary-color;
        font-size: 15px;
        cursor: pointer;
        transition: all 0.2s;

        &:hover {
          transform: translateY(-2px);
          box-shadow: 0 6px 16px rgba(16, 33, 61, 0.18);
        }
      }

      .jump-top {
        bottom: 54px;
      }

      .jump-latest {
        bottom: 12px;
      }
    }

    .chat-scroll {
      flex: 1;
      overflow-y: auto;
      padding: 16px 18px;

      /* 执行过程块：章节生成进度的 ZCode 工具调用风格卡片 */
      .steps-block {
        width: 100%;
        max-width: 560px;
        border: 1px solid #e2e9f5;
        border-left: 3px solid @primary-color;
        border-radius: 10px;
        background: #fbfcfe;
        overflow: hidden;
        box-shadow: 0 1px 3px rgba(16, 33, 61, 0.04);

        .steps-head {
          display: flex;
          align-items: center;
          gap: 8px;
          padding: 7px 12px;
          cursor: pointer;
          user-select: none;
          font-size: 12px;
          color: #475467;
          background: #f5f8fc;
          transition: background-color 0.15s;

          &:hover {
            background: #eef3fa;
          }

          .steps-spin {
            color: @primary-color;
            font-size: 12px;
            animation: steps-rotate 1s linear infinite;
            display: inline-flex;
          }

          .steps-badge {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 16px;
            height: 16px;
            border-radius: 50%;
            font-size: 10px;
            color: #fff;

            &.ok {
              background: #52c41a;
            }

            &.fail {
              background: #ff4d4f;
            }
          }

          .steps-title {
            font-weight: 600;
            color: #344054;
          }

          .steps-sub {
            color: #98a2b3;
          }

          .steps-caret {
            margin-left: auto;
            font-size: 10px;
            color: #98a2b3;
            transition: transform 0.2s;

            &.open {
              transform: rotate(180deg);
            }
          }
        }

        .steps-body {
          padding: 6px 12px 8px;

          .step-row {
            display: flex;
            align-items: baseline;
            gap: 8px;
            padding: 4px 0;
            font-size: 12px;
            line-height: 1.6;
            color: #475467;

            & + .step-row {
              border-top: 1px dashed #eef1f6;
            }

            .step-icon {
              flex: 0 0 auto;
              display: inline-flex;
              align-items: center;
              font-size: 11px;

              .anticon {
                transform: translateY(1px);
              }
            }

            &.is-run {
              .step-icon {
                color: @primary-color;

                .anticon {
                  animation: steps-rotate 1s linear infinite;
                }
              }
            }

            &.is-done {
              .step-icon {
                color: #52c41a;
              }
            }

            &.is-halt {
              .step-icon {
                color: #98a2b3;
              }

              .step-text {
                color: #98a2b3;
              }
            }

            &.is-fail {
              .step-icon {
                color: #ff4d4f;
              }

              .step-text {
                color: #b42318;
              }
            }

            .step-text {
              min-width: 0;
              word-break: break-word;
            }
          }
        }
      }

      /* 欢迎区 */
      .chat-hero {
        display: flex;
        flex-direction: column;
        align-items: center;
        text-align: center;
        padding: 26px 16px 10px;

        .hero-icon {
          width: 52px;
          height: 52px;
          border-radius: 15px;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 26px;
          color: #fff;
          background: linear-gradient(135deg, @primary-color 0%, #5b8ff9 100%);
          box-shadow: 0 6px 16px fade(@primary-color, 30%);
          margin-bottom: 14px;
        }

        .hero-title {
          font-size: 17px;
          font-weight: 600;
          color: rgba(0, 0, 0, 0.88);
          margin-bottom: 6px;
        }

        .hero-desc {
          font-size: 12px;
          color: #667085;
          line-height: 1.7;
          max-width: 480px;
          margin-bottom: 18px;
        }

        .hero-tips {
          display: flex;
          flex-direction: column;
          gap: 8px;
          width: 100%;
          max-width: 420px;

          .tip-card {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 10px 14px;
            border-radius: 10px;
            background: #fff;
            border: 1px solid #eef1f6;
            cursor: pointer;
            text-align: left;
            transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;

            &:hover {
              border-color: fade(@primary-color, 45%);
              box-shadow: 0 3px 10px rgba(16, 33, 61, 0.07);
              transform: translateY(-1px);
            }

            .tip-icon {
              flex: 0 0 auto;
              font-size: 16px;
              color: @primary-color;
            }

            .tip-body {
              flex: 1;
              min-width: 0;

              .tip-name {
                font-size: 13px;
                font-weight: 500;
                color: rgba(0, 0, 0, 0.85);
              }

              .tip-desc {
                font-size: 11px;
                color: #98a2b3;
                margin-top: 1px;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
              }
            }
          }
        }
      }

      .chat-message {
        display: flex;
        margin-bottom: 18px;

        &:last-child {
          margin-bottom: 0;
        }

        &:hover {
          .msg-actions {
            opacity: 1 !important;
          }
        }

        &.user {
          justify-content: flex-end;
        }

        .msg-avatar {
          flex: 0 0 30px;
          width: 30px;
          height: 30px;
          border-radius: 9px;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 15px;

          &.ai {
            color: #fff;
            background: linear-gradient(135deg, @primary-color 0%, #5b8ff9 100%);
            margin-right: 10px;
          }

          &.me {
            color: #475467;
            background: #e7ecf3;
            margin-left: 10px;
            border-radius: 50%;
          }
        }

        .msg-main {
          display: flex;
          flex-direction: column;
          max-width: 82%;
          min-width: 0;
        }

        /* AI 回复：白底气泡（原样式），内含思考折叠块/知识库来源/流域点选 */
        .chat-bubble {
          max-width: 100%;
          padding: 9px 13px;
          border-radius: 3px 12px 12px 12px;
          background: #fff;
          border: 1px solid #eef1f6;
          word-break: break-word;
          line-height: 1.7;
          box-shadow: 0 1px 2px rgba(16, 33, 61, 0.03);

          /* 错误气泡：淡红底色提示，可一键重试 */
          &.is-error {
            background: #fff7f6;
            border-color: #ffccc7;
          }

          .stream-caret {
            display: inline-block;
            width: 7px;
            height: 14px;
            margin-left: 2px;
            vertical-align: -2px;
            background: @primary-color;
            animation: stream-caret-blink 0.9s step-end infinite;
          }

          /* 推理过程折叠块（思考深度开启时） */
          .think-block {
            margin-bottom: 8px;
            border: 1px solid #eceff5;
            border-radius: 8px;
            background: #fafbfd;
            overflow: hidden;

            .think-head {
              display: flex;
              align-items: center;
              gap: 6px;
              padding: 5px 10px;
              cursor: pointer;
              user-select: none;
              font-size: 12px;
              color: #667085;
              transition: background-color 0.15s;

              &:hover {
                background: #f2f5fa;
              }

              .think-ic {
                font-size: 12px;
                color: #98a2b3;

                &.run {
                  color: @primary-color;
                  animation: steps-rotate 1.2s linear infinite;
                }
              }

              .think-title {
                font-weight: 500;
              }

              .think-caret {
                margin-left: auto;
                font-size: 10px;
                color: #98a2b3;
                transition: transform 0.2s;

                &.open {
                  transform: rotate(180deg);
                }
              }
            }

            .think-body {
              padding: 6px 12px 9px;
              border-top: 1px dashed #eef1f6;
              font-size: 12px;
              line-height: 1.8;
              color: #667085;
              white-space: pre-wrap;
              word-break: break-word;
            }
          }

          .choose-list {
            margin-top: 8px;
            display: flex;
            flex-direction: column;
            gap: 6px;
            align-items: flex-start;

            .choose-btn {
              text-align: left;
              white-space: normal;
              height: auto;
            }
          }

          /* 一期成果知识库参考来源 */
          .kb-sources {
            margin-top: 8px;
            padding: 7px 10px;
            border-radius: 8px;
            background: #f6f9ff;
            border: 1px solid #dce7fb;

            .kb-sources-title {
              display: flex;
              align-items: center;
              gap: 5px;
              font-size: 12px;
              font-weight: 600;
              color: #3b6fd4;
              margin-bottom: 4px;
            }

            .kb-source-item {
              display: flex;
              align-items: baseline;
              gap: 8px;
              font-size: 12px;
              color: #475467;
              line-height: 1.6;

              .kb-source-name {
                color: #344054;
              }

              .kb-source-score {
                color: #98a2b3;
                font-size: 11px;
                white-space: nowrap;
              }
            }
          }

          /* 打字动画气泡 */
          &.typing-bubble {
            display: flex;
            align-items: center;
            gap: 10px;
            color: #98a2b3;

            .typing-dots {
              display: inline-flex;
              gap: 4px;

              i {
                width: 6px;
                height: 6px;
                border-radius: 50%;
                background: @primary-color;
                opacity: 0.35;
                animation: typing-blink 1.2s infinite;

                &:nth-child(2) {
                  animation-delay: 0.2s;
                }

                &:nth-child(3) {
                  animation-delay: 0.4s;
                }
              }
            }

            .typing-text {
              font-size: 12px;
            }
          }
        }

        /* 用户消息：蓝色气泡 */
        .chat-bubble.user-bubble {
          align-self: flex-end;
          max-width: 100%;
          padding: 9px 13px;
          border-radius: 12px 3px 12px 12px;
          background: @primary-color;
          color: #fff;
          word-break: break-word;
          line-height: 1.7;
          white-space: pre-wrap;
          box-shadow: 0 1px 2px rgba(16, 33, 61, 0.12);
        }

        /* 用户消息底部 meta（角色+时间+悬浮操作） */
        .msg-date {
          display: flex;
          gap: 6px;
          margin: 3px 2px 0;
          font-size: 11px;
          color: #98a2b3;
          align-items: center;

          &.user {
            justify-content: flex-end;
          }

          .msg-actions {
            display: inline-flex;
            gap: 2px;
            opacity: 0;
            transition: opacity 0.15s;

            .act {
              display: inline-flex;
              align-items: center;
              justify-content: center;
              width: 20px;
              height: 20px;
              border-radius: 5px;
              cursor: pointer;
              color: #98a2b3;
              font-size: 12px;
              transition: all 0.15s;

              &:hover {
                color: @primary-color;
                background: fade(@primary-color, 10%);
              }
            }
          }
        }
      }
    }

    /* 输入区 */
    .composer {
      flex: 0 0 auto;
      margin: 0 14px 14px;

      .attach-chips {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
        margin-bottom: 6px;
      }

      /* 排队任务 chips：生成中回车的消息，本轮结束后自动发出 */
      .queue-chips {
        display: flex;
        flex-direction: column;
        gap: 4px;
        margin-bottom: 6px;

        .queue-chip {
          display: flex;
          align-items: center;
          gap: 6px;
          padding: 4px 8px;
          border-radius: 8px;
          background: #f5f8fc;
          border: 1px dashed #d6e0ef;
          font-size: 12px;
          color: #475467;

          .queue-icon {
            color: @primary-color;
            font-size: 12px;
          }

          .queue-text {
            flex: 1;
            min-width: 0;
            overflow: hidden;
            white-space: nowrap;
            text-overflow: ellipsis;
          }

          .queue-remove {
            flex: 0 0 auto;
            color: #98a2b3;
            font-size: 11px;
            cursor: pointer;
            transition: color 0.15s;

            &:hover {
              color: #ff4d4f;
            }
          }
        }
      }
      padding: 8px 12px 10px;
      border-radius: 12px;
      background: #fff;
      border: 1px solid #e4e9f1;
      box-shadow: 0 2px 8px rgba(16, 33, 61, 0.05);
      transition: border-color 0.2s, box-shadow 0.2s;

      &:focus-within {
        border-color: fade(@primary-color, 55%);
        box-shadow: 0 0 0 2px fade(@primary-color, 12%);
      }

      :deep(.ant-input) {
        font-size: 13px;
        background: transparent;
      }

      /* 底部操作栏：左（+/模型/思考深度）右（token 圆环/发送）两组，ZCode 布局 */
      .composer-foot {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 8px;
        padding-top: 6px;
        border-top: 1px solid #f2f4f7;

        .foot-left,
        .foot-right {
          display: flex;
          align-items: center;
          gap: 4px;
          min-width: 0;
        }

        .foot-btn {
          color: #667085;

          &:hover {
            color: @primary-color;
            background: fade(@primary-color, 8%);
          }
        }

        /* 模型+思考深度合并 chip（点击弹出统一面板） */
        .model-chip {
          display: inline-flex;
          align-items: center;
          gap: 5px;
          height: 24px;
          padding: 0 8px;
          border: none;
          background: transparent;
          border-radius: 6px;
          cursor: pointer;
          max-width: 260px;
          overflow: hidden;
          font-size: 12px;
          color: #8a909c;
          transition: background-color 0.2s, color 0.2s;

          &:hover:not(:disabled),
          &:focus-visible {
            background: #f2f3f7;
            color: #4b5563;
          }

          &:disabled {
            cursor: not-allowed;
            opacity: 0.6;
          }

          .chip-model {
            overflow: hidden;
            white-space: nowrap;
            text-overflow: ellipsis;
            min-width: 0;
          }

          .chip-sep {
            color: #c3c9d4;
          }

          .chip-thought {
            display: inline-flex;
            align-items: center;
            gap: 3px;
            white-space: nowrap;

            .anticon {
              font-size: 11px;
            }

            &.lv-standard {
              color: @primary-color;
            }

            &.lv-deep {
              color: #722ed1;
            }
          }

          .chip-caret {
            font-size: 9px;
            color: #a8adb8;
          }
        }

        /* Token 用量圆环：填充比例 = 会话累计 / 128k 上下文估算 */
        .usage-ring {
          display: inline-flex;
          width: 22px;
          height: 22px;
          cursor: pointer;
          margin-right: 4px;

          svg {
            width: 100%;
            height: 100%;
          }

          circle {
            fill: none;
            stroke-width: 2.4;

            &.ring-bg {
              stroke: #e5eaf2;
            }

            &.ring-in {
              stroke: @primary-color;
              stroke-linecap: round;
              transform: rotate(-90deg);
              transform-origin: center;
              transition: stroke-dasharray 0.4s;
            }
          }

          &.hot .ring-in {
            stroke: #fa8c16;
          }

          &:hover {
            .ring-bg {
              stroke: #d6e0ef;
            }
          }
        }

        .send-btn {
          border-radius: 8px;
        }
      }
    }
  }

  @keyframes typing-blink {
    0%,
    60%,
    100% {
      opacity: 0.35;
    }
    30% {
      opacity: 1;
    }
  }

  @keyframes stream-caret-blink {
    0%,
    49% {
      opacity: 1;
    }
    50%,
    100% {
      opacity: 0;
    }
  }

  @keyframes steps-rotate {
    from {
      transform: rotate(0deg);
    }
    to {
      transform: rotate(360deg);
    }
  }
</style>

<style lang="less">
  /* 模型+思考深度合并面板（popover 渲染在组件外，必须用非 scoped 全局样式） */
  .sh-model-panel {
    .model-panel {
      min-width: 250px;
      font-size: 13px;

      .mp-section {
        padding: 4px 0 6px;

        & + .mp-section {
          border-top: 1px solid #f2f4f7;
        }
      }

      .mp-title {
        padding: 4px 10px 6px;
        font-size: 12px;
        font-weight: 600;
        color: #344054;

        .mp-sub {
          margin-left: 8px;
          font-weight: 400;
          font-size: 11px;
          color: #98a2b3;
        }
      }

      .mp-row {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 7px 10px;
        cursor: pointer;
        border-radius: 8px;
        transition: background-color 0.15s;

        &:hover {
          background: #f5f8fc;
        }

        &.active {
          background: fade(@primary-color, 6%);
        }

        .mp-name {
          font-size: 13px;
          color: #344054;
          white-space: nowrap;
        }

        .mp-desc {
          flex: 1;
          min-width: 0;
          font-size: 11px;
          color: #98a2b3;
          text-align: right;
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
        }

        .mp-check {
          color: @primary-color;
          font-size: 12px;
        }

        .opt-ic {
          color: #98a2b3;
          font-size: 12px;

          &.on {
            color: @primary-color;
          }

          &.deep {
            color: #722ed1;
          }
        }

        &.active .mp-name {
          color: @primary-color;
          font-weight: 500;
        }
      }
    }
  }

  /* Token 统计弹层（popover 渲染在组件外，必须用非 scoped 全局样式） */
  .sh-usage-popover {    .usage-panel {
      min-width: 260px;
      font-size: 12px;

      .usage-head {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        margin-bottom: 8px;

        .usage-title {
          font-size: 13px;
          font-weight: 600;
          color: rgba(0, 0, 0, 0.88);
        }

        .usage-sub {
          color: #98a2b3;
          font-size: 11px;
        }
      }

      .usage-total {
        display: flex;
        align-items: baseline;
        gap: 8px;

        .usage-total-num {
          font-size: 22px;
          font-weight: 600;
          color: @primary-color;
        }

        .usage-calls {
          color: #98a2b3;
        }
      }

      .usage-bar {
        display: flex;
        height: 6px;
        border-radius: 3px;
        overflow: hidden;
        background: #52c41a33;
        margin: 8px 0 4px;

        .usage-bar-in {
          background: @primary-color;
          transition: width 0.3s;
        }
      }

      .usage-breakdown {
        display: flex;
        align-items: center;
        gap: 4px;
        color: #667085;
        margin-bottom: 8px;

        .dot {
          width: 6px;
          height: 6px;
          border-radius: 50%;
          display: inline-block;

          &.in {
            background: @primary-color;
            margin-left: 8px;
          }

          &.out {
            background: #52c41a;
          }
        }
      }

      .usage-detail {
        border-top: 1px solid #f2f4f7;
        padding-top: 6px;

        .usage-row {
          display: flex;
          justify-content: space-between;
          gap: 12px;
          color: #475467;
          line-height: 1.9;

          span:last-child {
            color: #98a2b3;
          }
        }
      }

      .usage-model {
        margin-top: 6px;
        color: #98a2b3;
      }

      .usage-est {
        margin-top: 4px;
        color: #d46b08;
        font-size: 11px;
      }

      .usage-empty {
        color: #98a2b3;
        padding: 4px 0;
      }
    }
  }
</style>
