/**
 * 章节状态元数据：状态 → 主色 / 背景色
 * 状态取值与后端一致：待审核 / 已通过 / 已驳回 / 生成中 / 失败
 */
export interface IStatusMeta {
  color: string;
  bg: string;
}

export function statusMeta(status?: string): IStatusMeta {
  switch (status) {
    case '已通过':
      return { color: '#52c41a', bg: '#f6ffed' };
    case '已驳回':
      return { color: '#fa541c', bg: '#fff2e8' };
    case '生成中':
      return { color: '#1677ff', bg: '#e6f4ff' };
    case '失败':
      return { color: '#ff4d4f', bg: '#fff1f0' };
    default:
      return { color: '#faad14', bg: '#fffbe6' }; // 待审核
  }
}

/** 尚未生成章节的占位色（进度条 / 骨架用） */
export const EMPTY_STATUS_META: IStatusMeta = { color: '#e4e8ef', bg: '#f5f7fa' };

/** "2026-09-16 02:18:37" → "09-16 02:18" */
export function shortTime(s?: string): string {
  if (!s) {
    return '';
  }
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/);
  return m ? `${m[2]}-${m[3]} ${m[4]}:${m[5]}` : s;
}

/** 章节生成耗时（毫秒）→ "48秒" / "3分12秒" / "1小时05分"；无数据返回空串 */
export function genCostText(ms?: number | null): string {
  if (!ms || ms <= 0) {
    return '';
  }
  const s = Math.round(ms / 1000);
  if (s < 60) {
    return `${s}秒`;
  }
  const m = Math.floor(s / 60);
  const r = s % 60;
  if (m < 60) {
    return r ? `${m}分${r}秒` : `${m}分`;
  }
  return `${Math.floor(m / 60)}小时${String(m % 60).padStart(2, '0')}分`;
}
