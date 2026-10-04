/**
 * 一张图筛选：点击流域平铺项会向外抛出 wscd。
 */
import { mount } from '@vue/test-utils';

jest.mock('ant-design-vue', () => ({
  message: { warning: jest.fn(), error: jest.fn() },
}));

const FilterBar = require('/@/views/super/airag/shreport/onemap/components/FilterBar.vue').default;

const stubs = {
  'a-input': { name: 'AInputStub', props: ['value'], template: '<input class="kw" />' },
  'a-tree': { name: 'ATreeStub', template: '<div class="tree-stub" />' },
  'a-button': { name: 'AButtonStub', template: '<button class="reset" @click="$emit(\'click\')"><slot /></button>' },
};

describe('山洪一张图 - FilterBar', () => {
  test('选择流域时发出 update:wscd', async () => {
    const wrapper = mount(FilterBar, {
      props: {
        watersheds: [
          { wscd: 'W1', wsnm: '甲流域', objectCount: 3 },
          { wscd: 'W2', wsnm: '乙流域', objectCount: 1 },
        ],
        counties: [],
        towns: [],
        wscd: '',
      },
      global: { components: stubs },
    });

    await wrapper.find('.ws-tile').trigger('click');
    expect(wrapper.emitted('update:wscd')?.[0]).toEqual(['W1']);
    wrapper.unmount();
  });

  test('重置发出 reset', async () => {
    const wrapper = mount(FilterBar, {
      props: { watersheds: [], counties: [], towns: [] },
      global: { components: stubs },
    });
    await wrapper.find('.reset').trigger('click');
    expect(wrapper.emitted('reset')).toBeTruthy();
    wrapper.unmount();
  });
});
