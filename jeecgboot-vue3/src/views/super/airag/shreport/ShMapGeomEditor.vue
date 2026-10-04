<template>
  <div class="geom-editor">
    <div class="geom-hint">
      <span class="hint-text">
        <template v-if="kind === 'point'">拖动标记调整位置；无坐标时双击地图放置点位</template>
        <template v-else-if="kind === 'line'">拖动顶点调整形状；点击线段中点圆点插入顶点；双击顶点删除；双击空白处在末尾追加顶点</template>
        <template v-else-if="kind === 'poly'">拖动顶点调整形状；点击边中点圆点插入顶点；双击顶点删除；双击空白处在末尾追加顶点</template>
        <template v-else>加载几何中…</template>
        <span v-if="multiPart" class="hint-extra">（原几何为多部件/带内环，仅编辑第一部件的外环，其余保持不变）</span>
      </span>
      <!-- 底图与详情页地图一致：默认影像，可切矢量 -->
      <a-radio-group v-model:value="baseType" size="small" button-style="solid" class="base-switch">
        <a-radio-button value="img">影像</a-radio-button>
        <a-radio-button value="vec">矢量</a-radio-button>
      </a-radio-group>
    </div>
    <div ref="mapEl" class="geom-map" :style="{ height: (height || 320) + 'px' }"></div>
    <div class="geom-footer">
      <span class="geom-meta" v-if="kind">
        {{ kind === 'point' ? '点' : kind === 'poly' ? `面：${coords.length} 个顶点` : `线：${coords.length} 个顶点` }}
      </span>
      <span class="geom-error" v-if="errorText">{{ errorText }}</span>
    </div>
  </div>
</template>

