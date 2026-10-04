import { computed, ref, watch } from 'vue';
import { message } from 'ant-design-vue';
import { useUserStore } from '/@/store/modules/user';
import {
  bindModel,
  cancelGenerate,
  bindWatershed,
  createConversation,
  deleteServerSession,
  downloadReport,
  getAgentModels,
  getChapterList,
  getContext,
  getQcIssues,
  getWatersheds,
  IAgentModel,
  IChapter,
  IQcIssue,
  IUsage,
  IWatershed,
  listServerSessions,
  saveServerSession,
} from './ShReportAssistant.api';
import { mergeMessages, mergeSession, stampMsg, type IChatMsg, type IKbSource, type ISession } from './sessionMerge';

export { stampMsg, mergeMessages, mergeSession };
export type { IChatMsg, IKbSource, ISession };

export const WELCOME =
  '你好，我是**山洪报告生成助手**，用于编制《山洪灾害风险隐患调查评价报告》。\n\n你可以这样对我说：\n- 「帮我生成 XX 流域的整份报告」\n- 「先出一份大纲」确认后再生成\n- 「修改第三章，表格数据再细化」\n- 「查看当前报告进度」\n\n生成完成后可在右侧章节面板查看、审核每一章内容。';

/**
 * 报告助手共享上下文（单例）
 * 顶部工具栏 / 对话面板 / 章节面板共用同一份状态：会话、模型、流域、批次、章节，
 * 避免多层 props 透传，也让「历史会话、模型切换」可以统一上移到顶部操作区。
 */
