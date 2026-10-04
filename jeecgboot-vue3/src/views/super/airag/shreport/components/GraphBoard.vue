<template>
  <div class="graph-board" :class="{ compact }">
    <div v-if="allowDemo" class="gb-intro">
      <div class="intro-title">这是报告生成的流水线，不是聊天，也不是报告正文</div>
      <div class="intro-body">
        对话里点「生成报告」后，后台会按下面这些方块一步步走：先锁住本流域的数字 → AI 写第 1～6 章 → 质检不合格就打回重写 → 再写第 7 章建议 → 核对各章数字有没有打架 → 存到右侧章节面板。
        <b>灰色还没走到，蓝色正在做，绿色已经做完。</b>
      </div>
      <div class="intro-demo">
        <span class="demo-label">先空跑一遍看看（不消耗大模型、不写真实报告）：</span>
        <a-button size="small" type="primary" :loading="demoing && scenario === 'pass'" :disabled="demoing" @click="runDemo('pass')">
          看完整流程
        </a-button>
        <a-button size="small" :loading="demoing && scenario === 'rewrite'" :disabled="demoing" @click="runDemo('rewrite')">
          看质检打回
        </a-button>
        <a-button size="small" :loading="demoing && scenario === 'chapter'" :disabled="demoing" @click="runDemo('chapter')">
          看只改一章
        </a-button>
      </div>
    </div>
    <div v-else-if="!compact" class="gb-livebar">蓝灯闪烁的是当前步骤，沿线上的光点是正在走过的数据，绿色是已经走完的。</div>

    <div class="gb-now" :class="'now-' + (trace.status || 'idle')">
      <a-tag :color="statusColor">{{ statusText }}</a-tag>
      <span class="now-text">{{ currentStepText }}</span>
      <span v-if="chapterHint" class="now-extra">{{ chapterHint }}</span>
    </div>

    <div v-if="!allowDemo" class="gb-chapters">
      <span v-for="ch in chapterLamps" :key="ch.no" class="ch-lamp" :class="ch.state">{{ ch.no }} {{ ch.name }}</span>
    </div>

    <div v-if="schemaError" class="gb-schema-error">{{ schemaError }}</div>

    <div v-else class="gb-canvas">
      <svg :viewBox="viewBox" :preserveAspectRatio="compact ? 'xMidYMid meet' : 'xMinYMin meet'" class="gb-svg">
        <defs>
          <marker id="gb-arrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto" markerUnits="userSpaceOnUse">
            <path d="M0,0 L9,4.5 L0,9 z" fill="#64748b" />
          </marker>
          <marker id="gb-arrow-active" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto" markerUnits="userSpaceOnUse">
            <path d="M0,0 L9,4.5 L0,9 z" fill="#1677ff" />
          </marker>
          <marker id="gb-arrow-done" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto" markerUnits="userSpaceOnUse">
            <path d="M0,0 L9,4.5 L0,9 z" fill="#52c41a" />
          </marker>
        </defs>
        <g v-for="e in schema.edges" :key="e.from + '-' + e.to" class="gb-edge">
          <polyline :points="edgePoints(e)" fill="none" stroke="#fff" stroke-width="7" stroke-linejoin="round" stroke-linecap="round" />
          <polyline
            :points="edgePoints(e)"
            fill="none"
            :stroke="edgeStroke(e)"
            :stroke-width="edgeFlow(e) ? 2.8 : edgeActive(e) ? 2.2 : 1.8"
            :stroke-dasharray="edgeFlow(e) ? '10 8' : e.kind === 'conditional' ? '7 5' : undefined"
            stroke-linejoin="round"
            stroke-linecap="round"
            :class="{ 'gb-flow': edgeFlow(e) }"
            :marker-end="edgeFlow(e) ? 'url(#gb-arrow-active)' : edgeActive(e) ? 'url(#gb-arrow-done)' : 'url(#gb-arrow)'"
          />
          <g v-if="e.label">
            <text :x="edgeLabelPos(e).x" :y="edgeLabelPos(e).y" class="gb-edge-label-halo">{{ e.label }}</text>
            <text :x="edgeLabelPos(e).x" :y="edgeLabelPos(e).y" class="gb-edge-label">{{ e.label }}</text>
          </g>
        </g>
        <g
          v-for="n in schema.nodes"
          :key="n.id"
          :transform="`translate(${n.x}, ${n.y})`"
          class="gb-node-hit"
          @click="picked = n.id"
        >
          <title>{{ nodeExplain(n.id) }}</title>
          <rect :width="NODE_W" :height="NODE_H" rx="10" :class="['gb-node', 'st-' + nodeStatus(n.id), { picked: picked === n.id }]" />
          <circle v-if="nodeStatus(n.id) === 'running'" :cx="NODE_W - 14" :cy="14" r="5" class="gb-led" />
          <text :x="NODE_W / 2" :y="NODE_H / 2 + 5" text-anchor="middle" class="gb-node-label">{{ nodeLabel(n.id) }}</text>
        </g>
      </svg>
    </div>

    <div class="gb-legend">
      <span><i class="dot idle"></i>还没走到</span>
      <span><i class="dot running"></i>正在做</span>
      <span><i class="dot done"></i>做完了</span>
      <span><i class="dot failed"></i>失败</span>
      <span class="hint">虚线箭头 = 可能走、也可能不走（例如质检没过才打回）</span>
    </div>

    <div v-if="!compact" class="gb-explain">
      <template v-if="explainId">
        <div class="exp-name">{{ nodeLabel(explainId) }}</div>
        <div class="exp-desc">{{ nodeExplain(explainId) }}</div>
      </template>
      <div v-else class="exp-desc">点上面任意一个方块，看这一步具体做什么。</div>
    </div>

    <div v-if="!compact" class="gb-log">
      <div class="log-head">刚才走过的步骤</div>
      <div v-if="!timeline.length" class="log-empty">
        {{ allowDemo ? '还没开始。点上面「看完整流程」，方块会按顺序亮起来。' : '这一轮还没有步骤记录。开始生成后，这里会按实际走过的顺序往下记。' }}
      </div>
      <ol v-else class="log-list">
        <li v-for="(row, i) in timeline" :key="i">
          {{ row.label }}
          <span v-if="row.detail" class="log-extra">{{ row.detail }}</span>
        </li>
      </ol>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
  import { message } from 'ant-design-vue';
  import { getGraphSchema, getGraphTrace, runGraphDemo, IGraphEdge, IGraphNode, IGraphSchema, IGraphTrace } from '../ShReportAssistant.api';

  defineOptions({ name: 'ShReportGraphBoard' });

  const NODE_W = 148;
  const NODE_H = 54;
  const PORT_OUT = 26;

  /**
   * 与后端 ShReportGraphTopology 坐标一致。
   * 主链先横排再折到右列往下，底行向左收束；打回重写贴在「撰写章节」正下方，避免拉成一条超宽的线。
   */
  const NODE_LAYOUT: Record<string, { x: number; y: number }> = {
    start: { x: 28, y: 32 },
    prepare: { x: 300, y: 32 },
    write: { x: 572, y: 32 },
    qc: { x: 844, y: 32 },
    rewrite: { x: 572, y: 188 },
    write_ch7: { x: 844, y: 188 },
    consistency: { x: 844, y: 344 },
    persist: { x: 572, y: 344 },
    end: { x: 300, y: 344 },
  };

  function applyLayout(s: IGraphSchema): IGraphSchema {
    return {
      nodes: (s.nodes || []).map((n) => ({ ...n, ...(NODE_LAYOUT[n.id] || {}) })),
      edges: s.edges || [],
    };
  }

  const props = withDefaults(
    defineProps<{
      batchNo?: string;
      liveEvent?: Record<string, any> | null;
      allowDemo?: boolean;
      compact?: boolean;
      chapters?: { chapterNo: number; status: string }[];
    }>(),
    { allowDemo: true, compact: false, chapters: () => [] }
  );

  const schema = ref<IGraphSchema>({ nodes: [], edges: [] });
  const trace = ref<IGraphTrace>({ status: 'idle', events: [] });
  const demoing = ref(false);
  const scenario = ref('');
  const picked = ref('');
  const schemaError = ref('');
  /** 对话 SSE 已在推 graph 事件时，不再用 HTTP 轮询补轨迹 */
  let sseDriven = false;
  let followTimer: number | null = null;

  const viewBox = computed(() => {
    const nodes = schema.value.nodes || [];
    if (!nodes.length) {
      return '0 0 1100 510';
    }
    const maxX = Math.max(...nodes.map((n) => n.x + NODE_W)) + 72;
    const maxY = Math.max(...nodes.map((n) => n.y + NODE_H)) + 64;
    return `0 0 ${maxX} ${maxY}`;
  });

  const statusText = computed(() => {
    const s = trace.value.status || 'idle';
    return ({ idle: '空闲', running: '运行中', success: '成功', failed: '失败', cancelled: '已取消' } as Record<string, string>)[s] || s;
  });

  const statusColor = computed(() => {
    const s = trace.value.status || 'idle';
    return ({ idle: 'default', running: 'processing', success: 'success', failed: 'error', cancelled: 'warning' } as Record<string, string>)[s] || 'default';
  });

  const explainId = computed(() => picked.value || (trace.value.status === 'idle' ? '' : trace.value.currentNode || ''));

  const currentStepText = computed(() => {
    const s = trace.value.status || 'idle';
    if (s === 'idle') {
      return props.allowDemo
        ? '还没开始。点「看完整流程」空跑一遍，或先在对话里生成报告。'
        : '还没开始生成。对话里一开始，当前步骤会在图上闪。';
    }
    if (s === 'success') {
      return '这一轮走完了。绿色方块就是实际经过的步骤。';
    }
    if (s === 'failed') {
      return '这一轮失败了。红灯停在卡住的那一步。';
    }
    if (s === 'cancelled') {
      return '用户点了停止，流水线在当前步中断。';
    }
    const cur = trace.value.currentNode || '';
    return cur ? '当前步骤：' + nodeLabel(cur) : '正在按流水线往下走…';
  });

  const CHAPTER_NAMES = ['概况', '准备', '外业', '影响', '灾害', '防治', '建议'];

  const chapterLamps = computed(() => {
    const rows = props.chapters || [];
    const targets = new Set(trace.value.targetChapters || []);
    const running = (trace.value.status || 'idle') === 'running';
    const cur = trace.value.currentNode || '';
    const writing = running && (cur === 'write' || cur === 'rewrite' || cur === 'write_ch7');
    return CHAPTER_NAMES.map((name, i) => {
      const no = i + 1;
      const st = rows.find((c) => c.chapterNo === no)?.status || '';
      let state = 'idle';
      if (st.includes('失败') || st.includes('驳回')) {
        state = 'failed';
      } else if (st.includes('生成中') || (writing && targets.has(no))) {
        state = 'live';
      } else if (st && st !== '未生成') {
        state = 'done';
      }
      return { no, name, state };
    });
  });

  const chapterHint = computed(() => {
    const chs = trace.value.targetChapters || [];
    const retry = trace.value.retryCount || 0;
    const bits: string[] = [];
    if (chs.length) {
      bits.push('正在处理第 ' + chs.join('、') + ' 章');
    }
    if (retry) {
      bits.push('已打回重写 ' + retry + ' 次（最多 ' + (trace.value.maxRetry || 2) + ' 次）');
    }
    return bits.join(' · ');
  });

  const timeline = computed(() => {
    const rows: { label: string; detail: string }[] = [];
    const seen = new Set<string>();
    for (const ev of trace.value.events || []) {
      if (ev.phase !== 'enter' || !ev.node || seen.has(ev.node + ':' + (ev.retryCount || 0))) {
        continue;
      }
      seen.add(ev.node + ':' + (ev.retryCount || 0));
      const chs = ev.targetChapters || [];
      rows.push({
        label: nodeLabel(ev.node),
        detail: chs.length ? '第 ' + chs.join('、') + ' 章' : '',
      });
    }
    return rows;
  });

  function nodeById(id: string): IGraphNode | undefined {
    return schema.value.nodes.find((n) => n.id === id);
  }

  function nodeLabel(id: string) {
    return nodeById(id)?.label || id;
  }

  function nodeExplain(id: string) {
    return nodeById(id)?.desc || nodeLabel(id);
  }

  function nodeStatus(id: string) {
    const t = trace.value;
    const events = t.events || [];
    if (t.status === 'failed' && t.currentNode === id) {
      return 'failed';
    }
    if (t.status === 'running' && t.currentNode === id) {
      return 'running';
    }
    const left = events.some((e) => e.node === id && e.phase === 'leave');
    const entered = events.some((e) => e.node === id && e.phase === 'enter');
    if (left) {
      return 'done';
    }
    if (entered) {
      return 'running';
    }
    return 'idle';
  }

  function edgeActive(e: IGraphEdge) {
    const a = nodeStatus(e.from);
    const b = nodeStatus(e.to);
    return (a === 'done' || a === 'running' || a === 'failed') && b !== 'idle';
  }

  /** 当前节点的上一步：电从这里流进来 */
  function previousNode() {
    const cur = trace.value.currentNode || '';
    const events = trace.value.events || [];
    for (let i = events.length - 1; i >= 0; i--) {
      const node = events[i].node;
      if (node && node !== cur) {
        return node;
      }
    }
    return '';
  }

  function edgeFlow(e: IGraphEdge) {
    if ((trace.value.status || 'idle') !== 'running') {
      return false;
    }
    if (e.to !== trace.value.currentNode) {
      return false;
    }
    const prev = previousNode();
    if (prev) {
      return e.from === prev;
    }
    return e.from === 'start' || nodeStatus(e.from) === 'done';
  }

  function edgeStroke(e: IGraphEdge) {
    if (edgeFlow(e)) {
      return '#1677ff';
    }
    if (edgeActive(e)) {
      return '#52c41a';
    }
    return '#94a3b8';
  }

  type Side = 'l' | 'r' | 't' | 'b';

  function portPoint(n: IGraphNode, side: Side, along = 0.5) {
    const t = Math.min(0.85, Math.max(0.15, along));
    if (side === 'l') {
      return { x: n.x, y: n.y + NODE_H * t };
    }
    if (side === 'r') {
      return { x: n.x + NODE_W, y: n.y + NODE_H * t };
    }
    if (side === 't') {
      return { x: n.x + NODE_W * t, y: n.y };
    }
    return { x: n.x + NODE_W * t, y: n.y + NODE_H };
  }

  function nudge(p: { x: number; y: number }, side: Side, dist = PORT_OUT) {
    if (side === 'r') {
      return { x: p.x + dist, y: p.y };
    }
    if (side === 'l') {
      return { x: p.x - dist, y: p.y };
    }
    if (side === 'b') {
      return { x: p.x, y: p.y + dist };
    }
    return { x: p.x, y: p.y - dist };
  }

  /** 每条边固定出入口，避免多条条件边从同一点挤出去 */
  function edgePorts(e: IGraphEdge): { fs: Side; ts: Side; fa: number; ta: number } {
    const table: Record<string, { fs: Side; ts: Side; fa?: number; ta?: number }> = {
      'start->prepare': { fs: 'r', ts: 'l' },
      'prepare->write': { fs: 'r', ts: 'l' },
      'write->qc': { fs: 'r', ts: 'l' },
      'qc->write_ch7': { fs: 'b', ts: 't' },
      'qc->rewrite': { fs: 'l', ts: 'r', fa: 0.72, ta: 0.45 },
      'qc->persist': { fs: 'r', ts: 'b', fa: 0.28, ta: 0.3 },
      'rewrite->write': { fs: 't', ts: 'b' },
      'write_ch7->consistency': { fs: 'b', ts: 't' },
      'consistency->rewrite': { fs: 't', ts: 'b', fa: 0.22, ta: 0.62 },
      'consistency->persist': { fs: 'l', ts: 'r' },
      'persist->end': { fs: 'l', ts: 'r' },
    };
    const hit = table[`${e.from}->${e.to}`] || { fs: 'r' as Side, ts: 'l' as Side };
    return { fs: hit.fs, ts: hit.ts, fa: hit.fa ?? 0.5, ta: hit.ta ?? 0.5 };
  }

  function aligned(a: { x: number; y: number }, b: { x: number; y: number }) {
    return Math.abs(a.x - b.x) < 3 || Math.abs(a.y - b.y) < 3;
  }

  function edgePointList(e: IGraphEdge): { x: number; y: number }[] {
    const a = nodeById(e.from);
    const b = nodeById(e.to);
    if (!a || !b) {
      return [];
    }
    const { fs, ts, fa, ta } = edgePorts(e);
    const p1 = portPoint(a, fs, fa);
    const p2 = portPoint(b, ts, ta);
    if (aligned(p1, p2) && ((fs === 'r' && ts === 'l') || (fs === 'l' && ts === 'r') || (fs === 'b' && ts === 't') || (fs === 't' && ts === 'b'))) {
      return [p1, p2];
    }
    const s = nudge(p1, fs);
    const t = nudge(p2, ts);
    const pts = [p1, s];
    if (aligned(s, t)) {
      pts.push(t);
    } else if (fs === 'b' || fs === 't') {
      pts.push({ x: t.x, y: s.y });
      pts.push(t);
    } else {
      pts.push({ x: s.x, y: t.y });
      pts.push(t);
    }
    pts.push(p2);
    return pts;
  }

  function edgePoints(e: IGraphEdge) {
    return edgePointList(e)
      .map((p) => `${p.x},${p.y}`)
      .join(' ');
  }

  function edgeLabelPos(e: IGraphEdge) {
    const pts = edgePointList(e);
    if (pts.length < 2) {
      return { x: 0, y: 0 };
    }
    let best = 0;
    let bestLen = -1;
    for (let i = 0; i < pts.length - 1; i++) {
      const len = Math.abs(pts[i + 1].x - pts[i].x) + Math.abs(pts[i + 1].y - pts[i].y);
      if (len > bestLen) {
        bestLen = len;
        best = i;
      }
    }
    const a = pts[best];
    const b = pts[best + 1];
    const mx = (a.x + b.x) / 2;
    const my = (a.y + b.y) / 2;
    const horiz = Math.abs(b.x - a.x) >= Math.abs(b.y - a.y);
    return horiz ? { x: mx, y: my - 10 } : { x: mx + 10, y: my };
  }

  async function loadSchema() {
    schemaError.value = '';
    try {
      const s = await getGraphSchema();
      if (!s || !s.nodes || !s.nodes.length) {
        schema.value = { nodes: [], edges: [] };
        schemaError.value = '编排拓扑为空，请检查后端 /sh/report/graph/schema';
        return;
      }
      schema.value = applyLayout(s);
    } catch (e) {
      schema.value = { nodes: [], edges: [] };
      schemaError.value = '编排拓扑加载失败，请检查后端 /sh/report/graph/schema';
    }
  }

  async function loadTrace() {
    if (demoing.value || !props.batchNo) {
      return;
    }
    try {
      const t = await getGraphTrace(props.batchNo);
      if (!t || !t.status || t.status === 'idle') {
        return;
      }
      const localN = (trace.value.events || []).length;
      const remoteN = (t.events || []).length;
      const localBatch = trace.value.batchNo;
      if (localBatch && t.batchNo && localBatch !== t.batchNo) {
        return;
      }
      if (remoteN >= localN || (trace.value.status || 'idle') === 'idle') {
        trace.value = t;
      }
    } catch {
      // ignore poll errors
    }
  }

  function stopFollow() {
    if (followTimer != null) {
      clearTimeout(followTimer);
      followTimer = null;
    }
  }

  /** 仅在「跑图中、且这条会话没有实时事件」时补拉，结束后停 */
  function scheduleFollow() {
    stopFollow();
    if (sseDriven || demoing.value || !props.batchNo) {
      return;
    }
    if ((trace.value.status || 'idle') !== 'running') {
      return;
    }
    followTimer = window.setTimeout(async () => {
      followTimer = null;
      if (sseDriven || demoing.value) {
        return;
      }
      await loadTrace();
      scheduleFollow();
    }, 1500);
  }

  async function runDemo(s: string) {
    demoing.value = true;
    scenario.value = s;
    trace.value = { status: 'running', events: [], retryCount: 0, currentNode: 'start' };
    try {
      const stream = await runGraphDemo(s);
      await readDemoStream(stream);
    } catch (e: any) {
      message.error(
        (e?.message || '').includes('HTTP')
          ? '模拟接口还没生效，请先重启后端，再点「看完整流程」。'
          : 'Demo 失败：' + (e?.message || '未知错误')
      );
      trace.value = { ...trace.value, status: 'failed' };
    } finally {
      demoing.value = false;
      scenario.value = '';
    }
  }

  async function readDemoStream(stream: ReadableStream<Uint8Array>) {
    const reader = stream.getReader();
    const decoder = new TextDecoder('UTF-8');
    let buffer = '';
    let finished = false;
    const handle = (event: string, data: string) => {
      if (event === 'schema') {
        try {
          schema.value = applyLayout(JSON.parse(data));
        } catch {
          // ignore
        }
      } else if (event === 'node' || event === 'trace') {
        try {
          const payload = JSON.parse(data);
          const next = payload.trace || payload;
          if (next && (next.events || next.status)) {
            trace.value = next;
          }
        } catch {
          // ignore
        }
      } else if (event === 'error') {
        message.error(data);
        trace.value = { ...trace.value, status: 'failed' };
      } else if (event === 'done') {
        finished = true;
      }
    };
    while (!finished) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split('\n\n');
      buffer = parts.pop() || '';
      for (const part of parts) {
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
          handle(event, dataLines.join('\n'));
        }
      }
    }
  }

  watch(
    () => props.chapters,
    () => {
      const live = (props.chapters || []).some((c) => String(c.status || '').includes('生成中'));
      if (!live && (trace.value.status || 'idle') === 'running' && props.batchNo && !demoing.value) {
        loadTrace();
      }
    },
    { deep: true }
  );

  watch(
    () => props.batchNo,
    async () => {
      sseDriven = false;
      stopFollow();
      trace.value = { status: 'idle', events: [], batchNo: props.batchNo };
      await loadTrace();
      scheduleFollow();
    }
  );

  watch(
    () => props.liveEvent,
    (ev) => {
      if (!ev) {
        return;
      }
      sseDriven = true;
      stopFollow();
      if (ev.batchNo && trace.value.batchNo && ev.batchNo !== trace.value.batchNo) {
        trace.value = { status: 'running', events: [], batchNo: ev.batchNo };
      }
      if (ev.trace) {
        trace.value = ev.trace;
        return;
      }
      if (Array.isArray(ev.events) && ev.status && ev.status !== 'running') {
        trace.value = ev;
        return;
      }
      const events = [...(trace.value.events || [])];
      events.push({
        node: ev.node,
        phase: ev.phase || 'leave',
        ts: Date.now(),
        retryCount: ev.retryCount,
        targetChapters: ev.targetChapters,
      });
      trace.value = {
        ...trace.value,
        status: ev.cancelled ? 'cancelled' : 'running',
        currentNode: ev.node,
        retryCount: ev.retryCount,
        maxRetry: ev.maxRetry,
        targetChapters: ev.targetChapters,
        events,
        batchNo: ev.batchNo || trace.value.batchNo,
      };
    }
  );

  onMounted(async () => {
    if (props.liveEvent) {
      sseDriven = true;
    }
    await loadSchema();
    await loadTrace();
    scheduleFollow();
  });

  onUnmounted(() => {
    stopFollow();
  });
