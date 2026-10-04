<template>
  <div class="layer-ctrl" :class="{ collapsed }">
    <div class="lc-head">
      <span>图层</span>
      <button type="button" class="lc-toggle" :title="collapsed ? '展开图层' : '收起图层'" @click="collapsed = !collapsed">
        <UpOutlined v-if="!collapsed" />
        <DownOutlined v-else />
      </button>
    </div>
    <div v-show="!collapsed" class="lc-body" :style="{ height: bodyHeight + 'px' }">
      <label v-for="ly in layers" :key="ly.key" class="lc-row">
        <a-checkbox :checked="visible[ly.key]" @change="(e: any) => emit('toggle', ly.key, e.target.checked)" />
        <span class="dot" :style="{ background: ly.color }"></span>
        <span>{{ ly.label }}</span>
        <span class="n">{{ ly.count }}</span>
      </label>

      <div v-if="legendItems.length" class="lc-title sub">图例筛选</div>
      <label v-for="it in legendItems" :key="it.layer + it.value" class="lc-row legend">
        <a-checkbox :checked="isOn(it.layer, it.value)" @change="() => emit('toggleType', it.layer, it.value)" />
        <span class="dot" :style="{ background: it.color }"></span>
        <span>{{ it.value }}</span>
        <span class="n">{{ it.count }}</span>
      </label>
    </div>
    <div v-if="!collapsed" class="lc-resize" title="拖动调整高度" @mousedown="onResizeStart"></div>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onBeforeUnmount, ref } from 'vue';
  import { DownOutlined, UpOutlined } from '@ant-design/icons-vue';
  import { HAZARD_TYPE_COLORS, PREVENTION_TYPE_COLORS, SECTION_TYPE_COLORS, typeColor } from '../mapStyles';

  defineOptions({ name: 'ShOneMapLayerControl' });

  const props = defineProps<{
    visible: { watershed: boolean; section: boolean; prevention: boolean; hazard: boolean };
    sectionTypes: Record<string, number>;
    preventionTypes: Record<string, number>;
    hazardTypes: Record<string, number>;
    categoryFilters: Record<string, string[]>;
    watershedCount: number;
    sectionCount: number;
    preventionCount: number;
    hazardCount: number;
  }>();

  const emit = defineEmits<{
    (e: 'toggle', key: 'watershed' | 'section' | 'prevention' | 'hazard', on: boolean): void;
    (e: 'toggleType', layer: 'section' | 'prevention' | 'hazard', value: string): void;
  }>();

  const collapsed = ref(false);
  const bodyHeight = ref(220);

  const layers = computed(() => [
    { key: 'watershed' as const, label: '小流域', color: '#2563eb', count: props.watershedCount },
    { key: 'section' as const, label: '断面平面位置', color: '#e11d48', count: props.sectionCount },
    { key: 'prevention' as const, label: '防治对象', color: '#10b981', count: props.preventionCount },
    { key: 'hazard' as const, label: '隐患要素', color: '#ef4444', count: props.hazardCount },
  ]);

  const legendItems = computed(() => {
    const items: { layer: 'section' | 'prevention' | 'hazard'; value: string; color: string; count: number }[] = [];
    if (props.visible.section) {
      for (const [value, count] of Object.entries(props.sectionTypes || {})) {
        items.push({ layer: 'section', value, color: typeColor('section', value, SECTION_TYPE_COLORS['横断面']), count });
      }
    }
    if (props.visible.prevention) {
      for (const [value, count] of Object.entries(props.preventionTypes || {})) {
        items.push({ layer: 'prevention', value, color: typeColor('prevention', value, PREVENTION_TYPE_COLORS['其他']), count });
      }
    }
    if (props.visible.hazard) {
      for (const [value, count] of Object.entries(props.hazardTypes || {})) {
        items.push({ layer: 'hazard', value, color: typeColor('hazard', value, HAZARD_TYPE_COLORS['跨沟道路']), count });
      }
    }
    return items;
  });

  function isOn(layer: string, value: string) {
    const cur = props.categoryFilters[layer];
    return !cur || cur.includes(value);
  }

  let dragging = false;
  let startY = 0;
  let startH = 0;

  function onResizeStart(e: MouseEvent) {
    e.preventDefault();
    dragging = true;
    startY = e.clientY;
    startH = bodyHeight.value;
    window.addEventListener('mousemove', onResizeMove);
    window.addEventListener('mouseup', onResizeEnd);
  }

  function onResizeMove(e: MouseEvent) {
    if (!dragging) {
      return;
    }
    bodyHeight.value = Math.min(480, Math.max(96, startH + e.clientY - startY));
  }

  function onResizeEnd() {
    dragging = false;
    window.removeEventListener('mousemove', onResizeMove);
    window.removeEventListener('mouseup', onResizeEnd);
  }

  onBeforeUnmount(onResizeEnd);
</script>

<style lang="less" scoped>
  .layer-ctrl {
    display: flex;
    flex-direction: column;
    min-height: 0;
    font-size: 12px;
    color: #334155;

    &.collapsed {
      .lc-head {
        border-bottom: 0;
      }
    }

    .lc-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 12px;
      font-weight: 600;
      color: #0f172a;
      border-bottom: 1px solid #eef1f6;
    }

    .lc-toggle {
      border: 0;
      background: transparent;
      padding: 0 2px;
      color: #64748b;
      cursor: pointer;
      line-height: 1;
    }

    .lc-body {
      overflow: auto;
      padding: 8px 12px 4px;
    }

    .lc-title.sub {
      margin: 8px 0 6px;
      font-weight: 500;
      color: #64748b;
    }

    .lc-row {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 6px;
      cursor: pointer;

      .dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        flex: 0 0 auto;
      }

      .n {
        margin-left: auto;
        color: #94a3b8;
      }
    }

    .lc-resize {
      flex: 0 0 8px;
      cursor: ns-resize;
      background: linear-gradient(to bottom, transparent, rgba(148, 163, 184, 0.35));
      border-radius: 0 0 10px 10px;

      &:hover {
        background: linear-gradient(to bottom, transparent, rgba(37, 99, 235, 0.35));
      }
    }
  }
</style>
