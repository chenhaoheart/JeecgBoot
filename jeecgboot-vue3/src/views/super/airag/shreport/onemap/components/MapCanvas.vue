<template>
  <div class="map-canvas">
    <div ref="el" class="map-el"></div>
  </div>
</template>

<script lang="ts" setup>
  import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
  import L, { GeoJSON as LGeoJSON, LayerGroup, Map as LMap, TileLayer } from 'leaflet';
  import 'leaflet/dist/leaflet.css';
  import type { IGeoJSON, IMapConfig } from '../ShOneMap.api';
  import { DEFAULT_TIANDITU_KEY, TIANDITU_LAYERS, preventionStyle, typeColor, WATERSHED_SELECTED_STYLE, WATERSHED_STYLE } from '../mapStyles';
  import { getFileAccessHttpUrl } from '/@/utils/common/compUtils';

  defineOptions({ name: 'ShOneMapCanvas' });

  const props = defineProps<{
    config: IMapConfig | null;
    tiandituKey: string;
    mapType: 'img' | 'vec' | 'ter';
    watershed: IGeoJSON | null;
    section: IGeoJSON | null;
    prevention: IGeoJSON | null;
    hazard: IGeoJSON | null;
    visible: { watershed: boolean; section: boolean; prevention: boolean; hazard: boolean };
    categoryFilters: Record<string, string[]>;
    selectedWscd?: string;
    flyTo?: { lat: number; lng: number; zoom?: number; key: number } | null;
    highlight?: { lat: number; lng: number } | null;
  }>();

  const emit = defineEmits<{
    (e: 'selectWatershed', p: { lybm: string; lymc: string; objectCount?: number; population?: number }): void;
    (e: 'featureClick', p: { layer: 'prevention' | 'hazard'; props: any }): void;
    (e: 'sectionClick', p: { props: any }): void;
    (e: 'boundsChange', p: { bbox: string }): void;
    (e: 'openDetail', p: { layer: 'prevention' | 'hazard'; id: string }): void;
  }>();

  const el = ref<HTMLElement>();
  let map: LMap | null = null;
  let baseLayers: TileLayer[] = [];
  let wsLayer: LGeoJSON | null = null;
  let secLayer: LGeoJSON | null = null;
  let poLayer: LGeoJSON | null = null;
  let hzLayer: LGeoJSON | null = null;
  let highlightLayer: LayerGroup | null = null;
  const overlay = L.featureGroup();
  /** 当前打开的要素弹出窗口（redraw 重建图层后据此恢复） */
  let activePopup: { layer: 'prevention' | 'hazard'; id: string } | null = null;
  let resizeObs: ResizeObserver | null = null;
  const TIANDITU_SUBDOMAINS = ['0', '1', '2', '3', '4', '5', '6', '7'];

  function resolveTk() {
    return String(props.tiandituKey || DEFAULT_TIANDITU_KEY).trim() || DEFAULT_TIANDITU_KEY;
  }

  function refreshSize() {
    nextTick(() => map?.invalidateSize());
  }

  function emitBounds() {
    if (!map) {
      return;
    }
    const b = map.getBounds();
    emit('boundsChange', {
      bbox: [b.getWest(), b.getSouth(), b.getEast(), b.getNorth()].map((n) => n.toFixed(6)).join(','),
    });
  }

  onMounted(() => {
    if (!el.value) {
      return;
    }
    // 默认视野：中国全图（后端 config 缺省时兜底）
    const center = props.config?.center || [35.6, 104.2];
    const zoom = props.config?.zoom || 4;
    map = L.map(el.value, { zoomControl: false, attributionControl: true }).setView(center as [number, number], zoom);
    map.attributionControl.setPosition('bottomleft');
    overlay.addTo(map);
    highlightLayer = L.layerGroup().addTo(map);
    map.on('moveend zoomend', emitBounds);
    el.value.addEventListener('click', onPopupClick);
    applyBase();
    redraw(true);
    resizeObs = new ResizeObserver(() => map?.invalidateSize());
    resizeObs.observe(el.value);
    setTimeout(refreshSize, 80);
    setTimeout(refreshSize, 400);
    setTimeout(emitBounds, 500);
  });

  onBeforeUnmount(() => {
    resizeObs?.disconnect();
    resizeObs = null;
    el.value?.removeEventListener('click', onPopupClick);
    map?.off('moveend zoomend', emitBounds);
    map?.remove();
    map = null;
  });

  watch(
    () => [props.tiandituKey, props.mapType],
    () => applyBase()
  );
  watch(
    () => props.config,
    (cfg) => {
      if (!map || !cfg?.center) {
        return;
      }
      map.setView(cfg.center as [number, number], cfg.zoom || map.getZoom());
      refreshSize();
    }
  );
  let prevWs: IGeoJSON | null = null;
  let prevPo: IGeoJSON | null = null;
  let prevHz: IGeoJSON | null = null;
  watch(
    () => [props.watershed, props.section, props.prevention, props.hazard, props.visible, props.selectedWscd],
    () => {
      // 选中流域后数据变化才缩放视野；全量浏览时保持默认中国视野，
      // 避免个别越界/异常坐标把 fitBounds 拉到错误范围
      const fit =
        !!props.selectedWscd && (props.watershed !== prevWs || props.prevention !== prevPo || props.hazard !== prevHz);
      prevWs = props.watershed;
      prevPo = props.prevention;
      prevHz = props.hazard;
      redraw(fit);
    },
    { deep: true }
  );
  watch(
    () => props.categoryFilters,
    () => redraw(false),
    { deep: true }
  );
  watch(
    () => props.flyTo,
    (v) => {
      if (v && map) {
        map.flyTo([v.lat, v.lng], v.zoom || 15, { duration: 0.6 });
      }
    }
  );
  watch(
    () => props.highlight,
    (v) => {
      highlightLayer?.clearLayers();
      if (v && map) {
        L.circleMarker([v.lat, v.lng], { radius: 10, color: '#f59e0b', weight: 3, fillOpacity: 0.15 }).addTo(highlightLayer!);
      }
    }
  );

  function applyBase() {
    if (!map) {
      return;
    }
    baseLayers.forEach((l) => l.remove());
    baseLayers = [];
    const tk = resolveTk();
    const cfg = TIANDITU_LAYERS[props.mapType] || TIANDITU_LAYERS.img;
    const common = {
      subdomains: TIANDITU_SUBDOMAINS,
      maxZoom: 18,
      minZoom: 1,
      attribution: '&copy; 天地图',
    };
    baseLayers.push(L.tileLayer(cfg.base.replaceAll('{tk}', tk), common).addTo(map));
    baseLayers.push(L.tileLayer(cfg.label.replaceAll('{tk}', tk), common).addTo(map));
    refreshSize();
  }

  function typeAllowed(layer: string, type: string) {
    const cur = props.categoryFilters[layer];
    if (!cur || cur.length === 0) {
      return true;
    }
    return cur.includes(type);
  }

  /** 要素弹出窗口内容：与直接点击图层一致（防治对象/隐患要素） */
  function popupHtml(p: any, layer: 'prevention' | 'hazard'): string {
    const rows: string[] = [];
    const add = (k: string, v: any) => {
      if (v != null && v !== '') {
        rows.push(`<div class="sh-pp-row"><span>${k}</span><b>${v}</b></div>`);
      }
    };
    add('类型', p.types || p.type_label);
    add('河流', p.rvname);
    add('防护对象', p.fnm);
    add('小流域', p.wsnm);
    add('人口', p.pcount);
    add('编码', p.pid);
    const imgs = String(p.photos || '')
      .split(',')
      .map((s: string) => s.trim())
      .filter((s: string) => s.length > 0)
      .slice(0, 3);
    const imgHtml = imgs.length
      ? `<div class="sh-pp-imgs">${imgs.map((u: string) => `<img src="${getFileAccessHttpUrl(u)}" alt=""/>`).join('')}</div>`
      : '';
    // 「查看详情」按钮经容器事件委托接管点击（popup 内容为 HTML 字符串，无法直接绑事件）
    const moreBtn = p.id
      ? `<button type="button" class="sh-pp-more" data-layer="${layer}" data-id="${p.id}">查看详情</button>`
      : '';
    return `<div class="sh-pp"><div class="sh-pp-title">${p.name || p.fnm || '要素'}</div>${rows.join('')}${imgHtml}${moreBtn}</div>`;
  }

  /** 弹窗内「查看详情」按钮点击（事件委托） */
  function onPopupClick(e: MouseEvent) {
    const btn = (e.target as HTMLElement | null)?.closest?.('.sh-pp-more') as HTMLElement | null;
    if (!btn) {
      return;
    }
    const layer = btn.dataset.layer as 'prevention' | 'hazard';
    const id = btn.dataset.id || '';
    if ((layer === 'prevention' || layer === 'hazard') && id) {
      emit('openDetail', { layer, id });
    }
  }

  function redraw(fit = true) {
    if (!map) {
      return;
    }
    // 记录当前是否打开了要素弹出窗口（clearLayers 会将其关闭）
    const hadOpenPopup = !!(map as any)._popup;
    overlay.clearLayers();
    wsLayer = secLayer = poLayer = hzLayer = null;

    if (props.visible.watershed && props.watershed) {
      wsLayer = L.geoJSON(props.watershed as any, {
        style: (feat: any) => {
          const selected = props.selectedWscd && feat?.properties?.wscd === props.selectedWscd;
          return selected ? WATERSHED_SELECTED_STYLE : WATERSHED_STYLE;
        },
        onEachFeature: (feat, layer) => {
          const p = feat.properties || {};
          layer.on('click', (e: any) => {
            L.DomEvent.stopPropagation(e);
            emit('selectWatershed', {
              lybm: p.wscd || p.lybm,
              lymc: p.wsnm || p.lymc || p.wscd,
              objectCount: p.objectCount,
              population: p.population,
            });
          });
          const area = Number(p.wsarea);
          const areaTxt = Number.isFinite(area) && area > 0 ? `，${area.toFixed(1)} km²` : '';
          layer.bindTooltip(`${p.wsnm || p.wscd || '流域'}${areaTxt}`, { sticky: true });
        },
      });
      overlay.addLayer(wsLayer);
    }

    if (props.visible.section && props.section) {
      secLayer = L.geoJSON(props.section as any, {
        filter: (feat) => typeAllowed('section', feat.properties?.types || ''),
        style: (feat: any) => {
          const label = feat?.properties?.types || '';
          const color = typeColor('section', label, '#e11d48');
          return label === '纵断面' ? { color, weight: 2, dashArray: '5 4' } : { color, weight: 2 };
        },
        onEachFeature: (feat, layer) => {
          const p = feat.properties || {};
          layer.bindTooltip(`${p.name || p.pid || '断面'}（${p.types || '断面'}）`, { sticky: true });
          layer.on('click', (e: any) => {
            L.DomEvent.stopPropagation(e);
            emit('sectionClick', { props: p });
          });
        },
      });
      overlay.addLayer(secLayer);
    }

    if (props.visible.prevention && props.prevention) {
      poLayer = L.geoJSON(props.prevention as any, {
        filter: (feat) => typeAllowed('prevention', feat.properties?.types || feat.properties?.ftp || ''),
        style: (feat: any) => preventionStyle(feat?.properties?.types || feat?.properties?.ftp || ''),
        pointToLayer: (feat, latlng) => {
          const t = feat.properties?.types || feat.properties?.ftp || '';
          return L.circleMarker(latlng, {
            radius: 6,
            color: '#fff',
            weight: 1,
            fillColor: typeColor('prevention', t),
            fillOpacity: 0.9,
          });
        },
        onEachFeature: (feat, layer) => {
          const p = feat.properties || {};
          layer.bindPopup(() => popupHtml(p, 'prevention'), { maxWidth: 280, className: 'sh-pp-popup' });
          layer.bindTooltip(p.name || p.fnm || '防治对象', { sticky: true });
          layer.on('click', (e: any) => {
            L.DomEvent.stopPropagation(e);
            activePopup = { layer: 'prevention', id: String(p.id) };
            emit('featureClick', { layer: 'prevention', props: p });
          });
        },
      });
      overlay.addLayer(poLayer);
    }

    if (props.visible.hazard && props.hazard) {
      hzLayer = L.geoJSON(props.hazard as any, {
        filter: (feat) => typeAllowed('hazard', feat.properties?.types || feat.properties?.type_label || ''),
        pointToLayer: (feat, latlng) => {
          const t = feat.properties?.types || feat.properties?.type_label || '';
          return L.circleMarker(latlng, {
            radius: 6,
            color: '#fff',
            weight: 1,
            fillColor: typeColor('hazard', t),
            fillOpacity: 0.9,
          });
        },
        onEachFeature: (feat, layer) => {
          const p = feat.properties || {};
          layer.bindPopup(() => popupHtml(p, 'hazard'), { maxWidth: 280, className: 'sh-pp-popup' });
          layer.bindTooltip(p.name || '隐患要素', { sticky: true });
          layer.on('click', (e: any) => {
            L.DomEvent.stopPropagation(e);
            activePopup = { layer: 'hazard', id: String(p.id) };
            emit('featureClick', { layer: 'hazard', props: p });
          });
        },
      });
      overlay.addLayer(hzLayer);
    }

    if (fit) {
      fitIfNeeded();
    }
    // 恢复重建前打开的弹出窗口（如断面图层按视野增量加载触发的重绘）
    if (hadOpenPopup && activePopup) {
      if (!openPopupNow(activePopup.layer, activePopup.id, false)) {
        activePopup = null;
      }
    }
  }

  function fitIfNeeded() {
    if (!map) {
      return;
    }
    try {
      // 逐图层取 bounds：空图层的 getBounds 会抛错或无效，不能依赖 featureGroup 整体 getBounds
      let bounds: L.LatLngBounds | null = null;
      for (const layer of [wsLayer, secLayer, poLayer, hzLayer]) {
        if (!layer) {
          continue;
        }
        try {
          const b = layer.getBounds();
          if (b && b.isValid()) {
            bounds = bounds ? bounds.extend(b) : b;
          }
        } catch {
          // 空图层忽略
        }
      }
      if (bounds) {
        map.fitBounds(bounds.pad(0.12), { maxZoom: 14, animate: true });
      }
    } catch {
      // 无要素时忽略
    }
  }

  function zoomIn() {
    map?.zoomIn();
  }

  function zoomOut() {
    map?.zoomOut();
  }

  /** 在图层中定位要素并打开其弹出窗口；fly 控制是否飞行居中 */
  function openPopupNow(layer: 'prevention' | 'hazard', id: string, fly: boolean): boolean {
    const src = layer === 'prevention' ? poLayer : hzLayer;
    if (!map || !src) {
      return false;
    }
    let target: any = null;
    src.eachLayer((l: any) => {
      if (!target && String(l?.feature?.properties?.id) === String(id)) {
        target = l;
      }
    });
    if (!target) {
      return false;
    }
    const latlng = target.getLatLng ? target.getLatLng() : target.getBounds?.()?.getCenter();
    if (latlng && fly) {
      map.flyTo(latlng, Math.max(map.getZoom(), 15), { duration: 0.6 });
    }
    // 点要素自带 _latlng；线/面必须显式传入坐标，否则 popup 无法定位
    latlng ? target.openPopup(latlng) : target.openPopup();
    return true;
  }

  /**
   * 打开要素在地图上的弹出窗口（点/面要素自动居中）。
   * 图层尚未加载完成时自动短暂重试。
   * @param layer 图层名（prevention / hazard）
   * @param id 要素 id（properties.id）
   */
  function openFeaturePopup(layer: 'prevention' | 'hazard', id: string | number) {
    activePopup = { layer, id: String(id) };
    if (!openPopupNow(layer, String(id), true)) {
      [800, 2000, 3500, 5000, 8000].forEach((ms) =>
        setTimeout(() => {
          if (activePopup?.layer === layer && activePopup.id === String(id)) {
            openPopupNow(layer, String(id), true);
          }
        }, ms)
      );
    }
  }

  defineExpose({ fit: fitIfNeeded, getMap: () => map, zoomIn, zoomOut, openFeaturePopup });