</script>

<style lang="less" scoped>
  .graph-board {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;
    background: #fff;

    &.compact {
      flex: 1;
      height: 100%;
      min-height: 0;

      .gb-svg {
        width: 100%;
        height: 100%;
      }

      .gb-legend .hint {
        display: none;
      }

      .gb-canvas {
        flex: 1 1 auto;
        min-height: 0;
        padding: 6px 8px 0;
        overflow: hidden;
      }

      .gb-now,
      .gb-chapters,
      .gb-legend {
        flex-shrink: 0;
      }
    }
  }

  .gb-intro {
    padding: 12px 16px 10px;
    border-bottom: 1px solid #eef1f6;
    background: #f8fbff;

    .intro-title {
      font-weight: 600;
      color: #1f2a37;
      margin-bottom: 6px;
    }

    .intro-body {
      font-size: 13px;
      line-height: 1.65;
      color: #475569;
    }

    .intro-demo {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin-top: 10px;
    }

    .demo-label {
      font-size: 12px;
      color: #64748b;
    }
  }

  .gb-livebar {
    padding: 8px 16px;
    border-bottom: 1px solid #eef1f6;
    background: #f8fbff;
    font-size: 12px;
    color: #64748b;
  }

  .gb-chapters {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    padding: 8px 16px;
    border-bottom: 1px solid #eef1f6;

    .ch-lamp {
      font-size: 12px;
      line-height: 22px;
      padding: 0 8px;
      border-radius: 999px;
      border: 1px solid #e2e8f0;
      color: #94a3b8;
      background: #f8fafc;
    }

    .ch-lamp.live {
      color: #0958d9;
      border-color: #1677ff;
      background: #e6f4ff;
      animation: gb-led 0.7s ease-in-out infinite;
    }

    .ch-lamp.done {
      color: #389e0d;
      border-color: #b7eb8f;
      background: #f6ffed;
    }

    .ch-lamp.failed {
      color: #cf1322;
      border-color: #ffccc7;
      background: #fff2f0;
    }
  }

  .gb-now {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 16px;
    border-bottom: 1px solid #eef1f6;
    font-size: 13px;

    &.now-running {
      background: #e6f4ff;
    }

    .now-text {
      color: #1f2a37;
    }

    .now-extra {
      margin-left: auto;
      font-size: 12px;
      color: #1677ff;
    }
  }

  .gb-canvas {
    flex: 0 0 auto;
    padding: 16px 12px 8px;
    overflow-x: auto;
    overflow-y: hidden;
  }

  .gb-svg {
    display: block;
    width: 100%;
    height: 460px;
  }

  .gb-edge {
    pointer-events: none;
  }

  .gb-node-hit {
    cursor: pointer;
  }

  .gb-edge-label-halo {
    font-size: 11px;
    fill: #fff;
    stroke: #fff;
    stroke-width: 4px;
    paint-order: stroke;
    text-anchor: middle;
  }

  .gb-edge-label {
    font-size: 11px;
    fill: #475569;
    font-weight: 600;
    text-anchor: middle;
  }

  .gb-schema-error {
    margin: 12px 16px;
    padding: 10px 12px;
    border: 1px solid #ffccc7;
    background: #fff2f0;
    color: #cf1322;
    border-radius: 6px;
    font-size: 13px;
  }

  .gb-node {
    fill: #f8fafc;
    stroke: #d0d7e2;
    stroke-width: 1.2;
  }

  .gb-node.picked {
    stroke-width: 2.4;
  }

  .st-running {
    fill: #e6f4ff;
    stroke: #1677ff;
    stroke-width: 2.6;
    animation: gb-pulse 0.7s ease-in-out infinite;
  }

  .gb-led {
    fill: #1677ff;
    stroke: #fff;
    stroke-width: 1.5;
    animation: gb-led 0.7s ease-in-out infinite;
  }

  .gb-flow {
    animation: gb-flow 0.45s linear infinite;
  }

  @keyframes gb-pulse {
    0%,
    100% {
      fill: #bae0ff;
      stroke: #0958d9;
      filter: drop-shadow(0 0 6px #1677ff);
    }
    50% {
      fill: #ffffff;
      stroke: #69b1ff;
      filter: drop-shadow(0 0 1px #1677ff);
    }
  }

  @keyframes gb-led {
    0%,
    100% {
      fill: #0958d9;
      opacity: 1;
    }
    50% {
      fill: #ffffff;
      opacity: 0.25;
    }
  }

  @keyframes gb-flow {
    to {
      stroke-dashoffset: -36;
    }
  }

  .st-done {
    fill: #f6ffed;
    stroke: #52c41a;
  }

  .st-failed {
    fill: #fff2f0;
    stroke: #ff4d4f;
  }

  .gb-node-label {
    font-size: 12px;
    font-weight: 600;
    fill: #1f2a37;
  }

  .gb-legend {
    display: flex;
    gap: 14px;
    padding: 4px 16px 8px;
    font-size: 12px;
    color: #64748b;
    align-items: center;

    .dot {
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      margin-right: 4px;
      background: #d0d7e2;
    }
    .running {
      background: #1677ff;
    }
    .done {
      background: #52c41a;
    }
    .failed {
      background: #ff4d4f;
    }
    .hint {
      margin-left: auto;
    }
  }

  .gb-explain {
    padding: 8px 16px 10px;
    background: #fafbfc;
    border-top: 1px solid #eef1f6;

    .exp-name {
      font-weight: 600;
      font-size: 13px;
      color: #1f2a37;
      margin-bottom: 2px;
    }

    .exp-desc {
      font-size: 12px;
      line-height: 1.55;
      color: #64748b;
    }
  }

  .gb-log {
    flex: 1;
    min-height: 0;
    border-top: 1px solid #eef1f6;
    display: flex;
    flex-direction: column;

    .log-head {
      padding: 8px 16px;
      font-size: 12px;
      font-weight: 600;
      color: #475569;
    }

    .log-empty {
      padding: 4px 16px 16px;
      color: #94a3b8;
      font-size: 12px;
    }

    .log-list {
      overflow: auto;
      margin: 0;
      padding: 0 16px 12px 32px;
      font-size: 13px;
      color: #334155;
      line-height: 1.7;
    }

    .log-extra {
      margin-left: 8px;
      color: #94a3b8;
      font-size: 12px;
    }
  }
</style>