<script lang="ts" setup>
  import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
  import L, { Map as LMap, Marker, Polyline, TileLayer } from 'leaflet';
  import 'leaflet/dist/leaflet.css';
  import { message } from 'ant-design-vue';
  import { getGeom } from './ShMapDataAdmin.api';
  import { getMapConfig } from './onemap/ShOneMap.api';
  import { DEFAULT_TIANDITU_KEY, TIANDITU_LAYERS } from './onemap/mapStyles';

  defineOptions({ name: 'ShMapGeomEditor' });

  /** 内嵌式几何编辑器：嵌在行编辑弹窗内，随表单一起保存（父组件监听 change 取当前几何） */
  const props = defineProps<{
    dsKey: string;
    record: Recordable | null;
    height?: number;
  }>();
  const emit = defineEmits<{
    (e: 'change', geometry: any | null): void;
  }>();

  const mapEl = ref<HTMLElement>();
  const errorText = ref('');
  const kind = ref<'point' | 'line' | 'poly' | null>(null);
  const multiPart = ref(false);
  const coords = ref<[number, number][]>([]);
  /** 底图类型：与详情页地图一致，默认影像 */
  const baseType = ref<'img' | 'vec'>('img');
  /** 用户是否动过几何：只有动过才在保存时提交几何，避免无改动覆盖 */
  let touched = false;

  let map: LMap | null = null;
  let baseLayers: TileLayer[] = [];
  let shape: Polyline | Marker | null = null;
  let vertexGroup: L.LayerGroup | null = null;
  let midGroup: L.LayerGroup | null = null;
  /** 保存多部件/内环等不参与编辑的骨架，构建时原样拼回 */
  let skeleton: { holes: number[][][]; others: number[][][] | number[][][][] } = { holes: [], others: [] };

  const TIANDITU_SUBDOMAINS = ['0', '1', '2', '3', '4', '5', '6', '7'];

  onMounted(init);
  onBeforeUnmount(destroyMap);

  watch(
    () => props.record?.id,
    () => {
      // 换行编辑时重置几何与编辑状态
      touched = false;
      errorText.value = '';
      destroyMap();
      init();
    }
  );

  function destroyMap() {
    baseLayers.forEach((l) => l.remove());
    baseLayers = [];
    map?.remove();
    map = null;
    shape = null;
    vertexGroup = null;
    midGroup = null;
  }

  async function init() {
    if (!mapEl.value) {
      return;
    }
    let center: [number, number] = [36.9, 101.7];
    let zoom = 10;
    try {
      const cfg = await getMapConfig();
      if (cfg?.center) {
        center = cfg.center;
        zoom = cfg.zoom || zoom;
      }
    } catch {
      // 配置接口失败用默认中心
    }
    map = L.map(mapEl.value, { doubleClickZoom: false, zoomControl: true }).setView(center, zoom);
    vertexGroup = L.layerGroup().addTo(map!);
    midGroup = L.layerGroup().addTo(map!);
    map!.on('dblclick', onMapDblClick);
    applyBase();
    await loadGeometry();
    setTimeout(() => map?.invalidateSize(), 120);
  }

  watch(baseType, applyBase);

  function applyBase() {
    if (!map) {
      return;
    }
    baseLayers.forEach((l) => l.remove());
    baseLayers = [];
    const tk = String(DEFAULT_TIANDITU_KEY);
    const cfg = TIANDITU_LAYERS[baseType.value] || TIANDITU_LAYERS.img;
    const common = { subdomains: TIANDITU_SUBDOMAINS, maxZoom: 18, minZoom: 1, attribution: '&copy; 天地图' };
    baseLayers.push(L.tileLayer(cfg.base.replaceAll('{tk}', tk), common).addTo(map!));
    baseLayers.push(L.tileLayer(cfg.label.replaceAll('{tk}', tk), common).addTo(map!));
  }

  async function loadGeometry() {
    if (!props.dsKey || !props.record?.id) {
      return;
    }
    let geometry: any = null;
    try {
      const res = await getGeom(props.dsKey, props.record.id);
      geometry = res?.geometry || null;
    } catch {
      message.warning('读取几何失败，可双击地图重新绘制');
    }
    skeleton = { holes: [], others: [] };
    multiPart.value = false;
    coords.value = [];
    if (!geometry) {
      // 无几何时按数据集类型从零绘制：隐患点/断面线/面图层
      kind.value = props.dsKey === 'hazard' ? 'point' : props.dsKey === 'section' ? 'line' : 'poly';
      rebuild();
      return;
    }
    const type = String(geometry.type || '');
    const cs = geometry.coordinates;
    if (type === 'Point') {
      kind.value = 'point';
      coords.value = [[Number(cs[0]), Number(cs[1])]];
    } else if (type === 'LineString') {
      kind.value = 'line';
      coords.value = (cs as [number, number][]).map((p) => [Number(p[0]), Number(p[1])]);
    } else if (type === 'MultiLineString') {
      kind.value = 'line';
      multiPart.value = (cs as any[]).length > 1;
      coords.value = toPairs(cs[0]);
      skeleton.others = cs.slice(1);
    } else if (type === 'Polygon') {
      kind.value = 'poly';
      const ring0 = cs[0] || [];
      if (ring0.length > 1 && isClosed(ring0)) {
        ring0.pop();
      }
      coords.value = toPairs(ring0);
      skeleton.holes = cs.slice(1);
    } else if (type === 'MultiPolygon') {
      kind.value = 'poly';
      multiPart.value = (cs as any[]).length > 1 || (cs[0]?.length || 0) > 1;
      const ring0 = cs[0]?.[0] || [];
      if (ring0.length > 1 && isClosed(ring0)) {
        ring0.pop();
      }
      coords.value = toPairs(ring0);
      skeleton.holes = cs[0]?.slice(1) || [];
      skeleton.others = cs.slice(1);
    }
    rebuild();
    fitCurrent();
  }

  function toPairs(ring: any[]): [number, number][] {
    return (ring || []).map((p: any) => [Number(p[0]), Number(p[1])]);
  }

  function isClosed(ring: any[]): boolean {
    if (!ring || ring.length < 2) {
      return false;
    }
    const a = ring[0];
    const b = ring[ring.length - 1];
    return Number(a[0]) === Number(b[0]) && Number(a[1]) === Number(b[1]);
  }

  /** Leaflet 用的纬度在前 */
  function latlngs(): L.LatLngExpression[] {
    return coords.value.map(([lng, lat]) => [lat, lng]);
  }

  function rebuild() {
    if (!map) {
      return;
    }
    if (shape) {
      shape.remove();
      shape = null;
    }
    vertexGroup?.clearLayers();
    midGroup?.clearLayers();
    if (kind.value === 'point') {
      if (coords.value.length === 1) {
        const [lng, lat] = coords.value[0];
        const mk = L.marker([lat, lng], { draggable: true, icon: pointIcon() });
        mk.on('dragend', () => {
          const ll = mk.getLatLng();
          coords.value = [[round(ll.lng), round(ll.lat)]];
          touch();
          rebuild();
        });
        shape = mk.addTo(map!);
      }
    } else if (coords.value.length >= 2) {
      if (kind.value === 'poly') {
        shape = L.polygon(latlngs() as any, { color: '#f59e0b', weight: 2, fillColor: '#f59e0b', fillOpacity: 0.18 }).addTo(map!);
      } else {
        shape = L.polyline(latlngs() as any, { color: '#f59e0b', weight: 3 }).addTo(map!);
      }
    }
    // 顶点手柄
    coords.value.forEach((c, i) => {
      const mk = L.marker([c[1], c[0]], { draggable: true, icon: vertexIcon() });
      mk.on('dragend', () => {
        const ll = mk.getLatLng();
        coords.value[i] = [round(ll.lng), round(ll.lat)];
        touch();
        rebuild();
      });
      mk.on('dblclick', (e: any) => {
        L.DomEvent.stopPropagation(e);
        removeVertex(i);
      });
      vertexGroup?.addLayer(mk);
    });
    // 中点插入手柄（面首尾相接也生成一段）
    if (kind.value !== 'point' && coords.value.length >= 2) {
      const segs = coords.value.length - (kind.value === 'poly' ? 0 : 1);
      for (let i = 0; i < segs; i++) {
        const a = coords.value[i];
        const b = coords.value[(i + 1) % coords.value.length];
        const mid: [number, number] = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
        const cm = L.circleMarker([mid[1], mid[0]], { radius: 4, color: '#fff', weight: 1.5, fillColor: '#2563eb', fillOpacity: 0.95 });
        cm.on('click', (e: any) => {
          L.DomEvent.stopPropagation(e);
          coords.value.splice(i + 1, 0, mid);
          touch();
          rebuild();
        });
        midGroup?.addLayer(cm);
      }
    }
    if (touched) {
      // 每次编辑后把当前几何（可能为 null 表示不完整）同步给父组件，随表单一起保存
      emit('change', buildGeometry());
    }
  }

  function touch() {
    touched = true;
  }

  function removeVertex(i: number) {
    const min = kind.value === 'poly' ? 3 : 2;
    if (coords.value.length <= min) {
      message.warning(`至少需要 ${min} 个顶点`);
      return;
    }
    coords.value.splice(i, 1);
    touch();
    rebuild();
  }

  function onMapDblClick(e: L.LeafletMouseEvent) {
    if (kind.value === 'point') {
      coords.value = [[round(e.latlng.lng), round(e.latlng.lat)]];
      touch();
      rebuild();
      return;
    }
    if (kind.value === 'line' || kind.value === 'poly') {
      coords.value.push([round(e.latlng.lng), round(e.latlng.lat)]);
      touch();
      rebuild();
    }
  }

  function fitCurrent() {
    if (!map) {
      return;
    }
    if (kind.value === 'point' && coords.value.length === 1) {
      map.setView([coords.value[0][1], coords.value[0][0]], Math.max(map.getZoom(), 15));
      return;
    }
    if (coords.value.length >= 2 && shape && 'getBounds' in shape) {
      try {
        map.fitBounds((shape as Polyline).getBounds().pad(0.25));
      } catch {
        // 忽略
      }
    }
  }

  function round(v: number) {
    return Math.round(v * 1e7) / 1e7;
  }

  function pointIcon() {
    return L.divIcon({
      className: 'sh-geom-icon-wrap',
      html: '<div class="sh-geom-point"></div>',
      iconSize: [16, 16],
      iconAnchor: [8, 8],
    });
  }

  function vertexIcon() {
    return L.divIcon({
      className: 'sh-geom-icon-wrap',
      html: '<div class="sh-geom-vtx"></div>',
      iconSize: [12, 12],
      iconAnchor: [6, 6],
    });
  }

  /** 按原几何类型拼回完整 GeoJSON：编辑部分 + 保留的内环/其他部件；不完整时返回 null 并提示 */
  function buildGeometry(): any | null {
    errorText.value = '';
    const cs = coords.value;
    if (kind.value === 'point') {
      if (cs.length !== 1) {
        errorText.value = '请先在地图上放置点位（双击或单击空白处）';
        return null;
      }
      return { type: 'Point', coordinates: cs[0] };
    }
    if (cs.length < (kind.value === 'poly' ? 3 : 2)) {
      errorText.value = kind.value === 'poly' ? '面至少需要 3 个顶点（双击地图追加）' : '线至少需要 2 个顶点（双击地图追加）';
      return null;
    }
    if (kind.value === 'line') {
      if (skeleton.others.length) {
        return { type: 'MultiLineString', coordinates: [cs, ...skeleton.others] };
      }
      return { type: 'LineString', coordinates: cs };
    }
    // poly：外环闭合后拼回
    const ring = [...cs, cs[0]];
    if (skeleton.others.length || skeleton.holes.length) {
      return { type: 'MultiPolygon', coordinates: [[ring, ...skeleton.holes], ...skeleton.others] };
    }
    return { type: 'Polygon', coordinates: [ring] };
  }
