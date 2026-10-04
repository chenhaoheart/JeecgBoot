<template>
  <div class="one-map-board">
    <section class="om-map">
      <MapCanvas
        ref="mapRef"
        :config="config"
        :tianditu-key="config?.tiandituKey || ''"
        :map-type="mapType"
        :watershed="geo.watershed"
        :section="geo.section"
        :prevention="geo.prevention"
        :hazard="geo.hazard"
        :visible="visible"
        :category-filters="categoryFilters"
        :selected-wscd="wscd || selected.lybm"
        :fly-to="flyTo"
        :highlight="highlight"
        @select-watershed="onMapWatershed"
        @feature-click="onFeatureClick"
        @section-click="onSectionClick"
        @bounds-change="onBoundsChange"
        @open-detail="onOpenDetail"
      />
      <div v-if="loading" class="map-spin">
        <a-spin />
      </div>
      <div v-if="empty" class="empty-mask">暂无空间数据，请先执行 sh_report_import 数据导入</div>
    </section>

    <div class="om-overlay">
      <aside class="om-float om-left" :class="{ collapsed: leftCollapsed }">
        <button v-if="leftCollapsed" type="button" class="dock-fab" @click="leftCollapsed = false">筛选</button>
        <template v-else>
          <div class="float-card region-card">
            <div class="card-head region-head">
              <div class="region-tabs">
                <button type="button" :class="{ on: filterMode === 'admin' }" @click="filterMode = 'admin'">政区</button>
                <button type="button" :class="{ on: filterMode === 'watershed' }" @click="filterMode = 'watershed'">流域</button>
              </div>
              <button type="button" class="icon-btn" title="收起" @click="leftCollapsed = true">
                <DoubleLeftOutlined />
              </button>
            </div>
            <FilterBar
              v-model:filter-mode="filterMode"
              :watersheds="filters.watersheds"
              :counties="filters.counties"
              :towns="filters.towns"
              :wscd="wscd"
              :county-adcd="countyAdcd"
              :town-adcd="townAdcd"
              @update:wscd="onWscd"
              @update:countyAdcd="onCounty"
              @update:townAdcd="onTown"
              @reset="resetFilter"
            />
          </div>
          <div class="float-card layer-card">
            <LayerControl
              :visible="visible"
              :section-types="stats.sectionByType || {}"
              :prevention-types="stats.preventionByType || {}"
              :hazard-types="stats.hazardByType || {}"
              :category-filters="categoryFilters"
              :watershed-count="wsCount"
              :section-count="secCount"
              :prevention-count="poCount"
              :hazard-count="hzCount"
              @toggle="onToggleLayer"
              @toggle-type="onToggleType"
            />
          </div>
        </template>
      </aside>

      <div class="om-tools">
        <div class="om-toolbar om-actions">
          <div class="stats">
            流域 {{ stats.watershedCount || 0 }} · 断面 {{ stats.sectionCount || 0 }} · 防治 {{ stats.preventionCount || 0 }} · 隐患
            {{ stats.hazardCount || 0 }}
          </div>
          <a-button type="primary" size="small" :disabled="!selected.lybm" @click="openConfirm()">生成报告</a-button>
        </div>
      </div>

      <div class="om-map-ctrls">
        <div class="om-toolbar om-maptype">
          <a-radio-group v-model:value="mapType" size="small" button-style="solid">
            <a-radio-button value="img">影像</a-radio-button>
            <a-radio-button value="vec">矢量</a-radio-button>
            <a-radio-button value="ter">地形</a-radio-button>
          </a-radio-group>
        </div>
        <div class="om-zoom">
          <button type="button" title="放大" @click="zoomIn">+</button>
          <button type="button" title="缩小" @click="zoomOut">−</button>
        </div>
      </div>

      <aside class="om-float om-right" :class="{ collapsed: rightCollapsed }">
        <button v-if="rightCollapsed" type="button" class="dock-fab" @click="rightCollapsed = false">列表</button>
        <div v-else class="float-card risk-card">
          <div class="card-head list-head">
            <div class="list-tabs">
              <button type="button" :class="{ on: listTab === 'prevention' }" @click="listTab = 'prevention'">防治对象</button>
              <button type="button" :class="{ on: listTab === 'hazard' }" @click="listTab = 'hazard'">隐患要素</button>
            </div>
            <button type="button" class="icon-btn" title="收起" @click="rightCollapsed = true">
              <DoubleRightOutlined />
            </button>
          </div>
          <RiskSidePanel
            v-model:tab="listTab"
            :wscd="wscd || selected.lybm"
            :adcd="adcd"
            :hazard-types="Object.keys(stats.hazardByType || {})"
            @open="onCardOpen"
          />
        </div>
      </aside>
    </div>

    <SectionProfileDrawer v-model:open="sectionOpen" :pid="sectionPid" :name="sectionName" />
    <FeatureDetailDrawer v-model:open="detailOpen" :tab="detailTab" :item="detailItem" />
    <GenerateConfirm
      :open="confirmOpen"
      :lybm="selected.lybm"
      :lymc="selected.lymc"
      :object-count="selected.objectCount"
      :population="selected.population"
      @confirm="onConfirmGenerate"
      @cancel="confirmOpen = false"
    />
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { useRouter } from 'vue-router';
  import { DoubleLeftOutlined, DoubleRightOutlined } from '@ant-design/icons-vue';
  import FilterBar from './components/FilterBar.vue';
  import LayerControl from './components/LayerControl.vue';
  import MapCanvas from './components/MapCanvas.vue';
  import RiskSidePanel from './components/RiskSidePanel.vue';
  import SectionProfileDrawer from './components/SectionProfileDrawer.vue';
  import FeatureDetailDrawer from './components/FeatureDetailDrawer.vue';
  import GenerateConfirm from './components/GenerateConfirm.vue';
  import { getMapConfig, getMapFilters, getMapGeojson, getMapStats, IGeoJSON, IMapConfig, IMapFilters, IMapStats } from './ShOneMap.api';

  defineOptions({ name: 'ShOneMapBoard' });

  const props = defineProps<{
    boundLybm?: string;
  }>();

  const emit = defineEmits<{
    (e: 'selectWatershed', p: { lybm: string; lymc: string }): void;
    (e: 'generate', p: { lybm: string; lymc: string }): void;
  }>();

  const router = useRouter();

  const config = ref<IMapConfig | null>(null);
  const filters = reactive<IMapFilters>({ watersheds: [], counties: [], towns: [] });
  const stats = reactive<IMapStats>({
    watershedCount: 0,
    sectionCount: 0,
    preventionCount: 0,
    hazardCount: 0,
  });
  const geo = reactive<{
    watershed: IGeoJSON | null;
    section: IGeoJSON | null;
    prevention: IGeoJSON | null;
    hazard: IGeoJSON | null;
  }>({ watershed: null, section: null, prevention: null, hazard: null });
  const visible = reactive({ watershed: true, section: true, prevention: true, hazard: true });
  const categoryFilters = reactive<Record<string, string[]>>({});
  const mapType = ref<'img' | 'vec' | 'ter'>('img');
  const mapRef = ref<{ zoomIn: () => void; zoomOut: () => void; openFeaturePopup?: (layer: 'prevention' | 'hazard', id: string | number) => void } | null>(null);
  const leftCollapsed = ref(false);
  const rightCollapsed = ref(false);
  const listTab = ref<'prevention' | 'hazard'>('prevention');
  const filterMode = ref<'admin' | 'watershed'>('watershed');
  const wscd = ref('');
  const countyAdcd = ref('');
  const townAdcd = ref('');
  const loading = ref(false);
  const flyTo = ref<{ lat: number; lng: number; zoom?: number; key: number } | null>(null);
  const highlight = ref<{ lat: number; lng: number } | null>(null);
  const selected = reactive<{ lybm: string; lymc: string; objectCount?: number; population?: number }>({ lybm: '', lymc: '' });
  const confirmOpen = ref(false);
  const sectionOpen = ref(false);
  const sectionPid = ref('');
  const sectionName = ref('');
  const detailOpen = ref(false);
  const detailTab = ref<'prevention' | 'hazard'>('hazard');
  const detailId = ref('');
  const detailItem = computed(() => ({ id: detailId.value }));
  let lastBbox = '';
  let lastFetchedSectionKey = '';
  let bboxTimer: ReturnType<typeof setTimeout> | null = null;

  const adcd = computed(() => townAdcd.value || countyAdcd.value);
  const wsCount = computed(() => geo.watershed?.features?.length || 0);
  const secCount = computed(() => geo.section?.features?.length || 0);
  const poCount = computed(() => geo.prevention?.features?.length || 0);
  const hzCount = computed(() => geo.hazard?.features?.length || 0);
  const empty = computed(() => !loading.value && !wsCount.value && !poCount.value && !hzCount.value);

  function zoomIn() {
    mapRef.value?.zoomIn();
  }

  function zoomOut() {
    mapRef.value?.zoomOut();
  }

  watch(
    () => props.boundLybm,
    (v) => {
      if (v && v !== wscd.value) {
        const hit = filters.watersheds.find((w) => w.wscd === v);
        wscd.value = v;
        selected.lybm = v;
        selected.lymc = hit?.wsnm || v;
        loadGeo();
      }
    }
  );

  onMounted(async () => {
    loading.value = true;
    try {
      const [cfg, flt, st] = await Promise.all([getMapConfig(), getMapFilters(), getMapStats()]);
      config.value = cfg;
      filters.watersheds = flt?.watersheds || [];
      filters.counties = flt?.counties || [];
      filters.towns = flt?.towns || [];
      Object.assign(stats, st || {});
      initLegend(st);
      if (props.boundLybm) {
        wscd.value = props.boundLybm;
        selected.lybm = props.boundLybm;
        selected.lymc = filters.watersheds.find((w) => w.wscd === props.boundLybm)?.wsnm || props.boundLybm;
      }
      await loadGeo();
    } finally {
      loading.value = false;
    }
  });

  function initLegend(st?: IMapStats) {
    categoryFilters.section = Object.keys(st?.sectionByType || {});
    categoryFilters.prevention = Object.keys(st?.preventionByType || {});
    categoryFilters.hazard = Object.keys(st?.hazardByType || {});
  }

  async function loadGeo() {
    loading.value = true;
    try {
      const params = { wscd: wscd.value || undefined, adcd: adcd.value || undefined };
      const [ws, po, hz] = await Promise.all([
        getMapGeojson('watershed', params),
        getMapGeojson('prevention', params),
        getMapGeojson('hazard', params),
      ]);
      geo.watershed = ws;
      geo.prevention = po;
      geo.hazard = hz;
    } catch {
      geo.watershed = { type: 'FeatureCollection', features: [] };
      geo.prevention = { type: 'FeatureCollection', features: [] };
      geo.hazard = { type: 'FeatureCollection', features: [] };
    } finally {
      loading.value = false;
    }
    lastFetchedSectionKey = '';
    await loadSections();
  }

  /**
   * 断面线按需加载：选中流域按流域取；未选中时按当前视野 bbox 取（全图 7000+ 条一次拉取过大）。
   */
  async function loadSections() {
    if (!visible.section) {
      return;
    }
    const params: { wscd?: string; adcd?: string; bbox?: string } = { adcd: adcd.value || undefined };
    let key: string;
    if (wscd.value) {
      params.wscd = wscd.value;
      key = `wscd:${wscd.value}:${adcd.value || ''}`;
    } else {
      if (!lastBbox) {
        return; // 等待首次视野事件
      }
      params.bbox = lastBbox;
      key = `bbox:${lastBbox}:${adcd.value || ''}`;
    }
    if (key === lastFetchedSectionKey) {
      return;
    }
    lastFetchedSectionKey = key;
    try {
      geo.section = await getMapGeojson('section', params);
    } catch {
      geo.section = { type: 'FeatureCollection', features: [] };
    }
  }

  function onBoundsChange(p: { bbox: string }) {
    lastBbox = p.bbox;
    if (!visible.section || wscd.value) {
      return;
    }
    if (bboxTimer) {
      clearTimeout(bboxTimer);
    }
    bboxTimer = setTimeout(loadSections, 300);
  }

  function applySelected(lybm: string, lymc: string, extra?: { objectCount?: number; population?: number }) {
    selected.lybm = lybm;
    selected.lymc = lymc || lybm;
    selected.objectCount = extra?.objectCount;
    selected.population = extra?.population;
    const hit = filters.watersheds.find((w) => w.wscd === lybm);
    if (hit) {
      selected.objectCount = extra?.objectCount ?? hit.objectCount;
      selected.population = extra?.population ?? hit.population;
    }
    emit('selectWatershed', { lybm, lymc: selected.lymc });
  }

  function onWscd(v: string) {
    wscd.value = v;
    if (v) {
      const hit = filters.watersheds.find((w) => w.wscd === v);
      applySelected(v, hit?.wsnm || v, { objectCount: hit?.objectCount, population: hit?.population });
    } else {
      selected.lybm = '';
      selected.lymc = '';
    }
    loadGeo();
  }

  function onCounty(v: string) {
    if (countyAdcd.value === v) {
      return; // 流域点选会连发 county/town/wscd 三个事件，值未变时不重复加载，避免旧响应覆盖新数据
    }
    countyAdcd.value = v;
    townAdcd.value = '';
    loadGeo();
  }

  function onTown(v: string) {
    if (townAdcd.value === v) {
      return;
    }
    townAdcd.value = v;
    loadGeo();
  }

  function resetFilter() {
    wscd.value = '';
    countyAdcd.value = '';
    townAdcd.value = '';
    selected.lybm = '';
    selected.lymc = '';
    loadGeo();
  }

  function onToggleLayer(key: 'watershed' | 'section' | 'prevention' | 'hazard', on: boolean) {
    visible[key] = on;
    if (key === 'section' && on) {
      lastFetchedSectionKey = '';
      loadSections();
    }
  }

  function onToggleType(layer: 'section' | 'prevention' | 'hazard', value: string) {
    const cur = new Set(categoryFilters[layer] || []);
    if (cur.has(value)) {
      cur.delete(value);
    } else {
      cur.add(value);
    }
    categoryFilters[layer] = Array.from(cur);
  }

  function onMapWatershed(p: { lybm: string; lymc: string; objectCount?: number; population?: number }) {
    if (!p.lybm) {
      return;
    }
    // 流域面覆盖全图，误点空白区域也会命中：这里只做高亮选中并同步右侧列表，
    // 不再自动过滤图层、也不自动弹出「生成报告」确认框（需要时用工具栏按钮主动触发）
    applySelected(p.lybm, p.lymc, p);
  }

  function onFeatureClick(p: { layer: 'prevention' | 'hazard'; props: any }) {
    // 详情由图层自身的弹出窗口展示，这里仅同步选中的小流域
    const lybm = p.props?.wscd;
    if (lybm && !selected.lybm) {
      wscd.value = lybm;
      applySelected(lybm, p.props?.wsnm || lybm);
    }
  }

  function onSectionClick(p: { props: any }) {
    const propsData = p.props || {};
    if (propsData.wscd && !selected.lybm) {
      applySelected(propsData.wscd, propsData.wsnm || propsData.wscd);
    }
    sectionPid.value = String(propsData.pid || '');
    sectionName.value = String(propsData.name || propsData.pid || '断面剖面');
    sectionOpen.value = true;
  }

  /** 地图弹窗「查看详情」：打开要素详情抽屉（含调查成果、现场照片/视频） */
  function onOpenDetail(p: { layer: 'prevention' | 'hazard'; id: string }) {
    detailTab.value = p.layer;
    detailId.value = p.id;
    detailOpen.value = true;
  }

  function firstCoordinate(geometry: any): [number, number] | null {
    if (!geometry?.coordinates) {
      return null;
    }
    let c: any = geometry.coordinates;
    while (Array.isArray(c) && Array.isArray(c[0])) {
      c = c[0];
    }
    if (Array.isArray(c) && c.length >= 2 && typeof c[0] === 'number') {
      return [c[0], c[1]];
    }
    return null;
  }

  function onLocate(_tab: 'prevention' | 'hazard', item: any) {
    const lng = Number(item.lgtd);
    const lat = Number(item.lttd);
    if (Number.isFinite(lng) && Number.isFinite(lat)) {
      flyTo.value = { lat, lng, zoom: 15, key: Date.now() };
      highlight.value = { lat, lng };
      return;
    }
    const pid = String(item.fcd || item.obj_pid || item.pid || item.code || '');
    const fc = _tab === 'prevention' ? geo.prevention : geo.hazard;
    const feat = fc?.features?.find((f: any) => String(f.properties?.pid || '') === pid);
    const coord = firstCoordinate(feat?.geometry);
    if (coord) {
      flyTo.value = { lat: coord[1], lng: coord[0], zoom: 15, key: Date.now() };
      highlight.value = { lat: coord[1], lng: coord[0] };
    }
  }

  /** 列表卡片点击：地图定位到要素并在其上弹出详情窗口 */
  function onCardOpen(tab: 'prevention' | 'hazard', item: any) {
    onLocate(tab, item);
    mapRef.value?.openFeaturePopup?.(tab, item.id);
  }

  function openConfirm() {
    if (!selected.lybm) {
      return;
    }
    confirmOpen.value = true;
  }

  function onConfirmGenerate() {
    confirmOpen.value = false;
    const payload = { lybm: selected.lybm, lymc: selected.lymc };
    emit('generate', payload);
    router.push({
      path: '/super/airag/shreport/ShReportAssistant',
      query: { lybm: payload.lybm, lymc: payload.lymc, action: 'generate' },
    });
  }
