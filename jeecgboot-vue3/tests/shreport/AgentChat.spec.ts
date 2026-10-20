/**
 * 山洪报告助手 - AgentChat 对话组件测试
 *
 * 覆盖核心用户路径：
 * 1. 首次进入自动创建会话并展示欢迎语
 * 2. 发送需求后解析 SSE 流（text/refresh/done），流式渲染回复并通知父组件刷新章节
 * 3. 接口异常时在气泡内提示错误且不抛出未捕获异常
 * 4. 重新进入时从 localStorage 恢复最近会话，不重复创建
 * 5. 多候选 choose 事件渲染点选按钮
 */

// ---- 模块 mock（工厂必须自包含；组件需在 mock 注册后再 require，避免拿到真实模块） ----
jest.mock('/@/views/super/airag/shreport/ShReportAssistant.api', () => ({
  createConversation: jest.fn(),
  sendChatMessage: jest.fn(),
  confirmOutlineMessage: jest.fn(),
  bindWatershed: jest.fn().mockResolvedValue({}),
  bindModel: jest.fn().mockResolvedValue({}),
  getAgentModels: jest.fn().mockResolvedValue([
    { id: 'M1', name: '智谱GLM-4.7', provider: 'zhipu', modelName: 'glm-4.7-flash' },
    { id: 'M2', name: 'DeepSeek', provider: 'deepseek', modelName: 'deepseek-chat' },
  ]),
  cancelGenerate: jest.fn().mockResolvedValue({}),
  getContext: jest.fn().mockResolvedValue({}),
  downloadReport: jest.fn(),
  getWatersheds: jest.fn().mockResolvedValue([]),
  getQcIssues: jest.fn().mockResolvedValue([]),
  getChapterList: jest.fn().mockResolvedValue([]),
  listServerSessions: jest.fn().mockResolvedValue([]),
  saveServerSession: jest.fn().mockResolvedValue({}),
  deleteServerSession: jest.fn().mockResolvedValue({}),
  uploadMaterial: jest.fn().mockResolvedValue({}),
  materialAccept: '.xlsx,.pdf,.jpg',
}));

jest.mock('/@/store/modules/user', () => ({
  useUserStore: () => ({ getUserInfo: { id: 'u1' } }),
}));

jest.mock('/@/components/Markdown', () => ({
  MarkdownViewer: {
    name: 'MarkdownViewer',
    props: ['value'],
    template: '<div class="md-stub">{{ value }}</div>',
  },
}));

// ShMarkdown 依赖 ESM-only 的 markdown 渲染栈，jest 不转换 node_modules，这里按需打桩
jest.mock('lodash-es', () => ({ throttle: (fn: any) => fn }));
jest.mock('markdown-it', () => ({
  __esModule: true,
  default: class {
    constructor(opts: any) {
      this.opts = opts;
    }
    opts: any;
    use() {
      return this;
    }
    render(src: string) {
      return src;
    }
    renderInline(src: string) {
      return src;
    }
  },
}));
jest.mock('markdown-it-link-attributes', () => ({ __esModule: true, default: () => {} }));
jest.mock('/@/views/super/airag/aiapp/chat/style/github-markdown.less', () => ({}), { virtual: true });
jest.mock('/@/views/super/airag/aiapp/chat/style/highlight.less', () => ({}), { virtual: true });
jest.mock('highlight.js', () => ({
  __esModule: true,
  default: {
    getLanguage: () => false,
    highlight: () => ({ value: '' }),
    highlightAuto: () => ({ value: '' }),
  },
}));

jest.mock('ant-design-vue', () => ({
  Spin: { name: 'Spin', props: ['spinning', 'size'], template: '<span class="spin-stub" />' },
  Textarea: { name: 'Textarea', props: ['value'], template: '<textarea />' },
}));

jest.mock('@ant-design/icons-vue', () => ({
  CloseOutlined: { name: 'CloseOutlined', template: '<i class="icon-close" />' },
  PlusOutlined: { name: 'PlusOutlined', template: '<i class="icon-plus" />' },
  PaperClipOutlined: { name: 'PaperClipOutlined', template: '<i />' },
}));

import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

const AgentChat = require('/@/views/super/airag/shreport/components/AgentChat.vue').default;
const apiMock = require('/@/views/super/airag/shreport/ShReportAssistant.api');
const mockedCreateConversation = apiMock.createConversation as jest.Mock;
const mockedSendChatMessage = apiMock.sendChatMessage as jest.Mock;
const mockedListServerSessions = apiMock.listServerSessions as jest.Mock;
// 会话/模型状态已上移到顶部工具栏，这里通过共享上下文断言会话侧数据
const { useReportSession } = require('/@/views/super/airag/shreport/useReportSession');

