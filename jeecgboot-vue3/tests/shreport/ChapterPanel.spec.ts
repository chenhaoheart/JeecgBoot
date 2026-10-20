/**
 * 章节面板：对话已生成完毕但列表为空时，可刷新 / 按批次单独查询内容。
 */
jest.mock('/@/views/super/airag/shreport/ShReportAssistant.api', () => ({
  createConversation: jest.fn(),
  bindModel: jest.fn().mockResolvedValue({}),
  bindWatershed: jest.fn().mockResolvedValue({}),
  getAgentModels: jest.fn().mockResolvedValue([]),
  getContext: jest.fn().mockResolvedValue({ batchNo: 'B-1', lybm: 'W1', lymc: '甲流域' }),
  getWatersheds: jest.fn().mockResolvedValue([]),
  getQcIssues: jest.fn().mockResolvedValue([]),
  getChapterList: jest.fn().mockResolvedValue([]),
  downloadReport: jest.fn(),
  auditChapter: jest.fn(),
  regenerateChapter: jest.fn(),
  saveChapter: jest.fn(),
  listMaterials: jest.fn().mockResolvedValue([]),
  uploadMaterial: jest.fn(),
  updateMaterial: jest.fn(),
  deleteMaterial: jest.fn(),
  reparseMaterial: jest.fn(),
  previewMaterialBlob: jest.fn(),
  materialAccept: '.xlsx,.pdf,.jpg',
}));

jest.mock('/@/store/modules/user', () => ({
  useUserStore: () => ({ getUserInfo: { id: 'u1' } }),
}));

jest.mock('/@/components/Markdown', () => ({
  MarkdownViewer: { name: 'MarkdownViewer', props: ['value'], template: '<div class="md-stub">{{ value }}</div>' },
}));

jest.mock('ant-design-vue', () => ({
  message: { warning: jest.fn(), error: jest.fn(), success: jest.fn(), info: jest.fn() },
  Modal: { name: 'AModal', template: '<div class="modal-stub"><slot /></div>' },
  Textarea: { name: 'Textarea', template: '<textarea />' },
}));

jest.mock('@ant-design/icons-vue', () => ({
  BulbOutlined: { name: 'BulbOutlined', template: '<i />' },
  CaretDownOutlined: { name: 'CaretDownOutlined', template: '<i />' },
  CheckCircleFilled: { name: 'CheckCircleFilled', template: '<i />' },
  CheckOutlined: { name: 'CheckOutlined', template: '<i />' },
  CloseOutlined: { name: 'CloseOutlined', template: '<i />' },
  DiffOutlined: { name: 'DiffOutlined', template: '<i />' },
  DownloadOutlined: { name: 'DownloadOutlined', template: '<i />' },
  EditOutlined: { name: 'EditOutlined', template: '<i />' },
  ExclamationCircleFilled: { name: 'ExclamationCircleFilled', template: '<i />' },
  FileTextOutlined: { name: 'FileTextOutlined', template: '<i />' },
  LoadingOutlined: { name: 'LoadingOutlined', template: '<i />' },
  RedoOutlined: { name: 'RedoOutlined', template: '<i />' },
  ReloadOutlined: { name: 'ReloadOutlined', template: '<i />' },
  WarningFilled: { name: 'WarningFilled', template: '<i />' },
}));

import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

const ChapterPanel = require('/@/views/super/airag/shreport/components/ChapterPanel.vue').default;
const apiMock = require('/@/views/super/airag/shreport/ShReportAssistant.api');
const { useReportSession } = require('/@/views/super/airag/shreport/useReportSession');
const { message } = require('ant-design-vue');

const STORE_KEY = 'shreport_agent_sessions__u1';

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 20));
  await nextTick();
}

function sampleChapters() {
  return [
    {
      id: 'c1',
      batchNo: 'B-1',
      lybm: 'W1',
      lymc: '甲流域',
      chapterNo: 7,
      chapterName: '七、成果与建议',
      content: '建议正文',
      status: '待审核',
      auditNote: '',
      updateTime: '',
    },
  ];
}

async function mountPanel() {
  const wrapper = mount(ChapterPanel, {
    global: {
      components: {
        'a-button': {
          name: 'AButtonStub',
          props: ['disabled', 'loading', 'type', 'size'],
          template: '<button class="btn-stub" :disabled="disabled"><slot name="icon" /><slot /></button>',
        },
        'a-input': {
          name: 'AInputStub',
          props: ['value', 'placeholder'],
          inheritAttrs: false,
          emits: ['update:value'],
          template: '<input class="input-stub" :value="value" :placeholder="placeholder" @input="$emit(\'update:value\', $event.target.value)" />',
        },
        'a-tooltip': { name: 'ATooltipStub', template: '<span><slot /></span>' },
        'a-modal': { name: 'AModalStub', template: '<div><slot /></div>' },
        'a-textarea': { name: 'ATextareaStub', template: '<textarea />' },
      },
    },
  });
  await flush();
  return wrapper;
}

describe('山洪报告助手 - 章节面板刷新与查询', () => {
  beforeEach(async () => {
    localStorage.clear();
    localStorage.setItem(
      STORE_KEY,
      JSON.stringify({
        sessions: [{ conversationId: 'c-1', batchNo: 'B-1', title: '博拉河', messages: [], updateTime: Date.now() }],
      })
    );
    apiMock.getChapterList.mockReset().mockResolvedValue([]);
    apiMock.getContext.mockResolvedValue({ batchNo: 'B-1', lybm: 'W1', lymc: '甲流域' });
    await useReportSession().init();
    useReportSession().chapters.value = [];
  });

  test('空态展示刷新按钮和单独查询区', async () => {
    const wrapper = await mountPanel();
    expect(wrapper.text()).toContain('暂无章节');
    expect(wrapper.text()).toContain('刷新章节');
    expect(wrapper.text()).toContain('单独查询已生成内容');
    wrapper.unmount();
  });

  test('点击刷新章节：对齐上下文后拉列表并展示内容', async () => {
    const wrapper = await mountPanel();
    expect(wrapper.text()).toContain('暂无章节');
    apiMock.getChapterList.mockResolvedValue(sampleChapters());
    const refreshBtn = wrapper.findAll('.btn-stub').find((b) => b.text().includes('刷新章节'));
    expect(refreshBtn).toBeTruthy();
    await refreshBtn!.trigger('click');
    await flush();
    expect(apiMock.getChapterList).toHaveBeenCalled();
    expect(wrapper.text()).toContain('七、成果与建议');
    expect(message.success).toHaveBeenCalled();
    wrapper.unmount();
  });

  test('空列表单独查询批次：查到章节后渲染卡片', async () => {
    const wrapper = await mountPanel();
    expect(wrapper.text()).toContain('单独查询已生成内容');
    apiMock.getChapterList.mockResolvedValue(sampleChapters());
    const queryBtn = wrapper.findAll('.btn-stub').find((b) => b.text().includes('查询'));
    expect(queryBtn).toBeTruthy();
    await queryBtn!.trigger('click');
    await flush();
    expect(wrapper.text()).toContain('七、成果与建议');
    wrapper.unmount();
  });
});
