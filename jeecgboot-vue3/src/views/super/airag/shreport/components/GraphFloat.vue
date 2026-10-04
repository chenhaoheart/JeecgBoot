<template>
  <Teleport v-if="isLeader" to="body">
    <div v-show="open" ref="rootEl" class="gf-wrap" :class="{ min: minimized }" :style="wrapStyle">
      <div class="gf-head" @mousedown="onDragStart">
        <i class="gf-led" :class="{ on: running }"></i>
        <span class="gf-title">{{ headText }}</span>
        <button type="button" class="gf-icon" @mousedown.stop @click="emit('minimize')">{{ minimized ? '展开' : '收起' }}</button>
        <button type="button" class="gf-icon" @mousedown.stop @click="emit('close')">关闭</button>
      </div>
      <div v-show="!minimized" class="gf-body">
        <GraphBoard v-if="open" compact :allow-demo="false" :batch-no="batchNo" :live-event="liveEvent" :chapters="chapters" />
      </div>
      <template v-if="open && !minimized">
        <div class="gf-rz gf-rz-n" @mousedown.stop.prevent="onResizeStart('n', $event)"></div>
        <div class="gf-rz gf-rz-s" @mousedown.stop.prevent="onResizeStart('s', $event)"></div>
        <div class="gf-rz gf-rz-w" @mousedown.stop.prevent="onResizeStart('w', $event)"></div>
        <div class="gf-rz gf-rz-e" @mousedown.stop.prevent="onResizeStart('e', $event)"></div>
        <div class="gf-rz gf-rz-nw" @mousedown.stop.prevent="onResizeStart('nw', $event)"></div>
        <div class="gf-rz gf-rz-ne" @mousedown.stop.prevent="onResizeStart('ne', $event)"></div>
        <div class="gf-rz gf-rz-sw" @mousedown.stop.prevent="onResizeStart('sw', $event)"></div>
        <div class="gf-rz gf-rz-se" @mousedown.stop.prevent="onResizeStart('se', $event)"></div>
      </template>
    </div>
  </Teleport>
</template>

<script lang="ts">
  /** 页面被缓存或重复挂载时，只允许最后一次挂载的实例把窗体挂到 body */
  let floatLeader = 0;
  let floatSeq = 0;
  const floatListeners = new Map<number, (leader: boolean) => void>();

  function claimFloatHost(onChange: (leader: boolean) => void) {
    const id = ++floatSeq;
    floatListeners.set(id, onChange);
    floatLeader = id;
    floatListeners.forEach((fn, key) => fn(key === floatLeader));
    return () => {
      floatListeners.delete(id);
      if (floatLeader === id) {
        const rest = [...floatListeners.keys()];
        floatLeader = rest.length ? rest[rest.length - 1] : 0;
      }
      floatListeners.forEach((fn, key) => fn(key === floatLeader));
    };
  }
</script>