// jsdom 20 不向全局注入 TextEncoder/TextDecoder（组件 SSE 解码与测试造流均依赖）
const nodeUtil = require('util');
(global as any).TextEncoder = (global as any).TextEncoder || nodeUtil.TextEncoder;
(global as any).TextDecoder = (global as any).TextDecoder || nodeUtil.TextDecoder;

// jsdom 未实现 Element.scrollTo，组件内 scrollToBottom 会调用
if (!(Element.prototype as any).scrollTo) {
  (Element.prototype as any).scrollTo = jest.fn();
}

const STORE_KEY = 'shreport_agent_sessions__u1';

/** 构造组件中 readStream 可消费的最小 SSE 伪流（ReadableStream 形状） */
function makeSseStream(events: string[]) {
  const encoder = new TextEncoder();
  const chunks = events.map((line) => encoder.encode(line));
  let i = 0;
  return {
    getReader() {
      return {
        read: async () => {
          if (i < chunks.length) {
            return { done: false, value: chunks[i++] };
          }
          return { done: true, value: undefined };
        },
      };
    },
  };
}

/** 等待一个宏任务 + Vue 渲染刷新（onMounted 内建会话、watch 持久化均为异步） */
async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 20));
  await nextTick();
}

async function mountAgentChat() {
  const wrapper = mount(AgentChat, {
    global: {
      // 模板中使用的全局 ant-design-vue 组件（项目 app.use(Antd) 注册，测试环境用桩替代）
      components: {
        'a-textarea': { name: 'ATextareaStub', template: '<textarea />' },
        'a-select': {
          name: 'ASelectStub',
          props: ['value', 'disabled'],
          template: '<select class="model-select-stub"><slot /></select>',
        },
        'a-select-option': { name: 'ASelectOptionStub', template: '<option><slot /></option>' },
        'a-button': {
          name: 'AButtonStub',
          props: ['disabled', 'loading'],
          template: '<button :disabled="disabled"><slot /></button>',
        },
      },
    },
  });
  await flush();
  return wrapper;
}

