export const PREVENTION_TYPE_COLORS: Record<string, string> = {
  村落: '#3b82f6',
  企业: '#10b981',
  事业: '#8b5cf6',
  集镇: '#f59e0b',
  景区: '#06b6d4',
  厂矿: '#84cc16',
  城镇: '#ef4444',
  村庄: '#60a5fa',
  乡镇: '#a16207',
  其他: '#64748b',
};

export const HAZARD_TYPE_COLORS: Record<string, string> = {
  跨沟道路: '#f97316',
  跨沟桥涵: '#ef4444',
  '塘（堰）坝': '#0ea5e9',
  沟滩占地: '#8b5cf6',
  急弯: '#f59e0b',
  束窄: '#eab308',
  低洼地: '#06b6d4',
  临河滑坡: '#dc2626',
  泥石流: '#b91c1c',
  溃决: '#991b1b',
  壅水: '#0284c7',
  顶托: '#14b8a6',
  改道: '#a855f7',
  漫流: '#6366f1',
  潜在滑坡: '#e11d48',
  多支齐汇: '#7c3aed',
};

/** 断面线样式：A 横断面实线，B 纵断面虚线 */
export const SECTION_TYPE_COLORS: Record<string, string> = {
  横断面: '#e11d48',
  纵断面: '#2563eb',
};

export const SECTION_STYLE_A = {
  color: SECTION_TYPE_COLORS['横断面'],
  weight: 2,
};

export const SECTION_STYLE_B = {
  color: SECTION_TYPE_COLORS['纵断面'],
  weight: 2,
  dashArray: '5 4',
};

export const WATERSHED_STYLE = {
  color: '#2563eb',
  weight: 2,
  dashArray: undefined as string | undefined,
  fillColor: '#3b82f6',
  fillOpacity: 0.1,
};

export const WATERSHED_SELECTED_STYLE = {
  color: '#f59e0b',
  weight: 3,
  dashArray: undefined as string | undefined,
  fillColor: '#f59e0b',
  fillOpacity: 0.22,
};

/** 防治对象面样式（按类型描边 + 淡填充） */
export function preventionStyle(type: string) {
  return {
    color: typeColor('prevention', type),
    weight: 2,
    fillColor: typeColor('prevention', type),
    fillOpacity: 0.35,
  };
}

export function typeColor(layer: 'prevention' | 'hazard' | 'section', type: string, fallback = '#64748b') {
  const map = layer === 'prevention' ? PREVENTION_TYPE_COLORS : layer === 'section' ? SECTION_TYPE_COLORS : HAZARD_TYPE_COLORS;
  return map[type] || fallback;
}

export const DEFAULT_TIANDITU_KEY = 'fced2cd1905b5b42d4167603c6ba32e2';

export const TIANDITU_LAYERS: Record<string, { name: string; base: string; label: string }> = {
  img: {
    name: '影像',
    base: 'https://t{s}.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={tk}',
    label:
      'https://t{s}.tianditu.gov.cn/cia_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=cia&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={tk}',
  },
  vec: {
    name: '矢量',
    base: 'https://t{s}.tianditu.gov.cn/vec_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=vec&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={tk}',
    label:
      'https://t{s}.tianditu.gov.cn/cva_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=cva&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={tk}',
  },
  ter: {
    name: '地形',
    base: 'https://t{s}.tianditu.gov.cn/ter_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ter&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={tk}',
    label:
      'https://t{s}.tianditu.gov.cn/cta_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=cta&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&tk={tk}',
  },
};
