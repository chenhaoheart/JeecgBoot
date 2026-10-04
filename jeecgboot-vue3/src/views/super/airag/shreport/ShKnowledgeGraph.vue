<template>
  <div class="kg">
    <div class="kg-bar">
      <div class="kg-title">
        <span class="kg-name">山洪知识图谱</span>
        <span class="kg-sub">{{ modeText }}</span>
      </div>
      <div class="kg-tools">
        <a-select
          v-model:value="wscd"
          :options="wsOptions"
          style="width: 220px"
          size="small"
          placeholder="小流域：关系总览"
          allowClear
          showSearch
          optionFilterProp="label"
          @change="load"
        />
        <a-select
          v-model:value="year"
          :options="yearOptions"
          style="width: 150px"
          size="small"
          placeholder="调查年份：全部"
          allowClear
          @change="load"
        />
        <a-input-search
          v-model:value="keyword"
          :placeholder="wscd ? '在这个流域里高亮名称' : '搜流域、隐患、防治对象'"
          style="width: 240px"
          size="small"
          allowClear
          @search="load"
        />
        <a-button size="small" :loading="loading" @click="load">刷新</a-button>
      </div>
    </div>
    <div v-if="loadError" class="kg-hint kg-hint-warn">{{ loadError }}</div>
    <div v-else-if="hint" class="kg-hint">{{ hint }}</div>
    <div class="kg-body">
      <div class="kg-canvas">
        <a-spin :spinning="loading">
          <div ref="chartEl" class="kg-chart"></div>
        </a-spin>
      </div>
      <aside class="kg-side">
        <template v-if="selected">
          <div class="side-kicker">{{ selected.category }}</div>
          <div class="side-name">{{ selected.name }}</div>
          <div v-if="selected.count != null" class="side-count">{{ selected.count }} 个</div>
          <p v-if="selected.blurb" class="side-blurb">{{ selected.blurb }}</p>
          <dl v-if="propsOf(selected).length" class="side-props">
            <template v-for="p in propsOf(selected)" :key="p.label">
              <dt>{{ p.label }}</dt>
              <dd>{{ p.value }}</dd>
            </template>
          </dl>
          <div class="side-actions">
            <a-button
              v-if="selected.kind === 'entity' && selected.categoryKey === 'watershed' && graph?.mode !== 'focus'"
              size="small"
              type="primary"
              @click="expandWs"
            >
              展开这个小流域
            </a-button>
            <a-button v-if="selected.ds" size="small" @click="openTable(selected.ds, selected.code)">打开{{ dsLabel(selected.ds) }}</a-button>
            <a-button v-if="selected.ds && selected.rowId" size="small" type="primary" ghost @click="editRow(selected.ds, selected.rowId)">
              编辑
            </a-button>
            <a-button v-if="selected.ds2 && selected.rowId2" size="small" @click="editRow(selected.ds2, selected.rowId2)">
              编辑{{ dsLabel(selected.ds2) }}
            </a-button>
          </div>
          <div class="rel-title">和谁有关 · {{ relations.length }}</div>
          <ul v-if="relations.length" class="rel-list">
            <li v-for="(r, i) in relations.slice(0, 40)" :key="i" @click="pickId(r.otherId)">
              <span class="rel-verb">{{ r.verb }}</span>
              <p class="rel-sentence">
                <template v-for="(p, j) in r.pieces" :key="j">
                  <b v-if="p.t === 'name'">{{ p.v }}</b>
                  <em v-else-if="p.t === 'verb'">{{ p.v }}</em>
                  <template v-else>{{ p.v }}</template>
                </template>
              </p>
            </li>
          </ul>
          <div v-else class="rel-empty">还没有连到其他对象。</div>
          <div v-if="relations.length > 40" class="rel-more">还有 {{ relations.length - 40 }} 条，放大图谱查看。</div>
        </template>
        <div v-else class="side-empty">{{ loadError || '点图谱上的圆点，查看它和谁有关。' }}</div>
      </aside>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { computed, nextTick, onMounted, ref, watch } from 'vue';
  import type { EChartsOption } from 'echarts';
  import { useECharts } from '/@/hooks/web/useECharts';
  import { getKnowledgeGraph, type IKgGraph, type IKgNode } from './ShMapDataAdmin.api';

  defineOptions({ name: 'ShKnowledgeGraph' });

  const props = defineProps<{ active?: boolean; revision?: number }>();
  const emit = defineEmits<{
    (e: 'edit', payload: { ds: string; rowId: number }): void;
    (e: 'maintain', payload: { ds: string; wscd?: string; search?: string }): void;
  }>();

  const COLORS: Record<string, string> = {
    watershed: '#722ed1',
    river: '#2f54eb',
    county: '#1677ff',
    town: '#13c2c2',
    prevention: '#52c41a',
    section: '#fa8c16',
    hazard: '#f5222d',
    road: '#d48806',
    gully: '#7cb305',
  };

  const DS_LABEL: Record<string, string> = {
    watershed: '小流域表',
    section: '断面表',
    prevention: '防治对象面',
    hazard: '隐患要素表',
    survey_object: '防治对象名录',
    survey_road: '跨沟道路表',
    survey_gully: '沟滩占地表',
  };

  const chartEl = ref<HTMLDivElement>();
  const { setOptions, getInstance, resize } = useECharts(chartEl);
  const loading = ref(false);
  const loadError = ref('');
  const graph = ref<IKgGraph | null>(null);
  const selected = ref<IKgNode | null>(null);
  const wscd = ref<string | undefined>(undefined);
  const year = ref<number | undefined>(undefined);
  const keyword = ref('');
  let clickBound = false;

  const wsOptions = computed(() =>
    (graph.value?.watersheds || []).map((w) => ({
      label: w.nm && w.nm !== w.cd ? `${w.nm}（${w.cd}）` : String(w.cd),
      value: String(w.cd),
    }))
  );
  const yearOptions = computed(() =>
    (graph.value?.years || []).map((y) => ({
      label: y.year === 0 ? `未标注（${y.count}）` : `${y.year} 年（${y.count}）`,
      value: y.year,
    }))
  );
  const hint = computed(() => graph.value?.hint || '');
  const modeText = computed(() => {
    if (graph.value?.mode === 'focus') return graph.value.wsnm || graph.value.wscd || '流域展开';
    if (graph.value?.mode === 'search') return '检索结果';
    return '对象关系总览';
  });

  const nodeMap = computed(() => {
    const m = new Map<string, IKgNode>();
    for (const n of graph.value?.nodes || []) m.set(n.id, n);
    return m;
  });

  /** 关系动词写成完整说法，避免「← 包含 红山河」这种看不出谁对谁 */
  const REL_VERB: Record<string, string> = {
    包含: '包含',
    下辖: '下辖',
    涉及: '涉及',
    辖有: '辖有',
    流经: '流经',
    位于: '位于',
    沿河: '沿着',
    威胁: '威胁',
    壅水影响: '壅水时会影响',
    溃决影响: '溃决时会影响',
    漫溢影响: '漫溢时会影响',
  };

  const SELF_WORD: Record<string, string> = {
    watershed: '本小流域',
    river: '这条河流',
    county: '本县',
    town: '本乡镇',
    prevention: '本防治对象',
    section: '本断面',
    hazard: '本隐患',
    road: '本处跨沟道路',
    gully: '本处沟滩占地',
  };

  type RelPiece = { t: 'text' | 'name' | 'verb'; v: string };

  function mention(node: IKgNode | undefined, isSelf: boolean, fallback: string): RelPiece[] {
    if (!node) return [{ t: 'name', v: fallback }];
    if (node.kind === 'type') return [{ t: 'name', v: node.name }];
    if (isSelf) return [{ t: 'text', v: SELF_WORD[node.categoryKey] || `本${node.category}` }];
    return [{ t: 'text', v: `${node.category}「` }, { t: 'name', v: node.name }, { t: 'text', v: '」' }];
  }

  const relations = computed(() => {
    const self = selected.value;
    if (!self) return [];
    return (graph.value?.links || [])
      .filter((l) => l.source === self.id || l.target === self.id)
      .map((l) => {
        const outward = l.source === self.id;
        const otherId = outward ? l.target : l.source;
        const other = nodeMap.value.get(otherId);
        const subject = mention(outward ? self : other, outward, outward ? self.name : otherId);
        const object = mention(outward ? other : self, !outward, outward ? otherId : self.name);
        const pieces: RelPiece[] = [...subject, { t: 'verb', v: REL_VERB[l.label] || l.label }, ...object];
        if (l.count != null) pieces.push({ t: 'text', v: `，共 ${l.count} 条` });
        return { otherId, verb: l.label, pieces };
      });
  });

  function dsLabel(ds?: string | null) {
    return DS_LABEL[ds || ''] || '数据表';
  }

  function propsOf(node: IKgNode) {
    return (node.props || []).filter((p) => p.value);
  }

  function pickId(id: string) {
    const node = nodeMap.value.get(id);
    if (node) selected.value = node;
  }

  function expandWs() {
    const code = selected.value?.wscd || selected.value?.code;
    if (!code) return;
    wscd.value = code;
    keyword.value = '';
    load();
  }

  function openTable(ds?: string | null, code?: string | null) {
    if (!ds) return;
    const search = selected.value?.kind === 'entity' ? code || selected.value?.name || '' : '';
    emit('maintain', { ds, wscd: selected.value?.wscd || wscd.value, search });
  }

  function editRow(ds?: string | null, rowId?: number | null) {
    if (!ds || rowId == null) return;
    emit('edit', { ds, rowId });
  }

  function paint() {
    const data = graph.value;
    if (!data) return;
    const cats = data.categories || [];
    const catIndex = new Map(cats.map((c, i) => [c.key, i]));
    const pairSeq = new Map<string, number>();
    const links = (data.links || []).map((l) => {
      const key = [l.source, l.target].sort().join('|');
      const seq = pairSeq.get(key) || 0;
      pairSeq.set(key, seq + 1);
      return {
        source: l.source,
        target: l.target,
        label: l.label,
        count: l.count,
        lineStyle: { curveness: 0.06 + seq * 0.12, width: data.mode === 'overview' ? 1.6 : 1.2 },
      };
    });
    const option: EChartsOption = {
      tooltip: {
        formatter: (p: any) => {
          if (p.dataType === 'edge') {
            return p.data?.count != null ? `${p.data.label} · ${p.data.count}` : p.data?.label || '';
          }
          const raw = nodeMap.value.get(p.data?.id);
          if (!raw) return p.name || '';
          return raw.count != null ? `${raw.name}（${raw.count}）` : raw.name;
        },
      },
      legend: { type: 'scroll', bottom: 0, data: cats.map((c) => c.name) },
      series: [
        {
          type: 'graph',
          layout: 'force',
          roam: true,
          draggable: true,
          scaleLimit: { min: 0.3, max: 4 },
          categories: cats.map((c) => ({ name: c.name, itemStyle: { color: COLORS[c.key] || '#98a2b3' } })),
          data: (data.nodes || []).map((n) => ({
            id: n.id,
            name: n.name,
            category: catIndex.get(n.categoryKey) ?? 0,
            symbolSize: n.symbolSize || 32,
            value: n.count,
            itemStyle: {
              color: COLORS[n.categoryKey] || '#98a2b3',
              borderColor: n.highlight ? '#faad14' : '#fff',
              borderWidth: n.highlight ? 4 : 2,
              shadowBlur: 8,
              shadowColor: 'rgba(15, 23, 42, 0.12)',
            },
          })),
          links,
          label: {
            show: true,
            position: 'right',
            color: '#344054',
            fontSize: 12,
            formatter: (p: any) => {
              const s = String(p.data?.name || '');
              return s.length > 12 ? s.slice(0, 12) + '…' : s;
            },
          },
          edgeLabel: {
            show: data.mode === 'overview',
            fontSize: 11,
            color: '#667085',
            formatter: (p: any) => p.data?.label || '',
          },
          lineStyle: { color: '#c5cdd8', opacity: 0.9 },
          force: {
            repulsion: data.mode === 'overview' ? 680 : 160,
            edgeLength: data.mode === 'overview' ? 160 : 70,
            gravity: data.mode === 'overview' ? 0.08 : 0.12,
            friction: 0.35,
            layoutAnimation: true,
          },
          emphasis: { focus: 'adjacency', lineStyle: { width: 2.4 } },
        },
      ],
    };
    setOptions(option);
    window.setTimeout(() => {
      resize();
      const inst = getInstance();
      if (inst && !clickBound) {
        clickBound = true;
        inst.on('click', (params: any) => {
          if (params?.dataType !== 'node') return;
          const node = nodeMap.value.get(params.data?.id);
          if (node) selected.value = node;
        });
      }
    }, 60);
  }

  async function load() {
    loading.value = true;
    loadError.value = '';
    try {
      const res = await getKnowledgeGraph({
        wscd: wscd.value || undefined,
        q: keyword.value?.trim() || undefined,
        year: year.value,
      });
      graph.value = res;
      if (res?.mode === 'focus' && res.wscd && !wscd.value) {
        wscd.value = res.wscd;
      }
      const prev = selected.value?.id;
      const nodes = res?.nodes || [];
      selected.value = nodes.find((n) => n.id === prev) || nodes.find((n) => n.categoryKey === 'watershed') || nodes[0] || null;
      await nextTick();
      paint();
    } catch (e: any) {
      const msg = e?.message || '图谱加载失败';
      loadError.value = msg.includes('路径不存在')
        ? '知识图谱接口还没加载。请重新启动后端，再点刷新。'
        : msg;
    } finally {
      loading.value = false;
    }
  }

  watch(
    () => props.active,
    (v) => {
      if (v) nextTick(() => resize());
    }
  );
  watch(
    () => props.revision,
    () => load()
  );

  onMounted(load);
