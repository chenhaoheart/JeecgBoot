<template>
  <div class="ll-picker">
    <div class="ll-map-wrap">
      <div ref="mapEl" class="ll-map"></div>
      <div class="ll-base-switch">
        <a-radio-group v-model:value="baseType" size="small" button-style="solid">
          <a-radio-button value="img">影像</a-radio-button>
          <a-radio-button value="vec">矢量（政区）</a-radio-button>
          <a-radio-button value="ter">地形</a-radio-button>
        </a-radio-group>
      </div>
    </div>
    <div class="ll-hint">{{ hint }}</div>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
  import L, { Map as LMap, Marker, TileLayer } from 'leaflet';
  import 'leaflet/dist/leaflet.css';
  import { DEFAULT_TIANDITU_KEY, TIANDITU_LAYERS } from './onemap/mapStyles';

  defineOptions({ name: 'ShLngLatPicker' });

  const props = defineProps<{
    lng?: number | null;
    lat?: number | null;
    /** 只读展示：标记不可拖动、点击不选点（详情弹窗用） */
    readonly?: boolean;
    /** 叠加的关联图层（GeoJSON geometry，如按 15 位政区编码关联的防治对象面），虚线蓝框展示 */
    extraGeojson?: any;
    /** 叠加图层的名称提示 */
    extraLabel?: string;
  }>();
  const emit = defineEmits<{
    (e: 'update:lng', v: number | null): void;
    (e: 'update:lat', v: number | null): void;
  }>();

  const mapEl = ref<HTMLElement>();
  /** 底图类型：默认影像图，可切换矢量（含政区注记）/地形 */
  const baseType = ref<'img' | 'vec' | 'ter'>('img');
  let map: LMap | null = null;
  let baseLayers: TileLayer[] = [];
  let marker: Marker | null = null;
  /** 关联图层（如防治对象面） */
  let extraLayer: L.GeoJSON | null = null;
  /** 组件内部触发的坐标回写不再反向移动标记，避免循环 */
  let selfSet = false;
  const TIANDITU_SUBDOMAINS = ['0', '1', '2', '3', '4', '5', '6', '7'];

  const hint = computed(() => {
    const base = props.readonly
      ? '该记录的平面位置（只读）'
      : '点击地图设置点位，或拖动标记微调；与经纬度输入双向同步';
    return props.extraGeojson ? `${base}；蓝框为按编码关联的${props.extraLabel || '防治对象面'}` : base;
  });

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

  watch(baseType, () => applyBase());

  function round(v: number) {
    return Math.round(v * 1e7) / 1e7;
  }

  function valid(lng?: number | null, lat?: number | null) {
    const a = Number(lng);
    const b = Number(lat);
    return Number.isFinite(a) && Number.isFinite(b) && a !== 0 && b !== 0 && Math.abs(a) <= 180 && Math.abs(b) <= 90;
  }

  function pointIcon() {
    return L.divIcon({
      className: 'sh-geom-icon-wrap',
      html: '<div class="sh-geom-point"></div>',
      iconSize: [16, 16],
      iconAnchor: [8, 8],
    });
  }

  function place(lng: number, lat: number, fly = false) {
    if (!map) {
      return;
    }
    if (!marker) {
      marker = L.marker([lat, lng], { draggable: !props.readonly, icon: pointIcon() }).addTo(map);
      marker.on('dragend', () => {
        const ll = marker!.getLatLng();
        push(ll.lng, ll.lat);
      });
    } else {
      marker.setLatLng([lat, lng]);
    }
    if (fly) {
      // 地图可能尚未设置初始视野，getZoom 为空时按 15 级定位（与隐患要素编辑页的几何编辑器一致）
      map.setView([lat, lng], Math.max(map.getZoom() || 0, 15));
    }
  }

  function push(lng: number, lat: number) {
    selfSet = true;
    emit('update:lng', round(lng));
    emit('update:lat', round(lat));
    selfSet = false;
  }

  onMounted(() => {
    if (!mapEl.value) {
      return;
    }
    map = L.map(mapEl.value, { zoomControl: true, attributionControl: true }).setView([36.9, 101.7], 4);
    map.attributionControl.setPosition('bottomleft');
    applyBase();
    drawExtra();
    map.on('click', (e: L.LeafletMouseEvent) => {
      if (props.readonly) {
        return;
      }
      place(e.latlng.lng, e.latlng.lat);
      push(e.latlng.lng, e.latlng.lat);
    });
    if (valid(props.lng, props.lat)) {
      // 有关联图层时以 fitBounds 为准（不飞行）；否则飞行定位到点位
      place(Number(props.lng), Number(props.lat), !props.extraGeojson);
    }
    setTimeout(() => {
      map?.invalidateSize();
      fitExtra();
    }, 150);
  });

  /** 关联图层（GeoJSON geometry）：虚线蓝框展示并定位 */
  function drawExtra() {
    if (!map) {
      return;
    }
    if (extraLayer) {
      extraLayer.remove();
      extraLayer = null;
    }
    const g = props.extraGeojson;
    if (!g) {
      return;
    }
    try {
      extraLayer = L.geoJSON(g as any, {
        style: { color: '#1677ff', weight: 2, dashArray: '6 4', fillColor: '#1677ff', fillOpacity: 0.08 },
      }).addTo(map);
      fitExtra();
    } catch {
      extraLayer = null;
    }
  }

  /**
   * 定位到关联图层。弹窗刚打开时容器可能还没完成布局（尺寸为 0），
   * fitBounds 会失效并把视野钳到最低级，因此推迟到下一帧并在 200ms 后再校准一次。
   */
  function fitExtra() {
    const run = () => {
      if (!map || !extraLayer) {
        return;
      }
      map.invalidateSize();
      try {
        const b = extraLayer.getBounds();
        if (b && b.isValid()) {
          map.fitBounds(b.pad(0.4));
        }
      } catch {
        // 图层尚无有效范围时忽略
      }
    };
    requestAnimationFrame(run);
    setTimeout(run, 200);
  }

  watch(
    () => props.extraGeojson,
    () => drawExtra()
  );

  watch(
    () => [props.lng, props.lat],
    ([a, b]) => {
      if (selfSet) {
        return;
      }
      if (valid(a, b)) {
        place(Number(a), Number(b));
      }
    }
  );

  onBeforeUnmount(() => {
    baseLayers.forEach((l) => l.remove());
    baseLayers = [];
    map?.remove();
    map = null;
    marker = null;
  });
</script>

<style lang="less" scoped>
  .ll-picker {
    .ll-map-wrap {
      position: relative;

      .ll-map {
        width: 100%;
        height: 280px;
        border-radius: 8px;
        overflow: hidden;
        background: #dbe4ee;
      }

      .ll-base-switch {
        position: absolute;
        top: 8px;
        right: 8px;
        z-index: 500;

        :deep(.ant-radio-group-small) {
          box-shadow: 0 1px 4px rgba(0, 21, 41, 0.25);
        }
      }
    }

    .ll-hint {
      margin-top: 4px;
      font-size: 12px;
      color: #98a2b3;
    }
  }
</style>
