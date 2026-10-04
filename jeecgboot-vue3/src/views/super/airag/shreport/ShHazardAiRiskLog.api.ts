import { defHttp } from '/@/utils/http/axios';

export interface IAiRiskLogRow {
  id: number;
  hazard_id?: string;
  pid?: string;
  hazard_name?: string;
  hz_type?: string;
  rvname?: string;
  fnm?: string;
  wsnm?: string;
  username?: string;
  realname?: string;
  model_name?: string;
  photo_count?: number;
  photo_total?: number;
  photo_labels?: string;
  /** 本次送入模型的照片：[{label, kind: disk|upload, path}] */
  photo_refs?: string;
  used_comment?: number | boolean;
  degraded?: number | boolean;
  status?: string;
  error_message?: string;
  input_summary?: string;
  result_text?: string;
  cost_ms?: number;
  create_time?: string;
}

export interface IAiRiskLogPage {
  total: number;
  pageNo: number;
  pageSize: number;
  data: IAiRiskLogRow[];
}

export const listAiRiskLogs = (params: { pageNo?: number; pageSize?: number; search?: string; status?: string }) =>
  defHttp.get<IAiRiskLogPage>({ url: '/sh/report/map/ai-risk/logs', params });

export const getAiRiskLog = (id: number | string) => defHttp.get<IAiRiskLogRow>({ url: `/sh/report/map/ai-risk/logs/${id}` });
