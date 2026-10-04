import { defHttp } from '/@/utils/http/axios';

enum Api {
  config = '/sh/report/map/config',
  filters = '/sh/report/map/filters',
  geojson = '/sh/report/map/geojson',
  prevention = '/sh/report/map/prevention',
  hazards = '/sh/report/map/hazards',
  section = '/sh/report/map/section',
  stats = '/sh/report/map/stats',
}

export type MapLayer = 'watershed' | 'section' | 'prevention' | 'hazard';

export interface IMapConfig {
  tiandituKey: string;
  center: [number, number];
  zoom: number;
}

export interface IWatershedOpt {
  wscd: string;
  wsnm: string;
  aliasName?: string;
  wsarea?: number;
  objectCount?: number;
  population?: number;
  bbox?: { minLng: number; minLat: number; maxLng: number; maxLat: number } | null;
}

export interface IAdminOpt {
  adcd: string;
  name: string;
  level: number;
}

export interface IMapFilters {
  watersheds: IWatershedOpt[];
  counties: IAdminOpt[];
  towns: IAdminOpt[];
}

export interface IGeoJSON {
  type: 'FeatureCollection';
  features: any[];
}

export interface IPaged<T = any> {
  total: number;
  pageNo: number;
  pageSize: number;
  data: T[];
}

export interface IMapStats {
  watershedCount: number;
  sectionCount: number;
  preventionCount: number;
  hazardCount: number;
  crossPointCount?: number;
  longPointCount?: number;
  surveyObjectCount?: number;
  sectionByType?: Record<string, number>;
  preventionByType?: Record<string, number>;
  hazardByType?: Record<string, number>;
}

export interface ISectionProfile {
  pid: string;
  secType?: string;
  secTypeLabel?: string;
  secName?: string;
  rvname?: string;
  wscd?: string;
  wsnm?: string;
  kind: 'cross' | 'long' | 'none';
  points: {
    point_no?: number;
    point_desc?: string;
    dist?: number;
    elev?: number;
    bed_elev?: number;
    water_elev?: number;
    roughness?: number;
    lgtd?: number;
    lttd?: number;
  }[];
}

export const getMapConfig = () => defHttp.get<IMapConfig>({ url: Api.config });

export const getMapFilters = (countyAdcd?: string) => defHttp.get<IMapFilters>({ url: Api.filters, params: { countyAdcd: countyAdcd || undefined } });

export const getMapGeojson = (layer: MapLayer, params?: { wscd?: string; adcd?: string; types?: string; bbox?: string }) =>
  defHttp.get<IGeoJSON>({ url: Api.geojson, params: { layer, ...params } });

export const getPreventionList = (params: { pageNo?: number; pageSize?: number; search?: string; wscd?: string; adcd?: string }) =>
  defHttp.get<IPaged>({ url: Api.prevention, params });

export const getPreventionDetail = (id: string) => defHttp.get<any>({ url: `${Api.prevention}/${id}` });

export const getHazardList = (params: { pageNo?: number; pageSize?: number; search?: string; wscd?: string; adcd?: string; types?: string }) =>
  defHttp.get<IPaged>({ url: Api.hazards, params });

export const getHazardDetail = (id: string) => defHttp.get<any>({ url: `${Api.hazards}/${id}` });

/** 按隐患点编码取详情，字段与地图弹窗预览一致 */
export const getHazardByCode = (pid: string) => defHttp.get<any>({ url: '/sh/report/map/hazard', params: { pid } });

/** 根据现场照片和外业备注识别风险，不回写调查数据 */
export const analyzeHazardRisk = (id: string) =>
  defHttp.post<{
    text: string;
    photoCount: number;
    photoTotal: number;
    photoLabels: string[];
    usedComment: boolean;
    modelName: string;
    degraded: boolean;
    logId?: number;
  }>({ url: `${Api.hazards}/${id}/ai-risk`, timeout: 3 * 60 * 1000 }, { errorMessageMode: 'none', successMessageMode: 'none' });

export const getSectionDetail = (id: string) => defHttp.get<any>({ url: `${Api.section}/${id}` });

export const getSectionProfile = (pid: string) => defHttp.get<ISectionProfile>({ url: `${Api.section}/profile`, params: { pid } });

export const getMapStats = () => defHttp.get<IMapStats>({ url: Api.stats });