</script>

<style lang="less" scoped>
  .one-map-board {
    position: relative;
    width: 100%;
    height: 100%;
    min-height: 0;
    overflow: hidden;
    background: #dbe4ee;

    .om-map {
      position: absolute;
      inset: 0;
      display: flex;
      flex-direction: column;

      :deep(.map-canvas) {
        flex: 1;
        min-height: 0;
        height: 100%;
      }

      .map-spin {
        position: absolute;
        inset: 0;
        z-index: 600;
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(255, 255, 255, 0.28);
        pointer-events: none;
      }

      .empty-mask {
        position: absolute;
        inset: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        pointer-events: none;
        color: #64748b;
        font-size: 13px;
      }
    }

    .om-overlay {
      position: absolute;
      inset: 0;
      z-index: 700;
      pointer-events: none;
    }

    .om-float,
    .om-tools,
    .om-toolbar,
    .om-map-ctrls,
    .dock-fab {
      pointer-events: auto;
    }

    .om-left,
    .om-right {
      pointer-events: none;
    }

    .om-left > *,
    .om-right > * {
      pointer-events: auto;
    }

    .float-card {
      background: rgba(255, 255, 255, 0.92);
      backdrop-filter: blur(10px);
      border: 1px solid rgba(255, 255, 255, 0.7);
      border-radius: 10px;
      box-shadow: 0 10px 28px rgba(15, 23, 42, 0.16);
      overflow: hidden;
    }

    .card-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 10px 8px 12px;
      font-size: 13px;
      font-weight: 600;
      color: #0f172a;
      border-bottom: 1px solid #eef1f6;
    }

    .icon-btn {
      border: 0;
      background: transparent;
      padding: 2px 4px;
      color: #64748b;
      cursor: pointer;
      line-height: 1;
    }

    /* 「筛选」表头与 政区/流域 tab 合并为一行 */
    .card-head.region-head {
      padding: 6px 8px 6px 6px;

      .region-tabs {
        flex: 1;
        display: flex;
        margin-right: 8px;
        padding: 2px;
        background: #f1f5f9;
        border-radius: 6px;

        button {
          flex: 1;
          border: 0;
          background: transparent;
          padding: 4px 0;
          font-size: 12px;
          color: #64748b;
          border-radius: 4px;
          cursor: pointer;

          &.on {
            background: #fff;
            color: #2563eb;
            font-weight: 600;
            box-shadow: 0 1px 2px rgba(15, 23, 42, 0.08);
          }
        }
      }
    }

    /* 「对象列表」表头与 防治对象/隐患要素 tab 合并为一行 */
    .card-head.list-head {
      padding: 0 8px 0 0;
      border-bottom: 0;

      .list-tabs {
        flex: 1;
        display: flex;
        border-bottom: 1px solid #eef1f6;

        button {
          flex: 1;
          border: 0;
          background: transparent;
          padding: 10px 0;
          font-size: 13px;
          color: #64748b;
          cursor: pointer;
          border-bottom: 2px solid transparent;

          &.on {
            color: #2563eb;
            font-weight: 600;
            border-bottom-color: #2563eb;
          }
        }
      }
    }

    .dock-fab {
      border: 0;
      background: rgba(255, 255, 255, 0.94);
      box-shadow: 0 8px 20px rgba(15, 23, 42, 0.16);
      border-radius: 8px;
      padding: 8px 10px;
      font-size: 12px;
      font-weight: 600;
      color: #2563eb;
      cursor: pointer;
    }

    .om-left {
      position: absolute;
      top: 12px;
      left: 12px;
      bottom: 12px;
      width: 320px;
      display: flex;
      flex-direction: column;
      gap: 10px;

      &.collapsed {
        width: auto;
        bottom: auto;
      }

      .region-card {
        flex: 1;
        min-height: 0;
        display: flex;
        flex-direction: column;

        :deep(.filter-bar) {
          flex: 1;
          min-height: 0;
          padding: 8px 8px 0;
        }
      }

      .layer-card {
        flex: 0 0 auto;
        max-height: 55%;
      }
    }

    .om-right {
      position: absolute;
      top: 12px;
      right: 12px;
      bottom: 12px;
      width: 300px;
      display: flex;
      flex-direction: column;

      &.collapsed {
        bottom: auto;
        width: auto;
      }

      .risk-card {
        flex: 1;
        min-height: 0;
        display: flex;
        flex-direction: column;

        :deep(.risk-side) {
          flex: 1;
          min-height: 0;
        }
      }
    }

    .om-tools {
      position: absolute;
      top: 12px;
      left: 344px;
      display: flex;
      align-items: center;
      gap: 8px;
      width: max-content;
      pointer-events: none;
    }

    .om-left.collapsed ~ .om-tools {
      left: 72px;
    }

    .om-toolbar {
      width: max-content;
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 8px;
      background: rgba(255, 255, 255, 0.92);
      backdrop-filter: blur(10px);
      border: 1px solid rgba(255, 255, 255, 0.7);
      border-radius: 10px;
      box-shadow: 0 8px 20px rgba(15, 23, 42, 0.12);
      pointer-events: auto;

      .stats {
        font-size: 12px;
        color: #64748b;
        white-space: nowrap;
      }
    }

    .om-map-ctrls {
      position: absolute;
      /* 摆放到右侧面板（300px 宽 + 12px 边距）左侧，避免与面板叠压 */
      right: 324px;
      bottom: 12px;
      display: flex;
      align-items: center;
      gap: 8px;
      z-index: 800;
    }

    /* 右侧面板收起后，控件移回右下角 */
    .one-map-board:has(.om-right.collapsed) .om-map-ctrls {
      right: 12px;
    }

    .om-zoom {
      display: flex;
      overflow: hidden;
      background: rgba(255, 255, 255, 0.92);
      backdrop-filter: blur(10px);
      border: 1px solid rgba(255, 255, 255, 0.7);
      border-radius: 10px;
      box-shadow: 0 8px 20px rgba(15, 23, 42, 0.12);

      button {
        width: 32px;
        height: 32px;
        border: 0;
        border-right: 1px solid #e2e8f0;
        background: transparent;
        color: #334155;
        font-size: 18px;
        line-height: 1;
        cursor: pointer;

        &:last-child {
          border-right: 0;
        }

        &:hover {
          background: #f8fafc;
        }
      }
    }
  }
</style>