</script>

<style lang="less" scoped>
  .geom-editor {
    .geom-hint {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 8px;
      font-size: 12px;
      color: #667085;

      .hint-text {
        flex: 1;
        min-width: 0;
      }

      .base-switch {
        flex: none;
      }

      .hint-extra {
        color: #d48806;
      }
    }

    .geom-map {
      width: 100%;
      border-radius: 8px;
      overflow: hidden;
      background: #dbe4ee;
    }

    .geom-footer {
      display: flex;
      align-items: center;
      gap: 12px;
      margin-top: 8px;

      .geom-meta {
        font-size: 12px;
        color: #475467;
      }

      .geom-error {
        font-size: 12px;
        color: #ff4d4f;
      }
    }
  }
</style>

<style lang="less">
  /* Leaflet 手柄样式需全局（渲染在地图容器内） */
  .sh-geom-icon-wrap {
    background: transparent;
    border: none;
  }

  .sh-geom-point {
    width: 16px;
    height: 16px;
    border-radius: 50%;
    background: rgba(245, 158, 11, 0.35);
    border: 2px solid #f59e0b;
    box-shadow: 0 0 0 2px #fff;
    cursor: move;
  }

  .sh-geom-vtx {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: #fff;
    border: 2px solid #f59e0b;
    box-sizing: border-box;
    cursor: move;
  }
</style>
