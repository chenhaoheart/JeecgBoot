import { defHttp } from '/@/utils/http/axios';
import { getToken } from '/@/utils/auth';
import { useGlobSetting } from '/@/hooks/setting';

enum Api {
  chapterList = '/sh/report/chapter/list',
  chapterSave = '/sh/report/chapter/save',
  chapterAudit = '/sh/report/chapter/audit',
  chapterRegenerate = '/sh/report/chapter/regenerate',
  chapterDownload = '/sh/report/chapter/download',
  watersheds = '/sh/report/chapter/watersheds',
  qcIssues = '/sh/report/chapter/qc/issues',
  createConversation = '/sh/report/agent/conversation',
  chatSend = '/sh/report/agent/chat/send',
  chatReceive = '/sh/report/agent/receive',
  context = '/sh/report/agent/context',
  bindWatershed = '/sh/report/agent/bindWatershed',
  bindModel = '/sh/report/agent/bindModel',
  models = '/sh/report/agent/models',
  cancel = '/sh/report/agent/cancel',
  outlineConfirm = '/sh/report/agent/outline/confirm',
  sessions = '/sh/report/agent/sessions',
  sessionsSave = '/sh/report/agent/sessions/save',
  sessionsDelete = '/sh/report/agent/sessions/delete',
  graphSchema = '/sh/report/graph/schema',
  graphTrace = '/sh/report/graph/trace',
  graphDemo = '/sh/report/graph/demo',
}

export interface IChapter {
  id: string;
  batchNo: string;
  lybm?: string;
  lymc?: string;
  chapterNo: number;
  chapterName: string;
  content: string;
  contentPrev?: string;
  status: string;
  auditNote: string;
  /** 生成失败原因（失败状态下有值） */
  failReason?: string;
  /** 生成所用流程版本（流程名@设计更新时间） */
  flowVer?: string;
  /** 本章从开始生成到最终完成的耗时（毫秒），未记录时为空 */
  genCostMs?: number | null;
  updateTime: string;
}

export interface IWatershed {
  wscd: string;
  wsnm: string;
  /** 以下为 /watersheds 返回的中文键进度统计（按批次） */
  对象数?: number;
  人口?: number;
  已生成章节数?: number;
  已通过章节数?: number;
}

export interface IQcIssue {
  id: string;
  batchNo: string;
  lybm?: string;
  chapterNo: number;
  level: string; // BLOCK 阻断 / HINT 提示
  type: string;
  message: string;
}

export interface IOutlineChapter {
  chapterNo: number;
  chapterName: string;
  sections: string[];
  tables: string[];
  note?: string;
}

export interface IAgentModel {
  id: string;
  name: string;
  provider?: string;
  modelName?: string;
  modelType?: string;
}

/** 会话累计 token 用量（后端 usage 节点） */
export interface IUsageItem {
  calls: number;
  inputTokens: number;
  outputTokens: number;
}

export interface IUsage {
  calls: number;
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
  /** 有调用未返回真实用量、按字符数估算时为 true */
  estimated?: boolean;
  scenes?: Record<string, IUsageItem>;
  chapters?: Record<string, IUsageItem>;
  lastModel?: string;
}

/** 后端 list 返回下划线键，统一归一化为前端驼峰字段 */
function normalizeChapter(r: any): IChapter {
  return {
    id: r.id,
    batchNo: r.batchNo ?? r.batch_no,
    lybm: r.lybm,
    lymc: r.lymc,
    chapterNo: Number(r.chapterNo ?? r.chapter_no),
    chapterName: r.chapterName ?? r.chapter_name,
    content: r.content || '',
    contentPrev: r.contentPrev ?? r.content_prev ?? '',
    status: r.status,
    auditNote: r.auditNote ?? r.audit_note ?? '',
    failReason: r.failReason ?? r.fail_reason ?? '',
    flowVer: r.flowVer ?? r.flow_ver ?? '',
    // PG 会把未加引号的别名 genCostMs 折成 gencostms，三种键都认
    genCostMs: r.genCostMs ?? r.gen_cost_ms ?? r.gencostms ?? null,
    updateTime: r.updateTime ?? r.update_time ?? '',
  };
}

/** 查询批次（+流域）下章节列表 */
export const getChapterList = (batchNo: string, lybm?: string) =>
  defHttp
    .get<any[]>({ url: Api.chapterList, params: { batchNo, lybm: lybm || undefined } })
    .then((rows) => (rows || []).map(normalizeChapter));