<script lang="ts" setup>
  import { computed, nextTick, onActivated, onBeforeUnmount, onDeactivated, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
  import GraphBoard from './GraphBoard.vue';

  defineOptions({ name: 'ShReportGraphFloat' });

  const props = defineProps<{
    open: boolean;
    minimized: boolean;
    running?: boolean;
    batchNo?: string;
    liveEvent?: Record<string, any> | null;
    chapters?: { chapterNo: number; status: string }[];
  }>();

  const emit = defineEmits<{
    (e: 'close'): void;
    (e: 'minimize'): void;
  }>();

  const NODE_LABEL: Record<string, string> = {
    start: '开始',
    prepare: '锁住数字',
    write: '撰写章节',
    qc: '章节质检',
    rewrite: '打回重写',
    write_ch7: '写第7章',
    consistency: '核对数字',
    persist: '保存报告',
    end: '结束',
  };

  const isLeader = ref(false);
  const rootEl = ref<HTMLElement | null>(null);
  const pos = reactive({ x: 24, y: 80 });
  const size = reactive({ w: 880, h: 540 });
  let releaseHost: (() => void) | null = null;
  let dragging = false;
  let originX = 0;
  let originY = 0;
  type ResizeEdge = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw';
  let resizing: ResizeEdge | null = null;
  let resizeOrigin = { x: 0, y: 0, w: 0, h: 0, px: 0, py: 0 };
  let resizeCursor = '';

  const headText = computed(() => {
    const ev = props.liveEvent;
    const name = ev?.node ? NODE_LABEL[ev.node] || ev.node : '';
    const chs = Array.isArray(ev?.targetChapters) ? ev.targetChapters : [];
    const extra = chs.length ? ' · 第' + chs.join('、') + '章' : '';
    if (props.running && name) {
      return '当前：' + name + extra;
    }
    return name ? '生成流水线 · ' + name : '生成流水线';
  });

  const wrapStyle = computed(() => ({
    left: pos.x + 'px',
    top: pos.y + 'px',
    width: props.minimized ? 'auto' : size.w + 'px',
    height: props.minimized ? 'auto' : size.h + 'px',
  }));

  function bounds() {
    const maxW = Math.max(280, window.innerWidth - 16);
    const maxH = Math.max(180, window.innerHeight - 16);
    return {
      minW: Math.min(480, maxW),
      minH: Math.min(320, maxH),
      maxW,
      maxH,
    };
  }

  function clamp() {
    if (!props.minimized) {
      const box = bounds();
      size.w = Math.min(box.maxW, Math.max(box.minW, size.w));
      size.h = Math.min(box.maxH, Math.max(box.minH, size.h));
    }
    const width = props.minimized ? 280 : size.w;
    const height = props.minimized ? 40 : size.h;
    const maxX = Math.max(8, window.innerWidth - width - 8);
    const maxY = Math.max(8, window.innerHeight - height - 8);
    pos.x = Math.min(maxX, Math.max(8, pos.x));
    pos.y = Math.min(maxY, Math.max(8, pos.y));
  }

  function placeDefault() {
    pos.x = Math.max(8, window.innerWidth - size.w - 28);
    pos.y = Math.max(8, window.innerHeight - size.h - 28);
    clamp();
  }

  function takeHost() {
    releaseHost?.();
    releaseHost = claimFloatHost((leader) => {
      isLeader.value = leader;
    });
  }

  function dropHost() {
    releaseHost?.();
    releaseHost = null;
    isLeader.value = false;
    rootEl.value?.remove();
    rootEl.value = null;
  }

  /** 清掉上一次页面卸载后遗留在 body 上的窗体 */
  function sweepOrphans() {
    if (!isLeader.value) {
      return;
    }
    document.querySelectorAll('.gf-wrap').forEach((el) => {
      if (el !== rootEl.value) {
        el.remove();
      }
    });
  }

  function onDragStart(e: MouseEvent) {
    if (e.button !== 0) {
      return;
    }
    dragging = true;
    originX = e.clientX - pos.x;
    originY = e.clientY - pos.y;
    window.addEventListener('mousemove', onDragMove);
    window.addEventListener('mouseup', onDragEnd);
  }

  function onDragMove(e: MouseEvent) {
    if (!dragging) {
      return;
    }
    pos.x = e.clientX - originX;
    pos.y = e.clientY - originY;
    clamp();
  }

  function onDragEnd() {
    dragging = false;
    window.removeEventListener('mousemove', onDragMove);
    window.removeEventListener('mouseup', onDragEnd);
  }

  function onResizeStart(edge: ResizeEdge, e: MouseEvent) {
    if (e.button !== 0 || props.minimized) {
      return;
    }
    resizing = edge;
    resizeOrigin = { x: e.clientX, y: e.clientY, w: size.w, h: size.h, px: pos.x, py: pos.y };
    const cursor = getComputedStyle(e.currentTarget as HTMLElement).cursor;
    resizeCursor = document.body.style.cursor;
    document.body.style.cursor = cursor && cursor !== 'auto' ? cursor : 'nwse-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', onResizeMove);
    window.addEventListener('mouseup', onResizeEnd);
  }

  function onResizeMove(e: MouseEvent) {
    if (!resizing) {
      return;
    }
    const dx = e.clientX - resizeOrigin.x;
    const dy = e.clientY - resizeOrigin.y;
    const box = bounds();
    let w = resizeOrigin.w;
    let h = resizeOrigin.h;
    let x = resizeOrigin.px;
    let y = resizeOrigin.py;
    if (resizing.includes('e')) {
      w = Math.min(window.innerWidth - resizeOrigin.px - 8, Math.max(box.minW, resizeOrigin.w + dx));
    }
    if (resizing.includes('w')) {
      w = Math.min(resizeOrigin.px + resizeOrigin.w - 8, Math.max(box.minW, resizeOrigin.w - dx));
      x = resizeOrigin.px + resizeOrigin.w - w;
    }
    if (resizing.includes('s')) {
      h = Math.min(window.innerHeight - resizeOrigin.py - 8, Math.max(box.minH, resizeOrigin.h + dy));
    }
    if (resizing.includes('n')) {
      h = Math.min(resizeOrigin.py + resizeOrigin.h - 8, Math.max(box.minH, resizeOrigin.h - dy));
      y = resizeOrigin.py + resizeOrigin.h - h;
    }
    size.w = w;
    size.h = h;
    pos.x = x;
    pos.y = y;
  }

  function onResizeEnd() {
    resizing = null;
    document.body.style.cursor = resizeCursor;
    document.body.style.userSelect = '';
    window.removeEventListener('mousemove', onResizeMove);
    window.removeEventListener('mouseup', onResizeEnd);
    clamp();
  }

  watch(
    () => props.open,
    (open) => {
      if (open) {
        nextTick(sweepOrphans);
      }
    }
  );

  watch(
    () => props.minimized,
    (min, was) => {
      if (min && !was) {
        pos.x += size.w - 280;
        pos.y += size.h - 40;
      } else if (!min && was) {
        pos.x -= size.w - 280;
        pos.y -= size.h - 40;
      }
      clamp();
    }
  );

  onMounted(() => {
    takeHost();
    placeDefault();
    window.addEventListener('resize', clamp);
    nextTick(sweepOrphans);
  });

  onActivated(() => {
    takeHost();
    nextTick(sweepOrphans);
  });

  onDeactivated(() => {
    dropHost();
  });

  onBeforeUnmount(() => {
    rootEl.value?.remove();
  });

  onUnmounted(() => {
    dropHost();
    onDragEnd();
    onResizeEnd();
    window.removeEventListener('resize', clamp);
  });
