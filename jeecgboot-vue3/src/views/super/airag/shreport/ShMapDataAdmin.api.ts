import { defHttp } from '/@/utils/http/axios';
import { useGlobSetting } from '/@/hooks/setting';

enum Api {
  datasets = '/sh/report/map/admin/datasets',
  years = '/sh/report/map/admin/years',
  facets = '/sh/report/map/admin/facets',
  list = '/sh/report/map/admin/list',
  rowUpdate = '/sh/report/map/admin/row/update',
  rowDelete = '/sh/report/map/admin/row/delete',
  importData = '/sh/report/map/admin/import',
  geom = '/sh/report/map/admin/geom',
  geomUpdate = '/sh/report/map/admin/geom/update',
  geomByPid = '/sh/report/map/admin/geom/by-pid',
  fileScan = '/sh/report/map/file/scan',
  fileList = '/sh/report/map/file/list',
  fileFacets = '/sh/report/map/file/facets',
  fileByPid = '/sh/report/map/file/by-pid',
  fileBind = '/sh/report/map/file/bind',
}

export interface IColConf {
  col: string;
  label: string;
  numeric: boolean;
}

export interface IDataset {
  key: string;
  label: string;
  hasGeom: boolean;
  columns: IColConf[];
}

export interface IYearStat {
  year: number;
  count: number;
}

/** 筛选项：政区（县/乡镇，乡镇带所属县代码）与小流域 */
export interface IFacets {
  counties: { adcd: string; name: string }[];
  towns: { adcd: string; name: string; county_adcd: string }[];
  watersheds: { cd: string; nm: string }[];
}

export interface IRowPage {
  total: number;
  pageNo: number;
  pageSize: number;
  data: Recordable[];
}

export const getDatasets = () => defHttp.get<IDataset[]>({ url: Api.datasets });

export const getYears = (ds: string) => defHttp.get<IYearStat[]>({ url: Api.years, params: { ds } });

/** 数据集筛选项（调查成果：政区县/乡镇与流域去重清单） */
export const getFacets = (ds: string) => defHttp.get<IFacets>({ url: Api.facets, params: { ds } });

/** year 传 0 表示未标注年份，不传表示全部；countyAdcd/townAdcd/wscd 为政区与流域过滤 */
export const getRows = (params: {
  ds: string;
  year?: number;
  search?: string;
  countyAdcd?: string;
  townAdcd?: string;
  wscd?: string;
  pageNo: number;
  pageSize: number;
}) => defHttp.get<IRowPage>({ url: Api.list, params });

export const updateRow = (ds: string, id: number, data: Recordable) =>
  defHttp.post({ url: Api.rowUpdate, params: { ds, id, data } });

export const deleteRows = (ds: string, ids: number[]) =>
  defHttp.post({ url: Api.rowDelete, params: { ds, ids } }, { successMessageMode: 'none' });

/** 读取行空间几何（GeoJSON geometry，无几何时 geometry 为 null） */
export const getGeom = (ds: string, id: number) =>
  defHttp.get<{ ds: string; id: number; geometry: any }>({ url: Api.geom, params: { ds, id } });

/** 保存行空间几何（GeoJSON geometry；隐患点同步回写经纬度） */
export const updateGeom = (ds: string, id: number, geometry: any) =>
  defHttp.post({ url: Api.geomUpdate, params: { ds, id, geometry } });

/** 按编码查空间图层关联几何（如调查成果按 15 位政区编码关联防治对象面） */
export const getGeomByPid = (ds: string, pid: string) =>
  defHttp.get<{ ds: string; pid: string; id: number | null; geometry: any }>({
    url: Api.geomByPid,
    params: { ds, pid },
  });

/** 通用文件上传（返回 Result，message 为相对路径） */
export const uploadCommonFile = (file: File) =>
  defHttp.uploadFile<any>({ url: '/sys/common/upload' }, { name: 'file', file, data: { biz: 'shreport' } });

// ---------------------------------------------------------------- 文件对象管理（磁盘照片/视频索引）

export const scanFiles = () => defHttp.post({ url: Api.fileScan });

export const getFileList = (params: Recordable) => defHttp.get({ url: Api.fileList, params });

export const getFileFacets = () =>
  defHttp.get<{ categories: string[]; wsnms: string[]; counties: { adcd: string; name: string }[] }>({
    url: Api.fileFacets,
  });

export const getFilesByPid = (pid: string) => defHttp.get<any[]>({ url: Api.fileByPid, params: { pid } });

export const bindFile = (id: number, pid: string | null) =>
  defHttp.post({ url: Api.fileBind, params: { id, pid } }, { successMessageMode: 'none' });

/** 文件预览直链（shiro anon 放行，img/video 可直接引用） */
export const filePreviewUrl = (relativePath: string) => {
  const { apiUrl } = useGlobSetting();
  return `${apiUrl}/sh/report/map/file/preview?path=${encodeURIComponent(relativePath)}`;
};

export interface IKgProp {
  label: string;
  value: string;
}

export interface IKgNode {
  id: string;
  name: string;
  category: string;
  categoryKey: string;
  kind: 'type' | 'entity';
  symbolSize: number;
  count?: number;
  ds?: string | null;
  rowId?: number | null;
  ds2?: string | null;
  rowId2?: number | null;
  wscd?: string | null;
  code?: string | null;
  blurb?: string | null;
  highlight?: boolean;
  props?: IKgProp[];
}

export interface IKgLink {
  source: string;
  target: string;
  label: string;
  count?: number;
}

/** 山洪知识图谱：不传 wscd 为关系总览，传入则展开该小流域 */
export const getKnowledgeGraph = (params: { wscd?: string; q?: string; year?: number }) =>
  defHttp.get<IKgGraph>({ url: '/sh/report/map/admin/kg', params }, { errorMessageMode: 'none' });

export interface IKgGraph {
  mode: 'overview' | 'focus' | 'search';
  hint?: string;
  wscd?: string | null;
  wsnm?: string | null;
  nodes: IKgNode[];
  links: IKgLink[];
  categories: { key: string; name: string }[];
  watersheds: { cd: string; nm: string }[];
  years: IYearStat[];
  truncated?: string[];
}

export const getRow = (ds: string, id: number) =>
  defHttp.get<Recordable>({ url: '/sh/report/map/admin/row', params: { ds, id } });

/** Excel 导入（按调查年份入库；mode=append 追加 / replace 覆盖该年份） */
export const importExcel = (params: { ds: string; year: number; mode: string; file: File }) => {
  const { ds, year, mode, file } = params;
  return defHttp.uploadFile(
    { url: Api.importData },
    { name: 'file', file, data: { ds, year, mode } }
  );
};