/** 在线编辑保存章节（保存后回到待审核） */
export const saveChapter = (params: { batchNo: string; lybm: string; lymc?: string; chapterNo: number; content: string }) =>
  defHttp.post({ url: Api.chapterSave, params });

/** 审核：通过 / 驳回 */
export const auditChapter = (params: { id: string; status: string; auditNote?: string }) =>
  defHttp.post({ url: Api.chapterAudit, params });

/** 单章重新生成（异步执行，必须携带流域编码；modelId 为页面选中的模型） */
export const regenerateChapter = (params: {
  batchNo: string;
  lybm: string;
  lymc?: string;
  chapterNo: number;
  auditNote?: string;
  modelId?: string;
}) => defHttp.post({ url: Api.chapterRegenerate, params });

/** 合并导出 Word（可指定章节号；不传则导出当前已有正文章节），返回 Blob */
export const downloadReport = (batchNo: string, lybm: string, chapterNos?: number[]): Promise<Blob> =>
  defHttp.get(
    {
      url: Api.chapterDownload,
      params: { batchNo, lybm, chapterNos: chapterNos?.length ? chapterNos.join(',') : undefined },
      responseType: 'blob',
    },
    { isTransformResponse: false }
  );

/** 流域清单（含批次进度） */
export const getWatersheds = (batchNo?: string) =>
  defHttp.get<IWatershed[]>({ url: Api.watersheds, params: { batchNo: batchNo || undefined } });

/** 查询章节质检问题 */
export const getQcIssues = (batchNo: string, lybm?: string) =>
  defHttp.get<any[]>({ url: Api.qcIssues, params: { batchNo, lybm: lybm || undefined } });

/** 创建助手会话 */
export const createConversation = () =>
  defHttp.post<{ conversationId: string; batchNo: string }>({ url: Api.createConversation });

/** 获取会话上下文（用于切会话/刷新后恢复流域绑定与模型选择，含 token 用量） */
export const getContext = (conversationId: string) =>
  defHttp.get<{ batchNo?: string; lybm?: string; lymc?: string; wsmc?: string; modelId?: string; modelLabel?: string; usage?: IUsage }>({
    url: Api.context,
    params: { conversationId },
  });

/** 会话可用模型列表（已激活） */
export const getAgentModels = () => defHttp.get<IAgentModel[]>({ url: Api.models });

/** 绑定会话使用的模型（modelId 传空串恢复系统默认） */
export const bindModel = (conversationId: string, modelId: string, modelLabel?: string) =>
  defHttp.post({ url: Api.bindModel, params: { conversationId, modelId, modelLabel } });

/** 显式绑定流域（面板选择/消歧点选后持久化到会话） */
export const bindWatershed = (conversationId: string, lybm: string, lymc: string) =>
  defHttp.post({ url: Api.bindWatershed, params: { conversationId, lybm, lymc } });

/** 请求停止正在执行的生成 */
export const cancelGenerate = (conversationId: string) =>
  defHttp.post({ url: `${Api.cancel}?conversationId=${encodeURIComponent(conversationId)}`, params: { conversationId } }, { errorMessageMode: 'none', successMessageMode: 'none' });

/** 服务端会话持久化（跨浏览器恢复）：当前用户的会话列表（含消息记录） */
export const listServerSessions = () =>
  defHttp.get<ISessionRecord[]>({ url: Api.sessions }, { errorMessageMode: 'none', successMessageMode: 'none' });

/** 幂等保存一个会话 */
export const saveServerSession = (session: ISessionRecord) =>
  defHttp.post({ url: Api.sessionsSave, params: session }, { errorMessageMode: 'none', successMessageMode: 'none' });

/** 删除一个持久化会话 */
export const deleteServerSession = (conversationId: string) =>
  defHttp.post({ url: Api.sessionsDelete, params: { conversationId } }, { errorMessageMode: 'none', successMessageMode: 'none' });

/** 与后端会话表对应的结构（messages 为聊天消息数组） */
export interface ISessionRecord {
  conversationId: string;
  batchNo?: string;
  lybm?: string;
  lymc?: string;
  modelId?: string;
  modelLabel?: string;
  title?: string;
  messages?: any[];
  updateTime?: number;
  /** 归档会话：主列表隐藏，仅归档列表可见，可恢复 */
  archived?: boolean;
}

