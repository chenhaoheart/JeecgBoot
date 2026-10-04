/**
 * 山洪报告助手 - 左侧历史会话栏 SessionSide 测试
 *
 * 覆盖：
 * 1. 渲染全部会话并高亮当前会话
 * 2. 点击列表项切换到指定会话
 * 3. 删除会话后从列表移除
 */

jest.mock('/@/views/super/airag/shreport/ShReportAssistant.api', () => ({
  createConversation: jest.fn(),
  bindModel: jest.fn().mockResolvedValue({}),
  bindWatershed: jest.fn().mockResolvedValue({}),
  getAgentModels: jest.fn().mockResolvedValue([]),
  getContext: jest.fn().mockResolvedValue({}),
  getWatersheds: jest.fn().mockResolvedValue([]),
  getQcIssues: jest.fn().mockResolvedValue([]),
  getChapterList: jest.fn().mockResolvedValue([]),
  deleteServerSession: jest.fn().mockResolvedValue({}),
  cancelGenerate: jest.fn().mockResolvedValue({}),
}));

jest.mock('ant-design-vue', () => ({
  // 彻底删除走 Modal.confirm 强确认：测试里直接执行 onOk 模拟用户点「删除并清除数据」
  Modal: { confirm: jest.fn((opts: any) => opts.onOk()) },
  message: { success: jest.fn(), info: jest.fn(), warning: jest.fn(), error: jest.fn() },
}));

jest.mock('/@/store/modules/user', () => ({
  useUserStore: () => ({ getUserInfo: { id: 'u1' } }),
}));

jest.mock('@ant-design/icons-vue', () => ({
  DeleteOutlined: { name: 'DeleteOutlined', template: '<i class="icon-del" />' },
  FolderAddOutlined: { name: 'FolderAddOutlined', template: '<i class="icon-arc" />' },
  FolderOutlined: { name: 'FolderOutlined', template: '<i class="icon-folder" />' },
  PlusOutlined: { name: 'PlusOutlined', template: '<i class="icon-plus" />' },
  UndoOutlined: { name: 'UndoOutlined', template: '<i class="icon-undo" />' },
}));

import { mount } from '@vue/test-utils';

const SessionSide = require('/@/views/super/airag/shreport/components/SessionSide.vue').default;
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

async function mountSide() {
  const wrapper = mount(SessionSide, {
    global: {
      components: {
        'a-button': { name: 'AButtonStub', props: ['disabled', 'loading', 'type'], template: '<button :disabled="disabled"><slot /><slot name="icon" /></button>' },
      },
    },
  });
  await flush();
  return wrapper;
}

describe('山洪报告助手 - 左侧历史会话栏', () => {
  beforeEach(async () => {
    localStorage.clear();
    seedSessions();
    await useReportSession().init();
  });

  test('渲染全部会话并高亮当前会话', async () => {
    const wrapper = await mountSide();

    const items = wrapper.findAll('.session-item');
    expect(items).toHaveLength(2);
    // init 恢复最近更新的会话
    expect(wrapper.find('.session-item.active .s-title').text()).toContain('最新的流域报告');
    expect(wrapper.find('.side-foot').text()).toContain('2');

    wrapper.unmount();
  });

  test('点击列表项切换到指定会话', async () => {
    const wrapper = await mountSide();

    await wrapper.findAll('.session-item')[0].trigger('click');
    await flush();

    expect(useReportSession().conversationId.value).toBe('c-1');
    expect(wrapper.find('.session-item.active .s-title').text()).toContain('旧的流域报告');

    wrapper.unmount();
  });

  test('归档会话后在归档视图彻底删除', async () => {
    const wrapper = await mountSide();

    // 归档非当前会话（主列表只有归档入口，删除在归档视图）
    await wrapper.findAll('.session-item')[0].find('.icon-arc').trigger('click');
    await flush();
    expect(useReportSession().sessions.value.filter((s: any) => s.archived)).toHaveLength(1);

    // 切到已归档视图，点击删除并在 Modal 强确认中执行 onOk
    await wrapper.findAll('.side-tab')[1].trigger('click');
    await flush();
    await wrapper.findAll('.session-item')[0].find('.icon-del').trigger('click');
    await flush();

    expect(useReportSession().sessions.value.map((s: any) => s.conversationId)).toEqual(['c-2']);
    expect(useReportSession().conversationId.value).toBe('c-2');

    wrapper.unmount();
  });
});
