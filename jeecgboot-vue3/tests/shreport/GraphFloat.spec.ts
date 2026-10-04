/**
 * 流水线浮窗：拖边缘改变窗口大小。
 */
jest.mock('/@/views/super/airag/shreport/ShReportAssistant.api', () => ({
  getGraphSchema: jest.fn().mockResolvedValue({ nodes: [], edges: [] }),
  getGraphTrace: jest.fn().mockResolvedValue({ status: 'idle', events: [] }),
  runGraphDemo: jest.fn(),
}));

jest.mock('ant-design-vue', () => ({
  message: { error: jest.fn(), warning: jest.fn(), success: jest.fn() },
}));

jest.mock('@ant-design/icons-vue', () => ({
  ApartmentOutlined: { name: 'ApartmentOutlined', template: '<i />' },
}));

import { nextTick } from 'vue';
import { mount } from '@vue/test-utils';

const GraphFloat = require('/@/views/super/airag/shreport/components/GraphFloat.vue').default;

function pointer(type: string, x: number, y: number) {
  const ev = new MouseEvent(type, { button: 0, bubbles: true, cancelable: true, clientX: x, clientY: y });
  if (ev.clientX !== x) {
    Object.defineProperty(ev, 'clientX', { value: x });
  }
  if (ev.clientY !== y) {
    Object.defineProperty(ev, 'clientY', { value: y });
  }
  return ev;
}

async function flush() {
  await new Promise((resolve) => setTimeout(resolve, 30));
}

describe('GraphFloat', () => {
  const realW = window.innerWidth;
  const realH = window.innerHeight;

  beforeEach(() => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1400 });
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 900 });
  });

  afterEach(() => {
    document.querySelectorAll('.gf-wrap').forEach((el) => el.remove());
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: realW });
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: realH });
  });

  it('拖左上角放大，右下角保持不动', async () => {
    const wrapper = mount(GraphFloat, {
      attachTo: document.body,
      props: { open: true, minimized: false, batchNo: 'B1' },
    });
    await flush();
    const wrap = document.querySelector('.gf-wrap') as HTMLElement;
    expect(wrap).toBeTruthy();
    expect(wrap.style.width).toBe('880px');
    expect(wrap.style.height).toBe('540px');
    const right = parseFloat(wrap.style.left) + parseFloat(wrap.style.width);
    const bottom = parseFloat(wrap.style.top) + parseFloat(wrap.style.height);

    const handle = wrap.querySelector('.gf-rz-nw') as HTMLElement;
    handle.dispatchEvent(pointer('mousedown', 100, 100));
    window.dispatchEvent(pointer('mousemove', 20, 40));
    window.dispatchEvent(pointer('mouseup', 20, 40));
    await nextTick();

    expect(wrap.style.width).toBe('960px');
    expect(wrap.style.height).toBe('600px');
    expect(parseFloat(wrap.style.left) + parseFloat(wrap.style.width)).toBe(right);
    expect(parseFloat(wrap.style.top) + parseFloat(wrap.style.height)).toBe(bottom);
    wrapper.unmount();
  });

  it('不会缩到最小尺寸以下', async () => {
    const wrapper = mount(GraphFloat, {
      attachTo: document.body,
      props: { open: true, minimized: false },
    });
    await flush();
    const wrap = document.querySelector('.gf-wrap') as HTMLElement;
    const handle = wrap.querySelector('.gf-rz-se') as HTMLElement;
    handle.dispatchEvent(pointer('mousedown', 400, 400));
    window.dispatchEvent(pointer('mousemove', 0, 0));
    window.dispatchEvent(pointer('mouseup', 0, 0));
    await nextTick();
    expect(wrap.style.width).toBe('480px');
    expect(wrap.style.height).toBe('320px');
    wrapper.unmount();
  });
});