function createReportSession() {
  const sessions = ref<ISession[]>([]);
  const conversationId = ref('');
  const batchNo = ref('');
  const activeMessages = ref<IChatMsg[]>([]);
  /** 对话流进行中：顶部新建会话、模型切换等操作需要禁用 */
  const loading = ref(false);
  const boundWatershed = ref<{ lybm: string; lymc: string } | null>(null);
  const modelOptions = ref<IAgentModel[]>([]);
  const currentModelId = ref<string | undefined>(undefined);
  const watersheds = ref<IWatershed[]>([]);
  const chapters = ref<IChapter[]>([]);
  const issues = ref<IQcIssue[]>([]);
  /** 当前会话累计 token 用量：SSE usage 事件实时更新，切会话从 /context 恢复 */
  const usage = ref<IUsage | null>(null);

  function applyUsage(u: IUsage | null | undefined) {
    if (u && typeof u === 'object') {
      usage.value = u;
    }
  }

  /** 章节号 -> 质检问题 */
  const issueMap = computed(() => {
    const map: Record<number, IQcIssue[]> = {};
    for (const q of issues.value) {
      (map[q.chapterNo] ||= []).push(q);
    }
    return map;
  });

  /** 有正文且非生成中的章节可导出 Word（不要求审核通过） */
  function isExportable(ch: IChapter) {
    return !!String(ch.content || '').trim() && ch.status !== '生成中';
  }

  const exportableChapters = computed(() => chapters.value.filter(isExportable));

  const allPassed = computed(
    () => chapters.value.length >= 7 && chapters.value.every((c) => c.status === '已通过')
  );

  /** 绑定了流域且至少有一章可导出 */
  const canExport = computed(() => !!boundWatershed.value?.lybm && exportableChapters.value.length > 0);

  const passedCount = computed(() => chapters.value.filter((c) => c.status === '已通过').length);

  /** 导出 Word 进行中（工具栏与章节面板共用） */
  const exporting = ref(false);
  /** 章节面板手动刷新 / 单独查询进行中 */
  const refreshing = ref(false);
  /** 选择导出章节的弹层 */
  const exportPickerOpen = ref(false);
  const exportChapterNos = ref<number[]>([]);

  function exportFileName(nos?: number[]) {
    const lymc = currentLymc();
    const bn = batchNo.value;
    if (nos?.length === 1) {
      return `山洪灾害风险隐患调查评价报告_${lymc}_第${nos[0]}章_${bn}.docx`;
    }
    if (nos?.length) {
      return `山洪灾害风险隐患调查评价报告_${lymc}_第${nos.join('-')}章_${bn}.docx`;
    }
    return `山洪灾害风险隐患调查评价报告_${lymc}_${bn}.docx`;
  }

  /** 触发浏览器下载 Word 文件 */
  async function downloadWord(bn: string, lybm: string, fileName?: string, chapterNos?: number[]) {
    const blob = await downloadReport(bn, lybm, chapterNos);
    const url = window.URL.createObjectURL(new Blob([blob as any]));
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName || `山洪灾害风险隐患调查评价报告_${bn}.docx`;
    link.click();
    window.URL.revokeObjectURL(url);
  }

  function openExportPicker() {
    if (!boundWatershed.value?.lybm) {
      message.warning('请先选择流域');
      return;
    }
    if (!exportableChapters.value.length) {
      message.warning('当前没有可导出的章节内容，请先生成');
      return;
    }
    exportChapterNos.value = exportableChapters.value.map((c) => c.chapterNo);
    exportPickerOpen.value = true;
  }

  /** 导出选中章节（不传则导出全部可导出章） */
  async function exportReport(chapterNos?: number[]) {
    if (!boundWatershed.value?.lybm) {
      message.warning('请先选择流域');
      return;
    }
    const nos = chapterNos && chapterNos.length ? chapterNos : exportableChapters.value.map((c) => c.chapterNo);
    if (!nos.length) {
      message.warning('请至少选择一章');
      return;
    }
    exporting.value = true;
    try {
      await downloadWord(batchNo.value, boundWatershed.value.lybm, exportFileName(nos), nos);
      exportPickerOpen.value = false;
    } catch (e: any) {
      const text = await e?.response?.text?.();
      message.error(text || '导出失败');
      throw e;
    } finally {
      exporting.value = false;
    }
  }

  /** localStorage 按用户隔离，切浏览器/重启后会话记录仍在 */
  function storeKey() {
    const info = useUserStore().getUserInfo;
    // 必须与后端 ShReportAgentController.currentUserKey() 同维度（登录名优先）：
    // 这里若用 userId、而库表按登录名存，同一个人会分裂成两份互不同步的数据。
    const uid = info?.username || info?.id || 'common';
    return `shreport_agent_sessions__${uid}`;
  }

  /**
   * 旧版按 userId 存储的历史 key（切换到「登录名」维度后需要迁移）。
   * 内置浏览器/IDE webview 不会自动换掉已写入的 key，不迁移就会残留一份孤儿缓存。
   */
  function legacyStoreKey() {
    const id = useUserStore().getUserInfo?.id;
    return id ? `shreport_agent_sessions__${id}` : '';
  }

  /** 依次尝试新 key 与旧 key，返回第一个存在的原始数据 */
  function readStoreRaw(): any | null {
    for (const key of [storeKey(), legacyStoreKey()]) {
      if (!key) {
        continue;
      }
      try {
        const raw = localStorage.getItem(key);
        if (raw) {
          const parsed = JSON.parse(raw);
          if (parsed && typeof parsed === 'object') {
            return parsed;
          }
        }
      } catch {
        // 存储数据损坏时按无数据处理
      }
    }
    return null;
  }

  /**
   * 删除墓碑：localStorage 与服务端合并都按 conversationId 并集，
   * 删除的会话若不做标记会在下次合并时被旧缓存复活。墓碑会话一律排除。
   */
  const deletedMap: Record<string, number> = {};
  /**
   * 已确认在服务端存在过的会话：某次拉取发现它从服务端消失时，
   * 视为在其他窗口/浏览器被删除 → 本地同步移除并落墓碑（且不再补推，防止复活）。
   * 随 localStorage 持久化，跨刷新生效。
   */
  const knownRemote = new Set<string>();
  /**
   * 推送失败的会话：这些会话服务端确实还没有，若按「远端为准」裁剪会误删本机唯一副本。
   * 推送失败时登记、推送成功或合并到远端内容时销账，随localStorage 持久化。
   */
  const pendingPush = new Set<string>();

  function loadDeletedMap() {
    try {
      const parsed = readStoreRaw();
      if (parsed?.deleted && typeof parsed.deleted === 'object') {
        Object.assign(deletedMap, parsed.deleted);
      }
      if (Array.isArray(parsed?.known)) {
        for (const id of parsed.known) {
          knownRemote.add(id);
        }
      }
      if (Array.isArray(parsed?.pending)) {
        for (const id of parsed.pending) {
          pendingPush.add(id);
        }
      }
    } catch {
      // 存储数据损坏时按无墓碑处理
    }
  }

  function saveStore() {
    try {
      // 读-合并-写：其他窗口可能已经写入了更新的消息。
      // 同一会话按消息时间做并集，禁止用旧消息列表整段覆盖新对话。
      let stored: ISession[] = [];
      try {
        const parsed = readStoreRaw();
        if (parsed && Array.isArray(parsed.sessions)) {
          stored = parsed.sessions;
        }
      } catch {
        // 存储数据损坏时按空处理，以本页内存为准
      }
      const byId = new Map<string, ISession>();
      for (const s of stored) {
        if (s?.conversationId && !deletedMap[s.conversationId]) {
          byId.set(s.conversationId, s);
        }
      }
      for (const s of sessions.value) {
        if (deletedMap[s.conversationId]) {
          continue;
        }
        const prev = byId.get(s.conversationId);
        if (prev) {
          const merged = mergeSession(prev, s);
          // generating 只反映本窗口的实时状态：存储里残留的旧标记不能经 OR 合并被永久复活
          merged.generating = !!s.generating;
          byId.set(s.conversationId, merged);
        } else {
          byId.set(s.conversationId, s);
        }
      }
      const merged = [...byId.values()].sort((a, b) => (b.updateTime || 0) - (a.updateTime || 0));
      localStorage.setItem(storeKey(), JSON.stringify({ sessions: merged, deleted: deletedMap, known: [...knownRemote], pending: [...pendingPush] }));
      // 迁移完成：清掉旧 userId 维度的 key，避免同一用户残留一份永不同步的孤儿缓存
      const legacy = legacyStoreKey();
      if (legacy && legacy !== storeKey()) {
        localStorage.removeItem(legacy);
      }
    } catch (e) {
      console.warn('会话保存失败', e);
    }
    // 本机写完顺带同步到服务端（防抖），供其他浏览器/设备恢复
    scheduleServerSync();
  }

  /**
   * 服务端会话持久化：localStorage 按浏览器隔离，换浏览器就看不到历史。
   * 有变化的会话防抖 2s 后按（用户+会话）幂等入库，本机存储仅作降级缓存。
   */
  const syncedTs = new Map<string, number>();
  /** 在途保存去重：防止同一会话的两个保存请求乱序返回，旧数据覆盖新数据 */
  const savingNow = new Set<string>();
  /** 推送失败退避重试：失败后若不再有新变更，也要把最后一次变更补推上去 */
  const syncRetry = { count: 0, timer: null as ReturnType<typeof setTimeout> | null };
  let syncTimer: ReturnType<typeof setTimeout> | null = null;
  let saveTimer: ReturnType<typeof setTimeout> | null = null;

  function scheduleServerSync() {
    // 有新变更说明用户还在活跃，重置退避计数给一轮完整的重试机会
    syncRetry.count = 0;
    if (syncTimer) {
      return;
    }
    syncTimer = setTimeout(() => {
      syncTimer = null;
      syncServerSessions();
    }, 2000);
  }

  function scheduleSyncRetry() {
    // 单测环境不挂后台定时器，避免 Jest 因 open handle 挂起
    if (typeof process !== 'undefined' && process.env.JEST_WORKER_ID) {
      return;
    }
    if (syncRetry.timer || syncRetry.count >= 3) {
      return;
    }
    syncRetry.count += 1;
    const delay = Math.min(5000 * 2 ** (syncRetry.count - 1), 60000);
    syncRetry.timer = setTimeout(() => {
      syncRetry.timer = null;
      syncServerSessions();
    }, delay);
  }

  function syncServerSessions() {
    for (const s of sessions.value) {
      if ((s.updateTime || 0) <= (syncedTs.get(s.conversationId) || 0)) {
        continue;
      }
      if (savingNow.has(s.conversationId)) {
        continue;
      }
      savingNow.add(s.conversationId);
      const snapTs = s.updateTime || 0;
      saveServerSession({
        conversationId: s.conversationId,
        batchNo: s.batchNo,
        lybm: s.lybm,
        lymc: s.lymc,
        modelId: s.modelId,
        modelLabel: s.modelLabel,
        title: s.title,
        messages: s.messages,
        updateTime: snapTs,
        archived: !!s.archived,
      })
        .then(() => {
          syncedTs.set(s.conversationId, Math.max(syncedTs.get(s.conversationId) || 0, snapTs));
          knownRemote.add(s.conversationId);
          // 已入库：撤销「推送失败」登记，后续权威裁剪才敢以服务端为准处理它
          pendingPush.delete(s.conversationId);
          syncRetry.count = 0;
        })
        .catch(() => {
          // 登记为「服务端还没有」：权威裁剪要跳过它，否则会把本机唯一副本当成幽灵删掉
          pendingPush.add(s.conversationId);
          // 同步失败走退避重试：否则最后一次变更推送失败后（如网络抖动后刷新页面）就永远不同步了
          scheduleSyncRetry();
        })
        .finally(() => {
          savingNow.delete(s.conversationId);
          // 保存期间会话又有更新（如流式输出中），再排一次同步把最新内容补上
          const cur = sessions.value.find((x) => x.conversationId === s.conversationId);
          if (cur && (cur.updateTime || 0) > (syncedTs.get(s.conversationId) || 0)) {
            scheduleServerSync();
          }
        });
    }
  }

  function loadStore(): ISession[] {
    try {
      const parsed = readStoreRaw();
      if (parsed) {
        if (Array.isArray(parsed.sessions)) {
          // generating 标志只对本次页面生命周期有效：刷新后 SSE 流已不存在，必须清掉，否则小圆点永久闪烁
          // 消息统一过一遍 mergeMessages：补齐缺失的稳定 id/time，并去掉历史版本恢复可能叠加的重复消息
          return parsed.sessions.map(
            (s: ISession): ISession => ({ ...s, generating: false, messages: mergeMessages([], s.messages || []) })
          );
        }
      }
      // 兼容旧版单会话 sessionStorage 数据
      const old = sessionStorage.getItem('shreport_agent_session');
      if (old) {
        const s = JSON.parse(old);
        if (s.conversationId && s.batchNo) {
          return [
            {
              conversationId: s.conversationId,
              batchNo: s.batchNo,
              title: '历史会话',
              messages: mergeMessages([], Array.isArray(s.messages) ? s.messages : []),
              updateTime: Date.now(),
            },
          ];
        }
      }
    } catch (e) {
      // 存储数据损坏时按空处理，以本页内存为准
    }
    return [];
  }

  function activeSession(): ISession | undefined {
    return sessions.value.find((s) => s.conversationId === conversationId.value);
  }

  function touchSession(msgs?: IChatMsg[]) {
    const s = activeSession();
    if (!s) {
      return;
    }
    s.updateTime = Date.now();
    if (msgs) {
      s.messages = msgs;
    }
  }

  function formatTime(ts: number) {
    if (!ts) {
      return '';
    }
    const d = new Date(ts);
    const p = (n: number) => String(n).padStart(2, '0');
    return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }

  /** 从后端会话上下文恢复流域绑定、批次号与模型选择（切会话/刷新后保持对话与面板联动） */
  async function restoreWatershed(convId: string) {
    try {
      const ctx = await getContext(convId);
      if (ctx?.batchNo) {
        batchNo.value = ctx.batchNo;
        const s = activeSession();
        if (s) {
          s.batchNo = ctx.batchNo;
        }
      }
      if (ctx?.lybm) {
        applyWatershed({ lybm: ctx.lybm, lymc: ctx.lymc || ctx.wsmc || ctx.lybm }, false, true);
      }
      currentModelId.value = ctx?.modelId || undefined;
      applyUsage(ctx?.usage);
      const s = activeSession();
      if (s) {
        s.modelId = ctx?.modelId || undefined;
        s.modelLabel = ctx?.modelLabel;
        // 只恢复元数据，不刷新 updateTime：否则闲置窗口会被当成「更新」去覆盖正在生成的对话
      }
    } catch (e) {
      // 上下文恢复失败不阻塞对话
    }
  }

  async function loadModels() {
    try {
      modelOptions.value = (await getAgentModels()) || [];
    } catch {
      modelOptions.value = [];
    }
  }

  /** 页面切换模型：绑定到当前会话，意图解析与章节生成都会使用 */
  async function onModelChange(modelId: string | undefined) {
    if (!conversationId.value) {
      return;
    }
    const m = modelOptions.value.find((x) => x.id === modelId);
    const label = m ? m.name : '';
    try {
      await bindModel(conversationId.value, modelId || '', label);
      currentModelId.value = modelId || undefined;
      const s = activeSession();
      if (s) {
        s.modelId = modelId || undefined;
        s.modelLabel = label || undefined;
        touchSession();
      }
    } catch {
      // 绑定失败回滚选择
      currentModelId.value = activeSession()?.modelId;
    }
  }

  function applyWatershed(ws: { lybm: string; lymc: string }, persist: boolean, silent = false) {
    boundWatershed.value = { lybm: ws.lybm, lymc: ws.lymc };
    const s = activeSession();
    if (s) {
      s.lybm = ws.lybm;
      s.lymc = ws.lymc;
      if (!silent) {
        touchSession();
      }
    }
    if (persist) {
      bindWatershed(conversationId.value, ws.lybm, ws.lymc).catch(() => {});
    }
  }

  /** 切换会话：对话区与章节面板整体跟随，互不串数据（force 用于进入页面时强制恢复当前会话） */
  async function switchSession(s: ISession, force = false) {
    if (!force && s.conversationId === conversationId.value) {
      return;
    }
    conversationId.value = s.conversationId;
    batchNo.value = s.batchNo;
    activeMessages.value = s.messages;
    boundWatershed.value = s.lybm ? { lybm: s.lybm!, lymc: s.lymc || s.lybm! } : null;
    currentModelId.value = s.modelId;
    usage.value = null;
    await restoreWatershed(s.conversationId);
  }

  async function createNewConversation() {
    if (loading.value) {
      return;
    }
    const res = await createConversation();
    const session: ISession = {
      conversationId: res?.conversationId || `local-${Date.now()}`,
      batchNo: res?.batchNo || 'B' + Date.now(),
      title: '新会话',
      messages: [stampMsg({ role: 'assistant', content: WELCOME })],
      updateTime: Date.now(),
    };
    sessions.value.unshift(session);
    usage.value = null;
    await switchSession(session);
  }

  /** 删除会话（仅供「彻底删除」入口调用，UI 侧必须先经二次确认提示数据全部清除） */
  function deleteSession(s: ISession) {
    const idx = sessions.value.findIndex((x) => x.conversationId === s.conversationId);
    if (idx < 0) {
      return;
    }
    sessions.value.splice(idx, 1);
    deletedMap[s.conversationId] = Date.now();
    // 该会话若还有后台生成轮次，先取消，避免结束时 saveTurn 把会话重新写回服务端
    cancelGenerate(s.conversationId).catch(() => {});
    deleteServerSession(s.conversationId).catch(() => {
      // 服务端删除失败只影响其他设备：本机墓碑仍然生效
      message.warning('服务端删除失败，其他设备刷新后可能仍显示该会话');
    });
    syncedTs.delete(s.conversationId);
    pendingPush.delete(s.conversationId);
    if (s.conversationId === conversationId.value) {
      const next = [...sessions.value].filter((x) => !x.archived).sort((a, b) => b.updateTime - a.updateTime)[0];
      if (next) {
        switchSession(next);
      } else {
        createNewConversation();
      }
    }
  }

  /** 归档会话：数据完整保留（含生成的报告），仅从主列表移入归档列表 */
  function archiveSession(s: ISession) {
    const target = sessions.value.find((x) => x.conversationId === s.conversationId);
    if (!target || target.archived) {
      return;
    }
    target.archived = true;
    target.generating = false;
    target.updateTime = Date.now();
    if (target.conversationId === conversationId.value) {
      const next = [...sessions.value].filter((x) => !x.archived).sort((a, b) => b.updateTime - a.updateTime)[0];
      if (next) {
        switchSession(next);
      } else {
        createNewConversation();
      }
    }
  }

  /** 取消归档：恢复到主列表 */
  function unarchiveSession(s: ISession) {
    const target = sessions.value.find((x) => x.conversationId === s.conversationId);
    if (!target || !target.archived) {
      return;
    }
    target.archived = false;
    target.updateTime = Date.now();
  }

  async function loadWatersheds() {
    try {
      watersheds.value = (await getWatersheds(batchNo.value)) || [];
    } catch {
      watersheds.value = [];
    }
  }

  async function loadQcIssues() {
    if (!batchNo.value || !boundWatershed.value?.lybm) {
      issues.value = [];
      return;
    }
    try {
      const rows = await getQcIssues(batchNo.value, boundWatershed.value.lybm);
      issues.value = (rows || []).map((r: any) => ({
        id: r.id,
        batchNo: r.batchNo ?? r.batch_no,
        lybm: r.lybm,
        chapterNo: Number(r.chapterNo ?? r.chapter_no),
        level: r.level,
        type: r.type,
        message: r.message,
      }));
    } catch {
      issues.value = [];
    }
  }

  /**
   * 按当前批次（及已绑定流域）拉章节。
   * 流域过滤为空时回退到「只按批次查」，避免对话已写完但前端尚未绑上 lybm 时右侧一直空白。
   */
  async function loadChapters(opts?: { fallbackAllInBatch?: boolean }) {
    if (!batchNo.value) {
      return;
    }
    try {
      const lybm = boundWatershed.value?.lybm || undefined;
      let rows = (await getChapterList(batchNo.value, lybm)) || [];
      if (!rows.length && lybm && opts?.fallbackAllInBatch !== false) {
        rows = (await getChapterList(batchNo.value)) || [];
      }
      chapters.value = rows;
      bindWatershedFromChapters(rows);
      await loadQcIssues();
      scheduleGenerationPoll();
    } catch (e) {
      console.error(e);
    }
  }

  /** 查询结果里有流域、前端还没绑定时，用章节上的 lybm/lymc 补上 */
  function bindWatershedFromChapters(rows: IChapter[]) {
    if (boundWatershed.value?.lybm || !rows.length) {
      return;
    }
    const codes = [...new Set(rows.map((r) => r.lybm).filter(Boolean))] as string[];
    if (codes.length !== 1) {
      return;
    }
    const hit = rows.find((r) => r.lybm === codes[0]);
    applyWatershed({ lybm: codes[0], lymc: hit?.lymc || codes[0] }, false, true);
  }

  /**
   * 手动刷新：先对齐会话上下文（批次/流域），再拉章节。
   * 对话显示生成完毕但右侧仍空时走这条，不依赖 SSE refresh。
   */
  async function refreshChapters(opts?: { silent?: boolean }): Promise<number> {
    refreshing.value = true;
    noteGenerationActivity();
    try {
      if (conversationId.value) {
        await restoreWatershed(conversationId.value);
      }
      await loadChapters({ fallbackAllInBatch: true });
      const n = chapters.value.length;
      if (!opts?.silent) {
        if (n) {
          message.success(`已加载 ${n} 个章节`);
        } else {
          message.info('当前批次暂未查到章节，可用下方查询指定批次或流域');
        }
      }
      return n;
    } finally {
      refreshing.value = false;
    }
  }

  /** 空面板单独查询：可改批次号 / 流域编码，不依赖当前会话绑定 */
  async function queryChapters(bn: string, lybm?: string): Promise<number> {
    const batch = (bn || '').trim();
    const code = (lybm || '').trim();
    if (!batch) {
      message.warning('请填写批次号');
      return 0;
    }
    refreshing.value = true;
    try {
      batchNo.value = batch;
      const s = activeSession();
      if (s) {
        s.batchNo = batch;
        touchSession();
      }
      if (code) {
        applyWatershed({ lybm: code, lymc: boundWatershed.value?.lymc || code }, false);
      } else {
        boundWatershed.value = null;
      }
      await loadChapters({ fallbackAllInBatch: true });
      const n = chapters.value.length;
      if (n) {
        message.success(`查到 ${n} 个章节`);
      } else {
        message.info('该批次下没有章节，请核对批次号或稍后再查');
      }
      return n;
    } catch (e) {
      console.error(e);
      message.error('查询章节失败');
      return 0;
    } finally {
      refreshing.value = false;
    }
  }

  /** 顶部批次号查询：切换查看的批次并重新拉取章节 */
  function setBatchNo(v: string) {
    if (v && v !== batchNo.value) {
      batchNo.value = v;
    } else if (v === batchNo.value) {
      // 同批次手动点查询时也要刷新
      loadWatersheds();
      loadChapters();
    }
  }

  /** SSE 进度事件的乐观更新：存在则改状态标签，避免等整表刷新 */
  function setProgress(chapterNo: number, status: string) {
    const ch = chapters.value.find((c) => c.chapterNo === chapterNo);
    if (ch) {
      ch.status = status;
    } else {
      // 列表还是空的：进度事件对不上卡片，立刻按批次把已落库的章节拉回来
      loadChapters({ fallbackAllInBatch: true });
    }
    if (String(status).includes('生成中')) {
      pollStartTs = 0;
    }
    noteGenerationActivity();
  }

  /**
   * 生成中状态轮询兜底：SSE 断线或页面刷新后收不到 progress/refresh 事件，
   * 章节面板会永远停在「生成中」。只要列表里还有生成中的章节就定期重拉，
   * 全部脱离生成中后自动停止；SSE 正常时事件照旧实时更新，轮询只是保险。
   * 列表为空但最近刚有生成活动时也继续拉，避免「对话写完、右侧仍空白」。
   */
  const POLL_INTERVAL = 8000;
  const POLL_MAX_MS = 30 * 60 * 1000;
  let pollTimer: ReturnType<typeof setTimeout> | null = null;
  let pollStartTs = 0;
  /** 空列表时继续轮询的截止时间（对话生成期间 / 手动刷新后） */
  let emptyPollUntil = 0;

  function noteGenerationActivity() {
    emptyPollUntil = Date.now() + 2 * 60 * 1000;
    scheduleGenerationPoll();
  }

  function hasGeneratingChapter(): boolean {
    return chapters.value.some((c) => String(c.status).includes('生成中'));
  }

  function shouldPollChapters(): boolean {
    if (hasGeneratingChapter()) {
      return true;
    }
    const generating = !!sessions.value.find((s) => s.conversationId === conversationId.value)?.generating;
    if (generating) {
      return true;
    }
    return chapters.value.length === 0 && Date.now() < emptyPollUntil;
  }

  function scheduleGenerationPoll() {
    // 单测环境不挂后台定时器，避免 Jest 因 open handle 挂起
    if (typeof process !== 'undefined' && process.env.JEST_WORKER_ID) {
      return;
    }
    if (pollTimer || !shouldPollChapters()) {
      return;
    }
    if (!pollStartTs) {
      pollStartTs = Date.now();
    }
    if (Date.now() - pollStartTs > POLL_MAX_MS) {
      // 超过兜底上限（后端可能已异常终止），停止轮询等待人工刷新
      return;
    }
    pollTimer = setTimeout(async () => {
      pollTimer = null;
      const keep = shouldPollChapters();
      const before = chapterSig();
      await loadChapters({ fallbackAllInBatch: true });
      // 整份报告 6 章并行 + 汇总 + 跨章校验常需十几分钟，SSE 断流后只剩这条兜底路径。
      // 只要落库仍在推进就续期，避免超过上限后卡片被永久钉在「生成中」。
      if (hasGeneratingChapter() && chapterSig() !== before) {
        pollStartTs = Date.now();
      }
      if (keep || shouldPollChapters()) {
        scheduleGenerationPoll();
      } else {
        pollStartTs = 0;
      }
    }, POLL_INTERVAL);
  }

  /** 章节状态/时间戳指纹：用于判断落库是否还在推进 */
  function chapterSig(): string {
    return chapters.value.map((c) => `${c.chapterNo}:${c.status}:${c.updateTime || ''}`).join('|');
  }

  function currentLymc() {
    const lybm = boundWatershed.value?.lybm || '';
    const w = watersheds.value.find((x) => x.wscd === lybm);
    const ch = chapters.value.find((c) => c.lybm === lybm);
    return w?.wsnm || ch?.lymc || boundWatershed.value?.lymc || lybm;
  }

  /**
   * 账号切换自愈：本 store 是模块级单例，而页面走 keep-alive（登出不会销毁组件），
   * 换了登录人若不清空，界面会把上一个用户的会话直接显示给新用户（跨账号数据泄露）。
   * 监听登录人变化，清空内存态与所有集合后按新用户重新恢复。
   */
  function resetForUserSwitch() {
    sessions.value = [];
    activeMessages.value = [];
    conversationId.value = '';
    batchNo.value = '';
    boundWatershed.value = null;
    currentModelId.value = undefined;
    usage.value = null;
    chapters.value = [];
    issues.value = [];
    watersheds.value = [];
    for (const id of Object.keys(deletedMap)) {
      delete deletedMap[id];
    }
    knownRemote.clear();
    pendingPush.clear();
    syncedTs.clear();
    savingNow.clear();
    void init();
  }

  watch(
    () => {
      const info = useUserStore().getUserInfo;
      return `${info?.username || ''}|${info?.id || ''}`;
    },
    (now, prev) => {
      // 首次进入时 prev 为 undefined，无需重置
      if (!prev || now === prev) {
        return;
      }
      resetForUserSwitch();
    }
  );

  watch(
    sessions,
    () => {
      if (saveTimer) {
        clearTimeout(saveTimer);
      }
      saveTimer = setTimeout(saveStore, 400);
    },
    { deep: true }
  );

  // 批次或流域变化后重新拉取章节（切会话、顶部切换、对话绑定流域都会走到这里）
  watch(
    () => [batchNo.value, boundWatershed.value?.lybm],
    () => {
      loadWatersheds();
      loadChapters();
    }
  );

  // 对话开始生成时，即使右侧还没有卡片也开始轮询，避免 SSE 丢事件后面板一直空白
  watch(
    () => sessions.value.find((s) => s.conversationId === conversationId.value)?.generating,
    (v) => {
      if (v) {
        noteGenerationActivity();
      }
    }
  );

  function sessionFingerprint(s: ISession) {
    const last = s.messages?.[s.messages.length - 1];
    return `${s.messages?.length || 0}|${last?.id || ''}|${last?.time || 0}|${(last?.content || '').length}|${s.lybm || ''}|${s.updateTime || 0}`;
  }

  function applyMergedSession(merged: ISession) {
    if (conversationId.value !== merged.conversationId) {
      return;
    }
    activeMessages.value = merged.messages;
    batchNo.value = merged.batchNo || batchNo.value;
    if (merged.lybm) {
      boundWatershed.value = { lybm: merged.lybm, lymc: merged.lymc || merged.lybm };
    }
    if (merged.modelId) {
      currentModelId.value = merged.modelId;
    }
  }

  /** 本次页面周期的起点：期间新建的会话可能还没推送成功，权威裁剪时要保护它们 */
let sessionEpoch = Date.now();
  /** 最近一次服务端同步是否失败：用于提示「当前显示本机缓存」，同一故障只提示一次 */
  let syncErrorNotified = false;

  /**
   * 多窗口/跨浏览器合并：按消息时间做并集，禁止用旧会话整段替换新对话。
   * 本窗口正在流式写入的会话不换数组，避免和 in-flight 的 msgs 引用拆开。
   *
   * @param opts.force 以服务端为准做权威裁剪（进入页面 / 用户手动刷新）：
   *   远端确认不存在的本地会话一律移除并落墓碑，绕过 knownRemote 间接判定——
   *   库里手工删掉的会话也能在界面上真正消失。
   * @param opts.silent 失败时不弹提示（后台轮询用，避免每30 秒打扰）
   * @returns 是否成功与服务端完成了一次对账
   */
  async function mergeServerSessions(opts?: { force?: boolean; silent?: boolean }): Promise<boolean> {
    const force = !!opts?.force;
    let remote;
    try {
      remote = (await listServerSessions()) || [];
    } catch (e) {
      // 拉取失败不能静默兜底：本机缓存与库表已经不一致，用户必须知道看到的是缓存
      if (!syncErrorNotified) {
        syncErrorNotified = true;
        if (!opts?.silent) {
          message.warning('会话同步失败：无法连接服务端，当前显示的是本机缓存数据');
        }
      }
      return false;
    }
    syncErrorNotified = false;
    const remoteIds = new Set<string>();
    for (const r of remote) {
      if (!r?.conversationId) {
        continue;
      }
      remoteIds.add(r.conversationId);
      knownRemote.add(r.conversationId);
      // 服务端已有该会话：撤销「推送失败」登记（可能刚刚才推送成功过）
      pendingPush.delete(r.conversationId);
      if (deletedMap[r.conversationId]) {
        continue;
      }
      const local = sessions.value.find((x) => x.conversationId === r.conversationId);
      if (local && loading.value && local.conversationId === conversationId.value) {
        continue;
      }
      if (local) {
        const merged = mergeSession(local, r as ISession);
        if (sessionFingerprint(local) === sessionFingerprint(merged)) {
          // 指纹相同只说明「没有可拉取的新内容」。推送水印必须停在已确认服务端拥有的
          // 远端时间上：本窗口可能持有服务端的超集（如上次推送失败后留下的新增消息），
          // 若把水印设成 max(本地,远端)，超集部分就永远不会再补推了。
          syncedTs.set(r.conversationId, Math.max(syncedTs.get(r.conversationId) || 0, r.updateTime || 0));
          continue;
        }
        const idx = sessions.value.findIndex((x) => x.conversationId === r.conversationId);
        sessions.value.splice(idx, 1, merged);
        // 同样只抬到远端时间：merged.updateTime = max(本地, 远端, 最新消息时间)，
        // 抬到它等于宣布「本地超集已入库」，下一次同步会以 updateTime <= 水印 为由跳过，
        // 结果是上次推送失败留下的消息再也不会补推，其他浏览器永远看不到这段对话。
        syncedTs.set(r.conversationId, Math.max(syncedTs.get(r.conversationId) || 0, r.updateTime || 0));
        applyMergedSession(merged);
      } else {
        const incoming = { ...(r as ISession), generating: false, messages: mergeMessages([], (r as ISession).messages || []) } as ISession;
        sessions.value.push(incoming);
        syncedTs.set(r.conversationId, incoming.updateTime || 0);
      }
    }
    // 服务端删除传播：远端已确认不存在 → 已在其他窗口/浏览器/直接在库里删掉了。
    // 本地同步移除并落墓碑；必须放在补推之前，否则会把已删会话原样推回服务端复活
    // （这正是「删除后刷新又出现」的根源）。
    //
    // 判定门槛：
    // - force（进入页面/手动刷新）：远端为准直接裁剪，让「库里手工删掉的会话」在界面上真正消失；
    //   但跳过两类会话——本轮新建尚未推送的、以及已登记推送失败的，它们本机是唯一副本。
    // - 后台轮询：沿用 knownRemote 判定，避免推送失败期间把本机独有数据误删
    let removedActive = false;
    if (!loading.value) {
      for (const s of [...sessions.value]) {
        if (deletedMap[s.conversationId] || remoteIds.has(s.conversationId)) {
          continue;
        }
        if (force) {
          if ((s.updateTime || 0) >= sessionEpoch || pendingPush.has(s.conversationId)) {
            continue;
          }
        } else if (!knownRemote.has(s.conversationId)) {
          continue;
        }
        const idx = sessions.value.findIndex((x) => x.conversationId === s.conversationId);
        if (idx >= 0) {
          sessions.value.splice(idx, 1);
        }
        deletedMap[s.conversationId] = Date.now();
        syncedTs.delete(s.conversationId);
        pendingPush.delete(s.conversationId);
        if (s.conversationId === conversationId.value) {
          removedActive = true;
        }
      }
    }
    // 双向对账：拉取成功后补推一次本地持有、服务端缺失的会话（幂等并集，重推无害）
    scheduleServerSync();
    if (removedActive) {
      const next = [...sessions.value].filter((x) => !x.archived).sort((a, b) => b.updateTime - a.updateTime)[0];
      if (next) {
        await switchSession(next);
      } else {
        await createNewConversation();
      }
    }
    return true;
  }

  let crossWindowSyncSetup = false;

  /** 多窗口联动：窗口聚焦/每 30 秒拉一次服务端合并
   *
   * 内置浏览器（IDE webview）里 document.hidden 常常长期为 true 且focus/visibilitychange
   * 不触发，原来的「hidden 就不拉」会让页面永远不对账；这里改成：前台 30s、后台兜底 120s 都拉，
   * 并用最小间隔节流，避免事件与定时器叠加打爆接口。
   */
  function setupCrossWindowSync() {
    if (crossWindowSyncSetup) {
      return;
    }
    crossWindowSyncSetup = true;
    const PULL_INTERVAL = 30_000;
    const PULL_INTERVAL_HIDDEN = 120_000;
    let lastPullAt = 0;
    const pull = async (opts?: { silent?: boolean; minInterval?: number }) => {
      const now = Date.now();
      if (opts?.minInterval && now - lastPullAt < opts.minInterval) {
        return;
      }
      lastPullAt = now;
      await mergeServerSessions({ silent: opts?.silent });
    };
    window.addEventListener('focus', () => pull());
    document.addEventListener('visibilitychange', () => pull());
    setInterval(() => pull({ minInterval: document.hidden ? PULL_INTERVAL_HIDDEN : PULL_INTERVAL - 2000 }), PULL_INTERVAL);
    // 同浏览器其他标签写入 localStorage 时立刻并入，而不是等 30 秒
    window.addEventListener('storage', (e) => {
      const watchKeys = [storeKey(), legacyStoreKey()].filter(Boolean);
      if (!e.key || !watchKeys.includes(e.key) || !e.newValue) {
        return;
      }
      try {
        const parsed = JSON.parse(e.newValue);
        // 其它标签页的删除墓碑与 known 集合要并入本页，否则本页深 watcher 落盘时
        // 会把已删会话原样写回 localStorage，刷新后死而复生
        if (parsed?.deleted && typeof parsed.deleted === 'object') {
          for (const id of Object.keys(parsed.deleted)) {
            deletedMap[id] = parsed.deleted[id];
          }
        }
        if (Array.isArray(parsed.known)) {
          for (const id of parsed.known) {
            knownRemote.add(id);
          }
        }
        if (Array.isArray(parsed.pending)) {
          for (const id of parsed.pending) {
            pendingPush.add(id);
          }
        }
        for (const id of Object.keys(deletedMap)) {
          const idx = sessions.value.findIndex((x) => x.conversationId === id);
          if (idx >= 0) {
            sessions.value.splice(idx, 1);
          }
        }
        const list: ISession[] = Array.isArray(parsed.sessions) ? parsed.sessions : [];
        for (const r of list) {
          if (!r?.conversationId || deletedMap[r.conversationId]) {
            continue;
          }
          const local = sessions.value.find((x) => x.conversationId === r.conversationId);
          if (local && loading.value && local.conversationId === conversationId.value) {
            continue;
          }
          if (local) {
            const merged = mergeSession(local, r);
            if (sessionFingerprint(local) === sessionFingerprint(merged)) {
              continue;
            }
            const idx = sessions.value.findIndex((x) => x.conversationId === r.conversationId);
            sessions.value.splice(idx, 1, merged);
            applyMergedSession(merged);
          } else {
            sessions.value.push({ ...r, generating: false, messages: mergeMessages([], r.messages || []) });
          }
        }
      } catch {
        // 其它标签写入损坏数据时忽略
      }
    });
  }

  /** 进入页面：恢复历史会话，没有则新建 */
  async function init() {
    loadModels();
    // 本次页面周期的起点：其后新建的会话在权威裁剪中受保护（可能还没推送成功）
    sessionEpoch = Date.now();
    loadDeletedMap();
    sessions.value = loadStore().filter((s) => !deletedMap[s.conversationId]);
    // 服务端会话恢复：与本机缓存按消息时间并集，不用旧列表整段替换。
    // force：进入页面即以服务端为准裁剪，本机残留/已在库里删掉的会话不会死而复生。
    const synced = await mergeServerSessions({ force: true, silent: true });
    if (!synced) {
      message.warning('会话同步失败：无法连接服务端，当前显示的是本机缓存数据');
    }
    setupCrossWindowSync();
    // 推送水印：服务端已有的会话由 mergeServerSessions 按远端时间设置（本地超集可补推）。
    // 只有「确认连上了服务端」时才给查不到的会话按本地时间抬水印，避免复活；
    // 拉取失败时保持无水印，网络恢复后的下一次变更仍能补推上去（否则那段时间的对话永久丢失）。
    if (synced) {
      for (const s of sessions.value) {
        // 已登记推送失败的会话绝不能抬水印：抬了它就永远不会再补推，
        // 服务端将永久缺这段对话，其他浏览器也看不到
        if (!syncedTs.has(s.conversationId) && !pendingPush.has(s.conversationId)) {
          syncedTs.set(s.conversationId, s.updateTime || 0);
        }
      }
    }
    if (sessions.value.length) {
      const latest = sessions.value.filter((s) => !s.archived).sort((a, b) => b.updateTime - a.updateTime)[0];
      if (latest) {
        await switchSession(latest, true);
        return;
      }
    }
    await createNewConversation();
  }

  /**
   * 手动强制刷新会话列表：以服务端为准做一次权威对账，
   * 库里的删除、其他窗口的改动都会立刻反映到界面上。
   */
  async function refreshSessions() {
    const ok = await mergeServerSessions({ force: true });
    if (ok) {
      message.success('会话列表已与服务端同步');
    }
  }

  /**
   * 清除本机缓存并重新按服务端恢复：内置浏览器/webview 常驻时localStorage
   * 不会被清理，出现「库里已删、界面仍在」且同步又失败时，用它强制回到服务端状态。
   */
  async function clearLocalCache() {
    for (const key of [storeKey(), legacyStoreKey()].filter(Boolean)) {
      try {
        localStorage.removeItem(key);
      } catch {
        // 存储不可用时忽略
      }
    }
    for (const id of Object.keys(deletedMap)) {
      delete deletedMap[id];
    }
    knownRemote.clear();
    pendingPush.clear();
    sessions.value = [];
    await init();
    message.success('本机缓存已清除，会话列表已按服务端重新恢复');
  }

  /** 地图点选生成：确保有会话后绑定流域 */
  async function consumePendingGenerate(lybm: string, lymc: string) {
    if (!lybm) {
      return;
    }
    if (!conversationId.value) {
      await init();
    }
    applyWatershed({ lybm, lymc: lymc || lybm }, true);
  }

  return {
    sessions,
    conversationId,
    batchNo,
    activeMessages,
    loading,
    boundWatershed,
    modelOptions,
    currentModelId,
    watersheds,
    chapters,
    issueMap,
    usage,
    applyUsage,
    canExport,
    allPassed,
    exportableChapters,
    passedCount,
    exporting,
    refreshing,
    exportPickerOpen,
    exportChapterNos,
    isExportable,
    openExportPicker,
    exportReport,
    downloadWord,
    init,
    refreshSessions,
    clearLocalCache,
    formatTime,
    activeSession,
    touchSession,
    onModelChange,
    applyWatershed,
    switchSession,
    createNewConversation,
    deleteSession,
    archiveSession,
    unarchiveSession,
    setBatchNo,
    setProgress,
    loadChapters,
    refreshChapters,
    queryChapters,
    currentLymc,
    consumePendingGenerate,
  };
}

let store: ReturnType<typeof createReportSession> | null = null;

export function useReportSession() {
  if (!store) {
    store = createReportSession();
  }
  return store;
}