describe('山洪报告助手 - AgentChat 对话组件', () => {
  beforeEach(() => {
    localStorage.clear();
    mockedCreateConversation.mockReset().mockResolvedValue({ conversationId: 'c-1', batchNo: 'B-1' });
    mockedSendChatMessage.mockReset();
  });

  test('首次进入：无历史会话时自动创建会话并展示欢迎语', async () => {
    const wrapper = await mountAgentChat();

    expect(mockedCreateConversation).toHaveBeenCalledTimes(1);
    expect(wrapper.text()).toContain('山洪报告生成助手');
    const { sessions, conversationId, batchNo } = useReportSession();
    expect(sessions.value).toHaveLength(1);
    expect(sessions.value[0].title).toBe('新会话');
    expect(conversationId.value).toBe('c-1');
    expect(batchNo.value).toBe('B-1');

    wrapper.unmount();
  });

  test('发送需求：SSE 文本流式拼接渲染、refresh 事件通知章节面板刷新、首条消息更新会话标题', async () => {
    mockedSendChatMessage.mockResolvedValue(
      makeSseStream([
        'event:text\ndata:正在生成第一章\n\n',
        'event:text\ndata:第一章已完成\n\n',
        'event:refresh\ndata:B-1\n\n',
        'event:done\ndata:ok\n\n',
      ])
    );

    const wrapper = await mountAgentChat();
    const vm: any = wrapper.vm;

    vm.inputText = '帮我生成XX流域的山洪报告';
    await vm.onSend();
    await flush();

    // 携带会话与批次号发送
    expect(mockedSendChatMessage).toHaveBeenCalledWith('c-1', '帮我生成XX流域的山洪报告', 'B-1', expect.anything(), expect.anything());
    // 用户气泡回显
    expect(wrapper.find('.chat-message.user .chat-bubble').text()).toContain('帮我生成XX流域的山洪报告');
    // 欢迎语走欢迎区，助手回复为同一条 markdown 气泡（ShMarkdown 根节点 .sh-md）
    const mdBubbles = wrapper.findAll('.chat-message.assistant .sh-md');
    expect(mdBubbles.length).toBeGreaterThanOrEqual(1);
    expect(mdBubbles.at(-1)?.text()).toContain('正在生成第一章');
    expect(mdBubbles.at(-1)?.text()).toContain('第一章已完成');
    // refresh 事件 + 流结束兜底都会通知父组件刷新右侧章节面板
    const refreshes = wrapper.emitted('refresh') || [];
    expect(refreshes.length).toBeGreaterThanOrEqual(1);
    expect(refreshes.every((args) => args[0] === 'B-1')).toBe(true);
    // “新会话”以首条消息作为标题
    const { sessions } = useReportSession();
    expect(sessions.value[0].title).toContain('帮我生成XX流域的山洪报告');
    // 结束后 loading 复位
    expect(vm.loading).toBe(false);

    wrapper.unmount();
  });

  test('发送失败：错误落到助手气泡并包含错误信息，loading 正常复位', async () => {
    mockedSendChatMessage.mockRejectedValueOnce(new Error('网络中断'));

    const wrapper = await mountAgentChat();
    const vm: any = wrapper.vm;

    vm.inputText = '查看当前进度';
    await vm.onSend();
    await flush();

    const assistantBubbles = wrapper.findAll('.chat-message.assistant .chat-bubble');
    expect(assistantBubbles.at(-1)?.text()).toContain('请求失败');
    expect(assistantBubbles.at(-1)?.text()).toContain('网络中断');
    expect(vm.loading).toBe(false);

    wrapper.unmount();
  });

  test('再次进入：从 localStorage 恢复最近会话与消息，不再请求创建接口', async () => {
    const ts = Date.now() - 24 * 3600 * 1000;
    // 真实场景里历史会话同时存在于服务端：让远端也返回它，
    // 否则进入页面时会以服务端为准裁剪掉本机残留（这正是「库里已删、界面仍在」的修复点）
    mockedListServerSessions.mockResolvedValueOnce([
      {
        conversationId: 'c-old',
        batchNo: 'B-old',
        title: '历史流域报告',
        messages: [
          { role: 'user', content: '旧需求' },
          { role: 'assistant', content: '旧回复内容' },
        ],
        updateTime: ts,
      },
    ]);
    localStorage.setItem(
      STORE_KEY,
      JSON.stringify({
        sessions: [
          {
            conversationId: 'c-old',
            batchNo: 'B-old',
            title: '历史流域报告',
            messages: [
              { role: 'user', content: '旧需求' },
              { role: 'assistant', content: '旧回复内容' },
            ],
            updateTime: ts,
          },
        ],
      })
    );

    const wrapper = await mountAgentChat();

    expect(mockedCreateConversation).not.toHaveBeenCalled();
    const { conversationId, batchNo } = useReportSession();
    expect(conversationId.value).toBe('c-old');
    expect(batchNo.value).toBe('B-old');
    expect(wrapper.text()).toContain('旧回复内容');

    wrapper.unmount();
  });

  test('顶部化后对话面板只保留消息与输入区：不再内置会话栏与模型下拉', async () => {
    const wrapper = await mountAgentChat();
    // 会话与模型已上移到顶部工具栏，对话面板内不再渲染
    expect(wrapper.find('.session-side').exists()).toBe(false);
    expect(wrapper.find('.agent-chat').exists()).toBe(true);
    expect(wrapper.find('.composer').exists()).toBe(true);

    wrapper.unmount();
  });

  test('多候选 choose 事件：渲染流域点选按钮，点击后发送规则可识别的确认文本', async () => {
    mockedSendChatMessage.mockResolvedValue(
      makeSseStream([
        'event:text\ndata:请选择流域\n\n',
        'event:choose\ndata:[{"lybm":"W1","lymc":"甲流域"},{"lybm":"W2","lymc":"乙流域"}]\n\n',
        'event:done\ndata:ok\n\n',
      ])
    );

    const wrapper = await mountAgentChat();
    const vm: any = wrapper.vm;

    vm.inputText = '生成甲流域报告';
    await vm.onSend();
    await flush();

    const chooseButtons = wrapper.findAll('.choose-btn');
    expect(chooseButtons).toHaveLength(2);
    expect(chooseButtons[0].text()).toContain('甲流域');

    // 点击第一个候选：应再次发起对话，内容为「确认使用流域：名称|编码」
    mockedSendChatMessage.mockClear();
    await chooseButtons[0].trigger('click');
    await flush();
    expect(mockedSendChatMessage).toHaveBeenCalledWith('c-1', '确认使用流域：甲流域|W1', 'B-1', expect.anything(), expect.anything());

    wrapper.unmount();
  });

  test('delta 事件：token 接到同一条气泡，中间不插入空行', async () => {
    mockedSendChatMessage.mockResolvedValue(
      makeSseStream([
        'event:text\ndata:收到，我先分析一下你的需求…\n\n',
        'event:delta\ndata:山洪\n\n',
        'event:delta\ndata:报告助手\n\n',
        'event:done\ndata:ok\n\n',
      ])
    );

    const wrapper = await mountAgentChat();
    const vm: any = wrapper.vm;

    vm.inputText = '山洪报告助手能做什么';
    await vm.onSend();
    await flush();

    const mdBubbles = wrapper.findAll('.chat-message.assistant .sh-md');
    const latest = mdBubbles.at(-1)?.text() || '';
    expect(latest).toContain('收到，我先分析一下你的需求…');
    expect(latest).toContain('山洪报告助手');
    expect(latest).not.toContain('山洪\n\n报告助手');
    expect(vm.loading).toBe(false);

    wrapper.unmount();
  });
});
