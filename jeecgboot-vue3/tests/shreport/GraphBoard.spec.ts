/**
 * 编排图 Demo 面板：拓扑渲染 + 节点状态。
 */
jest.mock('/@/views/super/airag/shreport/ShReportAssistant.api', () => ({
  getGraphSchema: jest.fn().mockResolvedValue({
    nodes: [
      { id: 'prepare', label: '锁住数字', desc: '先锁住本流域的关键数字', x: 20, y: 20 },
      { id: 'write', label: '撰写章节', desc: '让 AI 撰写章节正文', x: 180, y: 20 },
      { id: 'qc', label: '章节质检', desc: '检查这一章', x: 340, y: 20 },
    ],
    edges: [{ from: 'prepare', to: 'write', kind: 'always' }],
  }),
  getGraphTrace: jest.fn().mockResolvedValue({ status: 'idle', events: [] }),
  runGraphDemo: jest.fn(),
}));

jest.mock('ant-design-vue', () => ({
  message: { error: jest.fn(), warning: jest.fn(), success: jest.fn() },
}));

jest.mock('@ant-design/icons-vue', () => ({
  ApartmentOutlined: { name: 'ApartmentOutlined', template: '<i />' },
}));

import { mount } from '@vue/test-utils';

const GraphBoard = require('/@/views/super/airag/shreport/components/GraphBoard.vue').default;

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 30));
}

describe('GraphBoard', () => {
  it('renders graph nodes from schema', async () => {
    const wrapper = mount(GraphBoard, {
      props: { allowDemo: true },
      global: {
        components: {
          'a-button': { name: 'AButtonStub', props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' },
          'a-tag': { name: 'ATagStub', template: '<span><slot /></span>' },
        },
      },
    });
    await flush();
    expect(wrapper.text()).toContain('这是报告生成的流水线');
    expect(wrapper.text()).toContain('锁住数字');
    expect(wrapper.text()).toContain('撰写章节');
    expect(wrapper.text()).toContain('看完整流程');
    wrapper.unmount();
  });

  it('真实进度会点亮当前节点', async () => {
    const wrapper = mount(GraphBoard, {
      props: {
        allowDemo: false,
        batchNo: 'B1',
        chapters: [{ chapterNo: 3, status: '生成中' }],
      },
      global: {
        components: {
          'a-button': { name: 'AButtonStub', props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' },
          'a-tag': { name: 'ATagStub', template: '<span><slot /></span>' },
        },
      },
    });
    await flush();
    await wrapper.setProps({
      liveEvent: { node: 'prepare', phase: 'leave', batchNo: 'B1' },
    });
    await flush();
    await wrapper.setProps({
      liveEvent: { node: 'write', phase: 'enter', batchNo: 'B1', targetChapters: [3], retryCount: 0 },
    });
    await flush();
    expect(wrapper.text()).toContain('当前步骤：撰写章节');
    expect(wrapper.find('.st-running').exists()).toBe(true);
    expect(wrapper.find('.gb-led').exists()).toBe(true);
    expect(wrapper.find('.gb-flow').exists()).toBe(true);
    expect(wrapper.text()).toContain('3 外业');
    wrapper.unmount();
  });

  it('空闲时只拉一次轨迹', async () => {
    const api = require('/@/views/super/airag/shreport/ShReportAssistant.api');
    api.getGraphTrace.mockClear();
    const wrapper = mount(GraphBoard, {
      props: { allowDemo: false, batchNo: 'B1' },
      global: {
        components: {
          'a-button': { name: 'AButtonStub', props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' },
          'a-tag': { name: 'ATagStub', template: '<span><slot /></span>' },
        },
      },
    });
    await flush();
    expect(api.getGraphTrace).toHaveBeenCalledTimes(1);
    await new Promise((resolve) => setTimeout(resolve, 500));
    expect(api.getGraphTrace).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('已有实时事件时跑图中也不再轮询，结束快照直接落地', async () => {
    const api = require('/@/views/super/airag/shreport/ShReportAssistant.api');
    api.getGraphTrace.mockResolvedValue({ status: 'running', batchNo: 'B1', events: [{ node: 'write', phase: 'enter' }] });
    const wrapper = mount(GraphBoard, {
      props: {
        allowDemo: false,
        batchNo: 'B1',
        liveEvent: { node: 'write', phase: 'enter', batchNo: 'B1' },
      },
      global: {
        components: {
          'a-button': { name: 'AButtonStub', props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' },
          'a-tag': { name: 'ATagStub', template: '<span><slot /></span>' },
        },
      },
    });
    await flush();
    const calls = api.getGraphTrace.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 1700));
    expect(api.getGraphTrace.mock.calls.length).toBe(calls);
    await wrapper.setProps({
      liveEvent: {
        node: 'end',
        phase: 'leave',
        batchNo: 'B1',
        status: 'success',
        currentNode: 'end',
        events: [{ node: 'end', phase: 'leave' }],
      },
    });
    await flush();
    expect(wrapper.text()).toContain('这一轮走完了');
    wrapper.unmount();
    api.getGraphTrace.mockResolvedValue({ status: 'idle', events: [] });
  });
});
