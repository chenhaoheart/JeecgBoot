export interface IKbSource {
  docName: string;
  score?: number;
}

export interface IChatMsg {
  role: 'user' | 'assistant';
  content: string;
  /** 消息身份：多窗口合并时只认这一条，避免用旧列表整段替换 */
  id?: string;
  /** 消息时间（毫秒）。同步只把较新的消息并入，不会用旧消息删掉新消息 */
  time?: number;
  choices?: { lybm: string; lymc: string }[];
  /** 知识库问答返回的参考来源（一期调查成果文档名），由 SSE kb_sources 事件附加 */
  sources?: IKbSource[];
  /** 流式回复中的占位标记，流结束后删除 */
  __streaming?: boolean;
  /** 章节生成进度状态行（由 SSE progress 事件生成并就地更新），不是普通回复 */
  __progress?: boolean;
}

export interface ISession {
  conversationId: string;
  batchNo: string;
  lybm?: string;
  lymc?: string;
  modelId?: string;
  modelLabel?: string;
  title: string;
  messages: IChatMsg[];
  updateTime: number;
  generating?: boolean;
  /** 归档会话：主列表隐藏，可恢复；生成完报告的会话建议归档留存 */
  archived?: boolean;
}

/** 给新消息打上稳定 id + 时间；已有 id/time 的保持不变，便于多窗口按条合并 */
export function stampMsg(msg: IChatMsg, now = Date.now()): IChatMsg {
  const time = msg.time || now;
  return {
    ...msg,
    time,
    id: msg.id || `m-${time}-${Math.random().toString(36).slice(2, 9)}`,
  };
}

/**
 * 无 id 历史消息的稳定 id：同内容在任意窗口/服务端算出同一个值，保证合并键一致。
 * 必须与服务端 ShReportSessionMessageMerge.stableId 的算法保持一致（djb2 + base36）。
 * 只用于合并时补齐历史消息；流式中的消息内容还在增长，不能用内容哈希。
 */
export function stableMsgId(m: { role?: string; content?: string }): string {
  const s = `${m.role || ''}\n${m.content || ''}`;
  let h = 5381;
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  }
  return `m-h${(h >>> 0).toString(36)}`;
}

function progressKey(content: string): string {
  const no = /第(\d+)章/.exec(content || '')?.[1];
  return no ? `pg:${no}` : `pg:${(content || '').slice(0, 40)}`;
}

/** 同一条消息的合并键：优先 id；进度行按章节；旧数据用 role+正文指纹（不含随机数，避免重复） */
export function messageKey(m: IChatMsg): string {
  if (m.id) {
    return `id:${m.id}`;
  }
  if (m.__progress) {
    return progressKey(m.content || '');
  }
  return `fp:${m.role}:${(m.content || '').slice(0, 200)}`;
}

function pickMsg(a: IChatMsg, b: IChatMsg): IChatMsg {
  const aLen = (a.content || '').length;
  const bLen = (b.content || '').length;
  const aTime = a.time || 0;
  const bTime = b.time || 0;
  const newer = bTime !== aTime ? (bTime > aTime ? b : a) : bLen >= aLen ? b : a;
  const older = newer === b ? a : b;
  const out = {
    ...older,
    ...newer,
    content: (newer.content || '').length >= (older.content || '').length ? newer.content : older.content,
    sources: newer.sources?.length ? newer.sources : older.sources,
    choices: newer.choices?.length ? newer.choices : older.choices,
    time: Math.max(aTime, bTime) || newer.time || older.time,
    id: newer.id || older.id,
  };
  // 流式占位标记只属于产生它的窗口：流结束后合并时旧副本上的 __streaming 必须清掉，
  // 否则其他窗口的这条气泡会永远显示闪烁光标
  if (!(newer as any).__streaming) {
    delete (out as any).__streaming;
  }
  return out;
}

