/**
 * 章节版本对比：基于行的 LCS 差异，供「对比上一版」弹窗渲染。
 * 纯函数、无 DOM 依赖，便于 Jest 单测。
 */
export type DiffLineType = 'same' | 'add' | 'del';

export interface IDiffLine {
  type: DiffLineType;
  text: string;
}

export function lineDiff(oldText: string, newText: string): IDiffLine[] {
  const splitLines = (t: string) => (t ? t.replace(/\r\n/g, '\n').split('\n') : []);
  const a = splitLines(oldText || '');
  const b = splitLines(newText || '');
  const m = a.length;
  const n = b.length;

  // LCS 动态规划表
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array<number>(n + 1).fill(0));
  for (let i = m - 1; i >= 0; i--) {
    for (let j = n - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }

  const out: IDiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < m && j < n) {
    if (a[i] === b[j]) {
      out.push({ type: 'same', text: a[i] });
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ type: 'del', text: a[i] });
      i++;
    } else {
      out.push({ type: 'add', text: b[j] });
      j++;
    }
  }
  while (i < m) {
    out.push({ type: 'del', text: a[i++] });
  }
  while (j < n) {
    out.push({ type: 'add', text: b[j++] });
  }
  return out;
}

/** 是否存在实质差异（忽略首尾空白行） */
export function hasDiff(oldText: string, newText: string): boolean {
  return lineDiff(oldText, newText).some((l) => l.type !== 'same');
}
