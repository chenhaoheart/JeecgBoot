/**
 * 山洪报告助手 - 顶部工具栏 ReportToolbar 测试
 *
 * 覆盖改造后的顶部操作区（会话列表已移至左侧栏 SessionSide）：
 * 1. 模型切换下拉渲染已激活模型，切换后绑定会话
 * 2. 导出按钮：有正文的章节即可导出，不必全部审核通过
 */

jest.mock('/@/views/super/airag/shreport/ShReportAssistant.api', () => ({
  createConversation: jest.fn(),
  bindModel: jest.fn().mockResolvedValue({}),
  bindWatershed: jest.fn().mockResolvedValue({}),
  getAgentModels: jest.fn().mockResolvedValue([
    { id: 'M1', name: '智谱GLM-4.7', provider: 'zhipu', modelName: 'glm-4.7-flash' },
    { id: 'M2', name: 'DeepSeek', provider: 'deepseek', modelName: 'deepseek-chat' },
  ]),
  getContext: jest.fn().mockResolvedValue({}),
  getWatersheds: jest.fn().mockResolvedValue([]),
  getQcIssues: jest.fn().mockResolvedValue([]),
  getChapterList: jest.fn().mockResolvedValue([]),
  downloadReport: jest.fn(),
}));

jest.mock('/@/store/modules/user', () => ({
  useUserStore: () => ({ getUserInfo: { id: 'u1' } }),
}));

jest.mock('ant-design-vue', () => ({
  message: { warning: jest.fn(), error: jest.fn(), success: jest.fn(), info: jest.fn() },
  Segmented: {
    name: 'Segmented',
    props: ['options', 'value'],
    template: '<div class="seg-stub"><span v-for="o in options" :key="o.value">{{ o.label }}</span></div>',
  },
}));

jest.mock('@ant-design/icons-vue', () => ({
  HistoryOutlined: { name: 'HistoryOutlined', template: '<i class="icon-history" />' },
  ExportOutlined: { name: 'ExportOutlined', template: '<i class="icon-export" />' },
  DownloadOutlined: { name: 'DownloadOutlined', template: '<i class="icon-download" />' },
  FileDoneOutlined: { name: 'FileDoneOutlined', template: '<i class="icon-file" />' },
  PlusOutlined: { name: 'PlusOutlined', template: '<i class="icon-plus" />' },
  CloseOutlined: { name: 'CloseOutlined', template: '<i class="icon-close" />' },
}));

import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

const ReportToolbar = require('/@/views/super/airag/shreport/components/ReportToolbar.vue').default;
const { useReportSession } = require('/@/views/super/airag/shreport/useReportSession');

const STORE_KEY = 'shreport_agent_sessions__u1';

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 20));
}

function seedSessions() {
  localStorage.setItem(
    STORE_KEY,
    JSON.stringify({
      sessions: [
        { conversationId: 'c-1', batchNo: 'B-1', title: '旧的流域报告', messages: [], updateTime: Date.now() - 3600 * 1000 },
        { conversationId: 'c-2', batchNo: 'B-2', title: '最新的流域报告', messages: [], updateTime: Date.now() },
      ],
    })
  );
}

async function mountToolbar() {
  const wrapper = mount(ReportToolbar, {
    props: { chatVisible: true, chapterVisible: false },
    global: {
      components: {
        'a-button': { name: 'AButtonStub', props: ['disabled', 'loading', 'type'], template: '<button :disabled="disabled"><slot /><slot name="icon" /></button>' },
        'a-select': { name: 'ASelectStub', props: ['value', 'disabled'], template: '<select class="tb-select-stub"><slot /></select>' },
        'a-select-option': { name: 'ASelectOptionStub', template: '<option><slot /></option>' },
        'a-input': { name: 'AInputStub', props: ['value'], inheritAttrs: false, template: '<input :value="value" />' },
        'a-dropdown': { name: 'ADropdownStub', props: ['open'], template: '<div class="dropdown-stub"><slot /><slot name="overlay" /></div>' },
        'a-tooltip': { name: 'ATooltipStub', template: '<span><slot /></span>' },
      },
    },
  });
  await flush();
  return wrapper;
}

describe('山洪报告助手 - 顶部工具栏', () => {
  beforeEach(async () => {
    localStorage.clear();
    seedSessions();
    await useReportSession().init();
  });

  test('视图切换包含三栏、对话、章节、流水线', async () => {
    const wrapper = await mountToolbar();
    expect(wrapper.text()).toContain('三栏');
    expect(wrapper.text()).toContain('对话');
    expect(wrapper.text()).toContain('章节');
    expect(wrapper.text()).toContain('流水线');
    expect(wrapper.text()).not.toContain('地图');
    wrapper.unmount();
  });

  test('导出按钮：有可导出章节即可点击，不必全部审核通过', async () => {
    const { chapters, boundWatershed } = useReportSession();
    boundWatershed.value = { lybm: 'W1', lymc: '甲流域' };

    const wrapper = await mountToolbar();
    await flush();

    chapters.value = Array.from({ length: 7 }, (_, i) => ({
      id: `c${i}`,
      batchNo: 'B-2',
      lybm: 'W1',
      chapterNo: i + 1,
      chapterName: `第${i + 1}章`,
      content: '',
      status: '待审核',
      auditNote: '',
      updateTime: '',
    }));
    await nextTick();

    const exportBtn = wrapper.findAllComponents({ name: 'AButtonStub' }).at(-1)!;
    expect(exportBtn.props('disabled')).toBe(true);

    chapters.value[0].content = '第一章正文';
    chapters.value[0].status = '待审核';
    await nextTick();
    expect(exportBtn.props('disabled')).toBe(false);

    wrapper.unmount();
  });
});