</script>

<style lang="less" scoped>
  .map-canvas {
    position: relative;
    width: 100%;
    height: 100%;
    min-height: 360px;

    .map-el {
      width: 100%;
      height: 100%;
      min-height: 360px;
      background: #dbe4ee;
    }

    :deep(.leaflet-container) {
      width: 100%;
      height: 100%;
      font-size: 12px;
    }

    :deep(.leaflet-pane img),
    :deep(.leaflet-tile),
    :deep(.leaflet-marker-icon) {
      max-width: none !important;
      max-height: none !important;
    }
  }
</style>

<style lang="less">
  /* 点击矢量要素后浏览器默认焦点外框（矩形描边）——Leaflet 容器内一律去除 */
  .leaflet-container svg:focus,
  .leaflet-container svg:focus-visible,
  .leaflet-container g:focus,
  .leaflet-container g:focus-visible,
  .leaflet-container path:focus,
  .leaflet-container path:focus-visible,
  .leaflet-container .leaflet-interactive:focus {
    outline: none !important;
  }

  /* 要素弹出窗口（Leaflet popup 内容，需全局作用域） */
  .sh-pp-popup .leaflet-popup-content {
    margin: 10px 12px;
  }

  .sh-pp {
    min-width: 180px;

    .sh-pp-title {
      font-size: 13px;
      font-weight: 600;
      color: #0f172a;
      margin-bottom: 6px;
      word-break: break-all;
    }

    .sh-pp-row {
      display: flex;
      gap: 8px;
      font-size: 12px;
      line-height: 20px;

      span {
        flex: none;
        width: 56px;
        color: #94a3b8;
      }

      b {
        flex: 1;
        min-width: 0;
        color: #334155;
        font-weight: 500;
        word-break: break-all;
      }
    }

    .sh-pp-imgs {
      display: flex;
      gap: 4px;
      margin-top: 6px;

      img {
        width: 72px;
        height: 52px;
        object-fit: cover;
        border-radius: 4px;
      }
    }

    .sh-pp-more {
      display: block;
      width: 100%;
      margin-top: 8px;
      padding: 4px 0;
      border: 1px solid #bfdbfe;
      border-radius: 6px;
      background: #eff6ff;
      color: #2563eb;
      font-size: 12px;
      cursor: pointer;

      &:hover {
        background: #dbeafe;
      }
    }
  }
</style>
