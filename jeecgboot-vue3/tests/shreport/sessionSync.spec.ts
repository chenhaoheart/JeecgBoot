/**
 * 山洪报告助手 - 会话「本机缓存 ↔ 服务端」同步逻辑测试
 *
 * 覆盖线上问题：库里（或其他窗口）删掉的会话，在内置浏览器里仍然显示，原因是
 * 1. 本机 localStorage 缓存被当成第一显示源，删除只能靠远端「消失」被动检测；
 * 2. 拉取失败被静默兜底，用户看到的其实是缓存；
 * 3. 删除传播依赖 knownRemote 间接判定，缓存缺该字段时永不删除；
 * 4. 本机存储 key 与后端用户维度不一致，同一个人分裂成两份数据。
 *
 * store 是模块级单例，且墓碑/known/pending 集合会跨用例累积，
 * 因此各用例一律使用互不相同的 conversationId，避免互相污染。
 */

const remoteSessions: any[] = [];
let listImpl: () => Promise<any[]> = async () => remoteSessions;
let listShouldFail = false;

jest.mock('/@/views/super/airag/shreport/ShReportAssistant.api', () => ({
  createConversation: jest.fn().mockResolvedValue({ conversationId: 'c-new', batchNo: 'B-new' }),
  bindModel: jest.fn().mockResolvedValue({}),
  bindWatershed: jest.fn().mockResolvedValue({}),
  cancelGenerate: jest.fn().mockResolvedValue({}),
  getAgentModels: jest.fn().mockResolvedValue([]),
  getContext: jest.fn().mockResolvedValue({}),
  getWatersheds: jest.fn().mockResolvedValue([]),
  getQcIssues: jest.fn().mockResolvedValue([]),
  getChapterList: jest.fn().mockResolvedValue([]),
  listServerSessions: jest.fn(() => (listShouldFail ? Promise.reject(new Error('network down')) : listImpl())),
  saveServerSession: jest.fn().mockResolvedValue({}),
  deleteServerSession: jest.fn().mockResolvedValue({}),
  downloadReport: jest.fn(),
}));

jest.mock('ant-design-vue', () => ({
  message: { success: jest.fn(), info: jest.fn(), warning: jest.fn(), error: jest.fn() },
}));

// 存储维度必须与后端currentUserKey() 一致：登录名优先
// 工厂内建 reactive state，模拟 pinia 的响应式 getter（账号切换的 watch 依赖它才会触发）
jest.mock('/@/store/modules/user', () => {
  const { reactive } = require('vue');
  const state = reactive({ id: 'u1', username: 'tester' });
  return {
    useUserStore: () => ({ getUserInfo: state }),
    __setUser: (u: any) => Object.assign(state, u),
  };
});

import { message } from 'ant-design-vue';
import { nextTick } from 'vue';

const { useReportSession } = require('/@/views/super/airag/shreport/useReportSession');
const { __setUser: setUser } = require('/@/store/modules/user');
const messageMock = message as unknown as { warning: jest.Mock; success: jest.Mock };

const STORE_KEY = 'shreport_agent_sessions__tester';
const LEGACY_KEY = 'shreport_agent_sessions__u1';

/** 早于所有用例的时间戳：避免被「本轮新建保护」跳过裁剪 */
const OLD_TS = Date.now() - 24 * 3600 * 1000;