</script>

<style lang="less" scoped>
  .gf-wrap {
    position: fixed;
    z-index: 900;
    display: flex;
    flex-direction: column;
    max-width: calc(100vw - 16px);
    background: #fff;
    border: 1px solid #d6e4ff;
    border-radius: 12px;
    box-sizing: border-box;
    box-shadow: 0 12px 40px rgba(16, 33, 61, 0.18);
    overflow: hidden;

    &.min {
      width: auto;
      min-width: 240px;
    }
  }

  .gf-head {
    position: relative;
    z-index: 3;
    display: flex;
    align-items: center;
    gap: 8px;
    height: 36px;
    padding: 0 8px 0 12px;
    background: #f0f5ff;
    cursor: move;
    user-select: none;
  }

  .gf-led {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #d0d7e2;
    flex: 0 0 auto;

    &.on {
      background: #1677ff;
      box-shadow: 0 0 0 3px rgba(22, 119, 255, 0.2);
      animation: gf-blink 0.7s ease-in-out infinite;
    }
  }

  @keyframes gf-blink {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.25;
    }
  }

  .gf-title {
    flex: 1;
    min-width: 0;
    font-size: 13px;
    font-weight: 600;
    color: #1f2a37;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .gf-icon {
    border: 0;
    background: transparent;
    color: #475569;
    font-size: 12px;
    line-height: 22px;
    padding: 0 6px;
    border-radius: 4px;
    cursor: pointer;

    &:hover {
      background: #fff;
      color: #1677ff;
    }
  }

  .gf-body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .gf-rz {
    position: absolute;
    z-index: 2;
  }

  .gf-rz-n,
  .gf-rz-s {
    left: 14px;
    height: 6px;
    cursor: ns-resize;
  }

  .gf-rz-n {
    top: 0;
    right: 148px;
    z-index: 4;
  }

  .gf-rz-s {
    right: 14px;
    bottom: 0;
  }

  .gf-rz-w,
  .gf-rz-e {
    top: 36px;
    bottom: 14px;
    width: 8px;
    cursor: ew-resize;
  }

  .gf-rz-w {
    left: 0;
  }

  .gf-rz-e {
    right: 0;
  }

  .gf-rz-nw,
  .gf-rz-ne,
  .gf-rz-sw,
  .gf-rz-se {
    width: 16px;
    height: 16px;
  }

  .gf-rz-nw {
    top: 0;
    left: 0;
    z-index: 4;
    cursor: nwse-resize;
  }

  .gf-rz-se {
    right: 0;
    bottom: 0;
    cursor: nwse-resize;
  }

  .gf-rz-ne {
    top: 0;
    right: 0;
    cursor: nesw-resize;
  }

  .gf-rz-sw {
    left: 0;
    bottom: 0;
    cursor: nesw-resize;
  }

  .gf-rz-nw::after,
  .gf-rz-se::after {
    content: '';
    position: absolute;
    width: 8px;
    height: 8px;
    border-color: #91caff;
    border-style: solid;
  }

  .gf-rz-nw::after {
    top: 4px;
    left: 4px;
    border-width: 2px 0 0 2px;
  }

  .gf-rz-se::after {
    right: 4px;
    bottom: 4px;
    border-width: 0 2px 2px 0;
  }
</style>