/**
 * 按消息时间/身份做并集：只增不减。
 * 旧窗口整份会话即使 updateTime 更新，也不能删掉另一窗口已经写出的新消息。
 * 内容指纹兜底：同一条消息可能在服务端（内容哈希 id m-h…）和本窗口（随机 id）各存一份，
 * 只要一方是内容哈希 id 就按指纹折叠成一条，并把 id 收敛到内容哈希，两端合并键才能对齐。
 */
export function mergeMessages(left: IChatMsg[] = [], right: IChatMsg[] = []): IChatMsg[] {
  const byKey = new Map<string, IChatMsg>();
  const fpIndex = new Map<string, string>();
  const order: string[] = [];
  const ingest = (list: IChatMsg[]) => {
    list.forEach((raw, idx) => {
      if (!raw || !raw.role) {
        return;
      }
      const m: IChatMsg = {
        ...raw,
        time: raw.time || idx + 1,
      };
      // 历史/服务端来的消息可能没有 id（旧版本落库的数据）：
      // 不补稳定 id 的话合并键退化成 role+前200字指纹，同前缀消息会被误判成同一条折叠掉
      if (!m.id && !m.__progress && !m.__streaming) {
        m.id = stableMsgId(m);
      }
      const key = messageKey(m);
      const fpKey = contentKey(m);
      const target = byKey.has(key) ? key : fpIndex.get(fpKey);
      if (!target) {
        byKey.set(key, m);
        fpIndex.set(fpKey, key);
        order.push(key);
      } else {
        const merged = pickMsg(byKey.get(target)!, m);
        if (target.startsWith('id:m-h') && !key.startsWith('id:m-h')) {
          merged.id = target.slice(3);
        }
        byKey.set(target, merged);
        fpIndex.set(fpKey, target);
      }
    });
  };
  ingest(left);
  ingest(right);
  // __streaming 是"本窗口正在流式输出"的内存态标记，不允许进任何合并结果：
  // 否则异常中断的轮次会把标记写进 localStorage/服务端，恢复后这条气泡永远闪"生成中"光标。
  // 持有流的窗口用内存里的原对象渲染，不受这里影响。
  return order
    .map((k) => byKey.get(k)!)
    .sort((a, b) => (a.time || 0) - (b.time || 0))
    .map((m) => {
      if ((m as any).__streaming) {
        const clean = { ...m };
        delete (clean as any).__streaming;
        return clean;
      }
      return m;
    });
}

/** 不含 id 的内容指纹键：进度行按章节，其余按 role+前200字 */
function contentKey(m: IChatMsg): string {
  if (m.__progress) {
    return progressKey(m.content || '');
  }
  return `fp:${m.role}:${(m.content || '').slice(0, 200)}`;
}

function preferMeta(newer: ISession, older: ISession): ISession {
  return {
    ...older,
    ...newer,
    batchNo: newer.batchNo || older.batchNo,
    lybm: newer.lybm || older.lybm,
    lymc: newer.lymc || older.lymc,
    modelId: newer.modelId || older.modelId,
    modelLabel: newer.modelLabel || older.modelLabel,
    title: newer.title && newer.title !== '新会话' ? newer.title : older.title || newer.title,
  };
}

/** 两份同 id 会话：消息并集，元数据取较新一侧（空字段用另一侧补） */
export function mergeSession(a: ISession, b: ISession): ISession {
  const aTs = a.updateTime || 0;
  const bTs = b.updateTime || 0;
  const newer = bTs > aTs ? b : a;
  const older = newer === b ? a : b;
  const messages = mergeMessages(a.messages, b.messages);
  const maxMsg = messages.reduce((n, m) => Math.max(n, m.time || 0), 0);
  return {
    ...preferMeta(newer, older),
    conversationId: newer.conversationId || older.conversationId,
    messages,
    generating: !!(a.generating || b.generating),
    updateTime: Math.max(aTs, bTs, maxMsg),
  };
}