async function flush(ms = 30) {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

function seed(data: any, key = STORE_KEY) {
  localStorage.setItem(key, JSON.stringify(data));
}

beforeEach(() => {
  localStorage.clear();
  remoteSessions.length = 0;
  listShouldFail = false;
  listImpl = async () => remoteSessions;
  setUser({ id: 'u1', username: 'tester' });
  messageMock.warning.mockClear();
  messageMock.success.mockClear();
});

describe('山洪报告助手 - 会话与服务端同步', () => {
  test('服务端已删除的会话：进入页面即被裁剪（本机残留不再死而复生）', async () => {
    seed({
      sessions: [{ conversationId: 'c-gone', batchNo: 'B-1', title: '库里已删的会话', messages: [], updateTime: OLD_TS }],
      known: ['c-gone'],
    });
    // 服务端已无该会话（例如直接在库里删掉了）
    const store = useReportSession();
    await store.init();

    expect(store.sessions.value.map((s: any) => s.conversationId)).not.toContain('c-gone');
    // 列表空了应自动开新会话，而不是留一个空壳页面
    expect(store.conversationId.value).toBe('c-new');
    // 墓碑要落盘，防止下一次 watch 落盘时又被旧缓存写回
    await flush(600);
    expect(JSON.parse(localStorage.getItem(STORE_KEY) || '{}').deleted?.['c-gone']).toBeTruthy();
  });

  test('推送失败尚未入库的会话不会被裁剪（本机唯一副本要保住）', async () => {
    seed({
      sessions: [{ conversationId: 'c-pending', batchNo: 'B-p', title: '推送失败的会话', messages: [], updateTime: OLD_TS }],
      known: ['c-pending'],
      pending: ['c-pending'],
    });
    const store = useReportSession();
    await store.init();

    expect(store.sessions.value.map((s: any) => s.conversationId)).toContain('c-pending');
  });

  test('本机超集（上次推送失败留下的消息）合入远端后仍会补推，不会被水印跳过', async () => {
    const ts = Date.now();
    seed({
      sessions: [
        {
          conversationId: 'c-super',
          batchNo: 'B-x',
          title: '超集会话',
          // 本机两条（第二条是推送失败留下的），服务端只有一条
          messages: [
            { role: 'user', content: '问题一', time: ts - 1000, id: 'm1' },
            { role: 'assistant', content: '本机多出来的回复', time: ts - 500, id: 'm2' },
          ],
          updateTime: ts,
        },
      ],
      known: ['c-super'],
    });
    remoteSessions.push({
      conversationId: 'c-super',
      batchNo: 'B-x',
      title: '超集会话',
      messages: [{ role: 'user', content: '问题一', time: ts - 1000, id: 'm1' }],
      updateTime: ts - 1000,
    });
    const { saveServerSession } = require('/@/views/super/airag/shreport/ShReportAssistant.api');
    (saveServerSession as jest.Mock).mockClear();

    const store = useReportSession();
    await store.init();
    expect(store.sessions.value[0].messages).toHaveLength(2);

    // 等待 2s 防抖触发补推：水印若被抬到 merged.updateTime，这条就永远推不上服务端
    await flush(2600);
    const pushed = (saveServerSession as jest.Mock).mock.calls.map((c: any[]) => c[0]).filter((p: any) => p.conversationId === 'c-super');
    expect(pushed.length).toBeGreaterThan(0);
    expect(pushed[pushed.length - 1].messages).toHaveLength(2);
  });

  test('拉取失败时明确提示当前显示本机缓存，并保留本地会话', async () => {
    seed({
      sessions: [{ conversationId: 'c-offline', batchNo: 'B-1', title: '本机会话', messages: [], updateTime: OLD_TS }],
      known: ['c-offline'],
    });
    listShouldFail = true;
    const store = useReportSession();
    await store.init();

    expect(store.sessions.value.map((s: any) => s.conversationId)).toContain('c-offline');
    expect(messageMock.warning).toHaveBeenCalledWith(expect.stringContaining('本机缓存'));
  });

  test('存储按登录名隔离，并自动迁移旧 userId 维度的缓存', async () => {
    seed(
      { sessions: [{ conversationId: 'c-legacy', batchNo: 'B-l', title: '旧缓存', messages: [], updateTime: OLD_TS }], known: ['c-legacy'] },
      LEGACY_KEY
    );
    remoteSessions.push({ conversationId: 'c-legacy', batchNo: 'B-l', title: '旧缓存', messages: [], updateTime: OLD_TS });
    const store = useReportSession();
    await store.init();

    expect(store.sessions.value.map((s: any) => s.conversationId)).toContain('c-legacy');
    await flush(600);
    // 新 key 写好后旧 key 必须清掉，否则同一用户残留一份永不同步的孤儿缓存
    expect(localStorage.getItem(STORE_KEY)).toBeTruthy();
    expect(localStorage.getItem(LEGACY_KEY)).toBeNull();
  });

  test('手动刷新以服务端为准：其他窗口删掉的会话立即消失', async () => {
    seed({
      sessions: [{ conversationId: 'c-shared', batchNo: 'B-s', title: '共享会话', messages: [], updateTime: OLD_TS }],
      known: ['c-shared'],
    });
    remoteSessions.push({ conversationId: 'c-shared', batchNo: 'B-s', title: '共享会话', messages: [], updateTime: OLD_TS });
    const store = useReportSession();
    await store.init();
    expect(store.sessions.value.map((s: any) => s.conversationId)).toContain('c-shared');

    // 另一个窗口把它删了：远端列表里不再返回
    remoteSessions.length = 0;
    await store.refreshSessions();

    expect(store.sessions.value.map((s: any) => s.conversationId)).not.toContain('c-shared');
    expect(messageMock.success).toHaveBeenCalledWith(expect.stringContaining('同步'));
  });

  test('清除本机缓存后按服务端重新恢复', async () => {
    const store = useReportSession();
    await store.init();

    // 模拟库里被手工塞脏数据后只剩本机副本
    seed({ sessions: [{ conversationId: 'c-ghost', batchNo: 'B-g', title: '幽灵会话', messages: [], updateTime: OLD_TS }], known: ['c-ghost'] });
    await store.clearLocalCache();

    expect(store.sessions.value.map((s: any) => s.conversationId)).not.toContain('c-ghost');
  });

  test('切换登录账号：内存残留被清空，不会把上一用户的会话显示给新用户', async () => {
    seed({
      sessions: [{ conversationId: 'c-user-a', batchNo: 'B-a', title: 'A 的会话', messages: [], updateTime: OLD_TS }],
      known: ['c-user-a'],
    });
    remoteSessions.push({ conversationId: 'c-user-a', batchNo: 'B-a', title: 'A 的会话', messages: [], updateTime: OLD_TS });
    const store = useReportSession();
    await store.init();
    expect(store.sessions.value.map((s: any) => s.conversationId)).toContain('c-user-a');

    // 换成另一个登录人（同一浏览器重新登录），并给出属于 B 的远端数据
    remoteSessions.length = 0;
    remoteSessions.push({ conversationId: 'c-user-b', batchNo: 'B-b', title: 'B 的会话', messages: [], updateTime: OLD_TS });
    setUser({ id: 'u2', username: 'tester2' });
    await nextTick();
    await flush(100);

    const ids = store.sessions.value.map((s: any) => s.conversationId);
    expect(ids).toContain('c-user-b');
    expect(ids).not.toContain('c-user-a');
  });
});