</script>

<style lang="less" scoped>
  .kg {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    background: #fff;
    border-radius: 10px;
    box-shadow: 0 1px 4px rgba(0, 21, 41, 0.06);
    overflow: hidden;
  }

  .kg-bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
    padding: 12px 14px 8px;
  }

  .kg-title {
    display: flex;
    align-items: baseline;
    gap: 10px;
    min-width: 0;
  }

  .kg-name {
    font-size: 16px;
    font-weight: 650;
    color: #101828;
  }

  .kg-sub {
    font-size: 12px;
    color: #667085;
  }

  .kg-tools {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }

  .kg-hint {
    padding: 0 14px 8px;
    font-size: 12px;
    color: #667085;
    line-height: 1.6;
  }

  .kg-hint-warn {
    color: #d48806;
  }

  .kg-body {
    flex: 1;
    min-height: 0;
    display: flex;
    border-top: 1px solid #f0f0f0;
  }

  .kg-canvas {
    flex: 1;
    min-width: 0;
    background: linear-gradient(180deg, #f8fafc 0%, #fff 48%);

    :deep(.ant-spin-nested-loading),
    :deep(.ant-spin-container) {
      height: 100%;
    }
  }

  .kg-chart {
    height: calc(100vh - 210px);
    min-height: 520px;
  }

  .kg-side {
    flex: none;
    width: 300px;
    padding: 14px 14px 18px;
    border-left: 1px solid #f0f0f0;
    overflow: auto;
    max-height: calc(100vh - 168px);
  }

  .side-kicker {
    font-size: 12px;
    color: #98a2b3;
    letter-spacing: 1px;
  }

  .side-name {
    margin-top: 4px;
    font-size: 16px;
    font-weight: 650;
    color: #101828;
    line-height: 1.4;
    word-break: break-all;
  }

  .side-count {
    margin-top: 4px;
    color: #1677ff;
    font-size: 13px;
  }

  .side-blurb {
    margin: 10px 0 0;
    font-size: 13px;
    line-height: 1.7;
    color: #475467;
  }

  .side-props {
    display: grid;
    grid-template-columns: 72px 1fr;
    gap: 6px 8px;
    margin: 12px 0 0;
    font-size: 12px;

    dt {
      color: #98a2b3;
    }

    dd {
      margin: 0;
      color: #344054;
      word-break: break-all;
    }
  }

  .side-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 14px;
  }

  .rel-title {
    margin-top: 16px;
    font-size: 13px;
    font-weight: 650;
    color: #344054;
  }

  .rel-list {
    list-style: none;
    margin: 8px 0 0;
    padding: 0;

    li {
      padding: 10px 0;
      border-bottom: 1px solid #f0f0f0;
      cursor: pointer;

      &:hover .rel-sentence {
        color: #1677ff;
      }
    }
  }

  .rel-verb {
    display: inline-block;
    margin-bottom: 4px;
    padding: 0 8px;
    font-size: 12px;
    font-weight: 650;
    line-height: 22px;
    color: #0958d9;
    background: #e6f4ff;
    border-radius: 4px;
  }

  .rel-sentence {
    margin: 0;
    font-size: 14px;
    line-height: 1.65;
    color: #101828;

    b {
      font-weight: 700;
    }

    em {
      margin: 0 2px;
      font-style: normal;
      font-weight: 700;
      color: #0958d9;
    }
  }

  .rel-empty,
  .rel-more,
  .side-empty {
    margin-top: 8px;
    font-size: 12px;
    color: #98a2b3;
    line-height: 1.6;
  }
</style>
