<template>
  <a-drawer :open="open" :title="title" width="640" @close="emit('update:open', false)">
    <a-spin :spinning="loading">
      <div class="meta">
        <a-tag v-if="profile?.secTypeLabel" color="blue">{{ profile.secTypeLabel }}</a-tag>
        <span v-if="profile?.rvname">{{ profile.rvname }}</span>
        <span v-if="profile?.wsnm">{{ profile.wsnm }}</span>
      </div>
      <div v-if="profile?.kind === 'none' && !loading" class="empty">该断面暂无成果数据</div>
      <div v-show="profile?.kind && profile.kind !== 'none'" ref="chartEl" class="chart"></div>
      <div v-if="profile?.kind === 'long'" class="legend-note"> 纵断面：蓝线为河底高程，绿线为水面高程（横轴为距离 m） </div>
      <div v-if="profile?.kind === 'cross'" class="legend-note"> 横断面：红线为地面高程（横轴为起点距 m），悬停可查看糙率与特征点描述 </div>
    </a-spin>
  </a-drawer>
</template>

<script lang="ts" setup>
  import { computed, nextTick, ref, watch } from 'vue';
  import type { EChartsOption } from 'echarts';
  import { useECharts } from '/@/hooks/web/useECharts';
  import { getSectionProfile, type ISectionProfile } from '../ShOneMap.api';

  defineOptions({ name: 'ShOneMapSectionProfileDrawer' });

  const props = defineProps<{
    open: boolean;
    pid?: string;
    name?: string;
  }>();
  const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>();

  const loading = ref(false);
  const profile = ref<ISectionProfile | null>(null);
  const chartEl = ref<HTMLDivElement>();
  const { setOptions } = useECharts(chartEl);

  const title = computed(() => props.name || profile.value?.secName || profile.value?.pid || '断面剖面');

  watch(
    () => [props.open, props.pid],
    async () => {
      if (!props.open || !props.pid) {
        return;
      }
      loading.value = true;
      try {
        profile.value = await getSectionProfile(props.pid);
      } catch {
        profile.value = null;
      } finally {
        loading.value = false;
      }
      await nextTick();
      render();
    }
  );

  function render() {
    const p = profile.value;
    if (!p || !p.points?.length || p.kind === 'none') {
      return;
    }
    const xs = p.points.map((pt) => Number(pt.dist ?? 0));
    let option: EChartsOption;
    if (p.kind === 'cross') {
      option = {
        grid: { left: 56, right: 24, top: 32, bottom: 40 },
        tooltip: {
          trigger: 'axis',
          formatter: (params: any) => {
            const i = Array.isArray(params) ? (params[0]?.dataIndex ?? 0) : 0;
            const pt = p.points[i] || {};
            const parts = [`起点距: ${pt.dist ?? '-'} m`, `高程: ${pt.elev ?? '-'} m`];
            if (pt.roughness != null) {
              parts.push(`糙率: ${pt.roughness}`);
            }
            if (pt.point_desc) {
              parts.push(pt.point_desc);
            }
            return parts.join('<br/>');
          },
        },
        xAxis: { type: 'value', name: '起点距(m)', min: Math.min(...xs) },
        yAxis: { type: 'value', name: '高程(m)', scale: true },
        dataZoom: [{ type: 'inside' }],
        series: [
          {
            type: 'line',
            name: '地面高程',
            data: p.points.map((pt) => [Number(pt.dist ?? 0), Number(pt.elev ?? 0)]),
            showSymbol: false,
            lineStyle: { color: '#e11d48', width: 2 },
            areaStyle: { color: 'rgba(225, 29, 72, 0.08)' },
          },
        ],
      };
    } else {
      option = {
        grid: { left: 56, right: 24, top: 32, bottom: 40 },
        tooltip: { trigger: 'axis' },
        legend: { top: 0, data: ['河底高程', '水面高程'] },
        xAxis: { type: 'value', name: '距离(m)', min: Math.min(...xs) },
        yAxis: { type: 'value', name: '高程(m)', scale: true },
        dataZoom: [{ type: 'inside' }],
        series: [
          {
            type: 'line',
            name: '河底高程',
            data: p.points.map((pt) => [Number(pt.dist ?? 0), Number(pt.bed_elev ?? 0)]),
            showSymbol: false,
            lineStyle: { color: '#2563eb', width: 2 },
          },
          {
            type: 'line',
            name: '水面高程',
            data: p.points.map((pt) => [Number(pt.dist ?? 0), Number(pt.water_elev ?? 0)]),
            showSymbol: false,
            lineStyle: { color: '#059669', width: 2, type: 'dashed' },
          },
        ],
      };
    }
    setOptions(option, true);
  }
</script>

<style lang="less" scoped>
  .meta {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 8px;
    color: #475569;
    font-size: 13px;
  }

  .chart {
    width: 100%;
    height: 380px;
  }

  .empty {
    color: #94a3b8;
    font-size: 13px;
    text-align: center;
    padding: 32px 0;
  }

  .legend-note {
    color: #94a3b8;
    font-size: 12px;
    margin-top: 8px;
  }
</style>
