import { mergeMessages, mergeSession, stampMsg, stableMsgId, type IChatMsg, type ISession } from '/@/views/super/airag/shreport/sessionMerge';

function session(partial: Partial<ISession> & Pick<ISession, 'conversationId' | 'messages' | 'updateTime'>): ISession {
  return {
    batchNo: 'B1',
    title: '新会话',
    ...partial,
  };
}

describe('会话消息按时间增量合并', () => {
  test('stampMsg 补齐 id 与 time，已有值不覆盖', () => {
    const a = stampMsg({ role: 'user', content: '生成吕家河报告' }, 1000);
    expect(a.time).toBe(1000);
    expect(a.id).toMatch(/^m-1000-/);
    const b = stampMsg({ ...a, content: '生成吕家河报告（改）' }, 2000);
    expect(b.time).toBe(1000);
    expect(b.id).toBe(a.id);
  });

  test('旧窗口 updateTime 更新、消息更少：不能删掉另一窗口已经写出的新消息', () => {
    const generating: IChatMsg[] = [
      { id: 'm1', time: 10, role: 'assistant', content: '欢迎' },
      { id: 'm2', time: 20, role: 'user', content: '生成吕家河流域的报告' },
      { id: 'm3', time: 30, role: 'assistant', content: '好的，开始生成全部 7 个章节。' },
      { id: 'm4', time: 40, role: 'assistant', content: '⏳ 第1章 流域概况：生成中', __progress: true },
    ];
    const stale: IChatMsg[] = [{ id: 'm1', time: 10, role: 'assistant', content: '欢迎' }];
    const local = session({ conversationId: 'c1', messages: generating, updateTime: 30 });
    const other = session({ conversationId: 'c1', messages: stale, updateTime: 90, title: '闲置窗口' });
    const merged = mergeSession(local, other);
    expect(merged.messages.map((m) => m.content)).toEqual([
      '欢迎',
      '生成吕家河流域的报告',
      '好的，开始生成全部 7 个章节。',
      '⏳ 第1章 流域概况：生成中',
    ]);
    expect(merged.updateTime).toBe(90);
  });

  test('两边各有对方没有的消息：按时间并集，不互相覆盖', () => {
    const left: IChatMsg[] = [
      { id: 'a', time: 1, role: 'user', content: '问进度' },
      { id: 'b', time: 3, role: 'assistant', content: '已完成 3 章' },
    ];
    const right: IChatMsg[] = [
      { id: 'a', time: 1, role: 'user', content: '问进度' },
      { id: 'c', time: 2, role: 'user', content: '导出 Word' },
    ];
    const out = mergeMessages(left, right);
    expect(out.map((m) => m.id)).toEqual(['a', 'c', 'b']);
  });

  test('同一 id 的流式气泡保留更长正文', () => {
    const out = mergeMessages(
      [{ id: 's1', time: 5, role: 'assistant', content: '收到' }],
      [{ id: 's1', time: 5, role: 'assistant', content: '收到，我先分析一下你的需求…\n\n正在生成' }]
    );
    expect(out).toHaveLength(1);
    expect(out[0].content).toContain('正在生成');
  });

  test('无 id 的历史消息合并时补稳定 id：同内容同 id（跨端对齐合并键）', () => {
    // 期望值由 Node 按同一算法生成，同时固定在后端 ShReportSessionMessageMergeTest，作为跨语言契约
    expect(stableMsgId({ role: 'user', content: '继续' })).toBe('m-hjdeq82');
    expect(stableMsgId({ role: 'user', content: '继续' })).toBe(stableMsgId({ role: 'user', content: '继续' }));
    expect(stableMsgId({ role: 'user', content: '继续' })).not.toBe(stableMsgId({ role: 'user', content: '继续，请接着生成' }));

    const out = mergeMessages([{ role: 'user', content: '继续' }], [{ role: 'user', content: '继续', time: 1000 }]);
    expect(out).toHaveLength(1);
    expect(out[0].id).toBe('m-hjdeq82');
    expect(out[0].time).toBe(1000);
  });

  test('进度行与流式气泡不补内容哈希 id', () => {
    const progress = mergeMessages([], [{ role: 'assistant', content: '⏳ 第1章 流域概况：生成中', __progress: true }]);
    expect(progress).toHaveLength(1);
    expect(progress[0].id).toBeUndefined();

    const streaming = mergeMessages([], [{ role: 'assistant', content: '部分回复', __streaming: true }]);
    expect(streaming).toHaveLength(1);
    expect(streaming[0].id).toBeUndefined();
  });
});