/** 发送对话消息，返回 SSE 流（原生 fetch；vite 代理下 SSE 需直读流） */
export const sendChatMessage = (
  conversationId: string,
  content: string,
  batchNo?: string,
  signal?: AbortSignal,
  requestId?: string
): Promise<Response> => {
  const base = useGlobSetting().apiUrl || '';
  const params = [`conversationId=${conversationId}`, batchNo ? `batchNo=${batchNo}` : '', requestId ? `requestId=${requestId}` : '']
    .filter(Boolean)
    .join('&');
  return fetch(`${base}${Api.chatSend}?${params}`, {
    method: 'POST',
    cache: 'no-store',
    signal,
    headers: {
      'Content-Type': 'application/json;charset=UTF-8',
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
      'X-Access-Token': getToken() || '',
    },
    body: JSON.stringify({ content }),
  }).then((r) => {
    if (!r.ok) {
      throw new Error('HTTP ' + r.status);
    }
    return r.body;
  });
};

/** 确认大纲并放行被暂存的生成动作，返回 SSE 流 */
export const confirmOutlineMessage = (
  conversationId: string,
  outline: IOutlineChapter[],
  signal?: AbortSignal,
  requestId?: string,
  userMessage?: string
): Promise<Response> => {
  const base = useGlobSetting().apiUrl || '';
  const params = [`conversationId=${conversationId}`, requestId ? `requestId=${requestId}` : '', userMessage ? `userMessage=${encodeURIComponent(userMessage)}` : '']
    .filter(Boolean)
    .join('&');
  return fetch(`${base}${Api.outlineConfirm}?${params}`, {
    method: 'POST',
    cache: 'no-store',
    signal,
    headers: {
      'Content-Type': 'application/json;charset=UTF-8',
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
      'X-Access-Token': getToken() || '',
    },
    body: JSON.stringify({ outline }),
  }).then((r) => {
    if (!r.ok) {
      throw new Error('HTTP ' + r.status);
    }
    return r.body;
  });
};

/** 断线重连：按 requestId 重新接入同一轮 SSE，服务端补发错过的消息后继续实时推送 */
export const reconnectStream = (requestId: string, signal?: AbortSignal): Promise<ReadableStream<Uint8Array>> => {
  const base = useGlobSetting().apiUrl || '';
  return fetch(`${base}${Api.chatReceive}/${encodeURIComponent(requestId)}`, {
    method: 'GET',
    cache: 'no-store',
    signal,
    headers: {
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
      'X-Access-Token': getToken() || '',
    },
  }).then((r) => {
    if (!r.ok) {
      throw new Error('HTTP ' + r.status);
    }
    if (!r.body) {
      throw new Error('无返回流');
    }
    return r.body;
  });
};

export interface IGraphNode {
  id: string;
  label: string;
  desc?: string;
  x: number;
  y: number;
}

export interface IGraphEdge {
  from: string;
  to: string;
  label?: string;
  kind?: string;
}

export interface IGraphSchema {
  nodes: IGraphNode[];
  edges: IGraphEdge[];
}

export interface IGraphTraceEvent {
  node: string;
  phase: string;
  ts: number;
  retryCount?: number;
  targetChapters?: number[];
}

export interface IGraphTrace {
  batchNo?: string;
  scenario?: string;
  status?: string;
  currentNode?: string;
  retryCount?: number;
  maxRetry?: number;
  targetChapters?: number[];
  success?: boolean;
  cancelled?: boolean;
  fullReport?: boolean;
  events?: IGraphTraceEvent[];
}

export const getGraphSchema = () =>
  defHttp.get<IGraphSchema>({ url: Api.graphSchema }, { errorMessageMode: 'none' });

export const getGraphTrace = (batchNo?: string) =>
  defHttp.get<IGraphTrace>({ url: Api.graphTrace, params: { batchNo: batchNo || undefined } }, { errorMessageMode: 'none' });

/** 假节点跑一遍编排图，返回 SSE 流 */
export const runGraphDemo = (scenario: string): Promise<ReadableStream<Uint8Array>> => {
  const base = useGlobSetting().apiUrl || '';
  return fetch(`${base}${Api.graphDemo}?scenario=${encodeURIComponent(scenario || 'pass')}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json;charset=UTF-8', 'X-Access-Token': getToken() || '' },
  }).then((r) => {
    if (!r.ok) {
      throw new Error('HTTP ' + r.status);
    }
    if (!r.body) {
      throw new Error('无返回流');
    }
    return r.body;
  });
};
