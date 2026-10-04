import { hasDiff, lineDiff } from '/@/views/super/airag/shreport/components/versionDiff';

describe('山洪报告 - 章节版本对比 lineDiff', () => {
  test('完全相同的文本只有 same 行', () => {
    const text = '第一节\n第二节\n第三节';
    const diff = lineDiff(text, text);
    expect(diff.every((l) => l.type === 'same')).toBe(true);
    expect(diff).toHaveLength(3);
  });

  test('新增行标记 add，删除行标记 del，公共行保持 same 且顺序正确', () => {
    const oldText = '标题\n旧段落\n结尾';
    const newText = '标题\n新段落\n新增补充\n结尾';
    const diff = lineDiff(oldText, newText);

    expect(diff.find((l) => l.type === 'del' && l.text === '旧段落')).toBeTruthy();
    expect(diff.find((l) => l.type === 'add' && l.text === '新段落')).toBeTruthy();
    expect(diff.find((l) => l.type === 'add' && l.text === '新增补充')).toBeTruthy();
    const sameTitles = diff.filter((l) => l.type === 'same').map((l) => l.text);
    expect(sameTitles).toEqual(['标题', '结尾']);
    // 标题在前、结尾在后
    expect(diff.findIndex((l) => l.text === '标题')).toBeLessThan(diff.findIndex((l) => l.text === '结尾'));
  });

  test('空文本场景不报错，全部按另一侧增量输出', () => {
    const diff = lineDiff('', '仅新版本有内容');
    expect(diff).toEqual([{ type: 'add', text: '仅新版本有内容' }]);
    const diff2 = lineDiff('仅旧版本有内容', '');
    expect(diff2).toEqual([{ type: 'del', text: '仅旧版本有内容' }]);
  });

  test('hasDiff：有差异为 true，忽略 CRLF 差异', () => {
    expect(hasDiff('a\nb', 'a\r\nb')).toBe(false);
    expect(hasDiff('a\nb', 'a\nc')).toBe(true);
  });
});
