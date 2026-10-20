package org.jeecg.modules.airag.shreport.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.util.SpringContextUtils;
import org.jeecg.modules.airag.common.handler.AIChatParams;
import org.jeecg.modules.airag.llm.handler.AIChatHandler;
import org.jeecg.modules.airag.shreport.component.ShReportConcurrencyLimiter;
import org.jeecg.modules.airag.shreport.component.ShReportModelVariant;
import org.jeecg.modules.airag.shreport.component.ShReportSessionStore;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.jeecg.modules.airag.shreport.util.ReportWordBuilder;
import org.jeecg.modules.airag.shreport.component.ShReportUsageTracker;
import org.jeecg.modules.airag.shreport.util.ShReportIntentValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 山洪报告 AI 助手：规则优先解析意图，再必要时走模型；生成统一交给 {@link ShReportGenerateService}。
 */
@Slf4j
@Service
public class ShReportAgentService {

    /** 用户让系统自行挑选流域的表达（用于自动绑定，替代「无法自行选择」式拒绝） */
    private static final java.util.regex.Pattern PICKER_PATTERN =
            java.util.regex.Pattern.compile("(自己选|你选|随便|任意|都可以|选一个|来一个|挑一个)");

    private static final String INTENT_SYSTEM_PROMPT =
            "你是「山洪灾害风险隐患调查报告生成助手」的意图解析器。根据对话历史和用户最新消息，输出一个JSON对象（不要输出任何其他文字、不要使用代码块）：" +
            "{\"action\":\"...\",\"chapterNo\":数字或null,\"wsmc\":\"流域名称或null\",\"requirement\":\"用户对内容的补充要求\",\"reply\":\"给用户的中文回复\"}\n" +
            "action 取值：\n" +
            "GENERATE_ALL：用户想生成整份报告（首次生成或重新生成全部章节）；\n" +
            "GENERATE_CHAPTER：用户想生成或修改某一章节（chapterNo 1-7，根据章节标题判断：一流域概况=1，二前期准备=2，三外业调查成果=3，四影响分析=4，五历史山洪灾害情况=5，六防治区分析=6，七成果与建议=7）；\n" +
            "QUERY_STATUS：用户查询报告/章节生成进度或状态；\n" +
            "EXPORT：用户想导出 Word；\n" +
            "PREPARE_OUTLINE：用户想先看大纲/提纲/章节框架，确认后再生成（如\"先出个大纲\"\"列一下提纲\"）；\n" +
            "QUERY_DATA：用户想查询调查数据或统计信息（如\"当前有多少小流域\"\"防治对象按县区分布\"\"有哪些风险隐患\"\"桥涵坝阻水情况\"），不需要生成报告；\n" +
            "QUERY_KB：用户想查阅已入库的一期调查成果报告（知识库），如\"大崖沟成果报告摘要\"\"塔子沟一期报告的建议\"\"南峪沟原来怎么写的\"，不生成新报告；只要用户提到\"一期/成果报告/摘要/原来怎么写/范文\"或询问某流域报告里已有的写法，优先判为 QUERY_KB，QUERY_DATA 只用于查询平台实测统计数据；\n" +
            "CHAT：其他闲聊或咨询。\n" +
            "当 action 为 QUERY_DATA 时，需额外给出字段：\n" +
            "dataQuery（必填）取值：overview=总体概况（数量/人口汇总）；by_type=防治对象按类型统计；by_county=按县区统计；by_town=按乡镇统计；risk=风险隐患统计；culvert=桥涵坝阻水统计；gully=沟滩占地统计；list_objects=防治对象清单；list_watersheds=小流域清单；\n" +
            "scope：bound=问的是当前绑定流域（用户说\"当前流域\"\"该流域\"等）；all=全域统计（默认，可省略）；\n" +
            "county/town/ftp/keyword（均可选）：用户明确按县区、乡镇、对象类型过滤或给出名称关键词时填写。\n" +
            "规则：\n" +
            "1. 如果用户消息中包含具体的修改意见/要求（如\"第三章表格再细化\"\"语气更正式\"），把它放进 requirement；\n" +
            "2. 如果对话中从未出现流域名称且本次要生成报告或出大纲，从用户消息里提取流域名称放入 wsmc；提取不到时禁止用 CHAT 拒绝或追问，仍返回对应动作（GENERATE_ALL/PREPARE_OUTLINE，wsmc 置 null，requirement 只放对报告内容的补充要求，\u300c帮我生成（XX流域的）整份报告\u300d这类触发语本身不算要求，没有实质补充要求时置 null），系统会把流域候选列表列出来供用户点选；当用户让系统自行挑选流域（如「你自己选」「随便来一个」「选哪个都行」）时，同样 wsmc 置 null 返回 GENERATE_ALL，reply 简短表示将自行选择；\n" +
            "3. reply 用简洁中文说明你将执行的操作；\n" +
            "4. 必须严格输出合法 JSON，chapterNo 只能是 1-7 的整数或 null。\n" +
            "示例：\n" +
            "用户：帮我生成上九三公别河上游流域的整份报告\n" +
            "{\"action\":\"GENERATE_ALL\",\"chapterNo\":null,\"wsmc\":\"上九三公别河上游流域\",\"requirement\":null,\"reply\":\"好的，我将生成该流域的全部7个章节\"}\n" +
            "用户：第三章表格再细化一些，语气正式点\n" +
            "{\"action\":\"GENERATE_CHAPTER\",\"chapterNo\":3,\"wsmc\":null,\"requirement\":\"表格再细化，语气正式\",\"reply\":\"好的，按你的要求重新生成第三章\"}\n" +
            "用户：先给我列一份大纲看看\n" +
            "{\"action\":\"PREPARE_OUTLINE\",\"chapterNo\":null,\"wsmc\":null,\"requirement\":null,\"reply\":\"好的，我先整理一份章节提纲供你确认\"}\n" +
            "用户：当前有多少小流域\n" +
            "{\"action\":\"QUERY_DATA\",\"dataQuery\":\"overview\",\"scope\":\"all\",\"chapterNo\":null,\"wsmc\":null,\"requirement\":null,\"reply\":\"好的，我来查询全域小流域统计\"}\n" +
            "用户：当前流域的防治对象按类型分布如何\n" +
            "{\"action\":\"QUERY_DATA\",\"dataQuery\":\"by_type\",\"scope\":\"bound\",\"chapterNo\":null,\"wsmc\":null,\"requirement\":null,\"reply\":\"好的，我来统计当前流域的防治对象类型分布\"}\n" +
            "用户：大崖沟成果报告摘要写了什么\n" +
            "{\"action\":\"QUERY_KB\",\"chapterNo\":null,\"wsmc\":\"大崖沟流域\",\"requirement\":null,\"reply\":\"好的，我来查一期调查成果知识库\"}";

    @Autowired
    private ShReportSessionStore sessionStore;

    @Autowired
    private ShReportGenerateService generateService;

    @Autowired
    private AIChatHandler aiChatHandler;

    @Autowired
    private ShReportOutlineService outlineService;

    @Autowired
    private ShReportModelVariant modelVariantService;

    @Autowired
    private ShReportConcurrencyLimiter concurrencyLimiter;

    @Autowired
    private ShReportUsageTracker usageTracker;

    @Autowired
    private ShReportDataQueryService dataQueryService;

    @Autowired(required = false)
    private ShReportKnowledgeService knowledgeService;

    @Autowired(required = false)
    private ShReportMaterialService materialService;

    /** 会话消息服务端落库：对齐通用 AI 助手「后端单点写」的设计，客户端推送只作补充 */
    @Autowired
    private ShReportAgentSessionService agentSessionDao;

    /** 进行中对话轮的内容记录器（按 emitter 弱引用挂载），done 事件或连接结束时落库 */
    private final Map<SseEmitter, TurnRecorder> turnRecorders =
            Collections.synchronizedMap(new WeakHashMap<>());

    @org.springframework.beans.factory.annotation.Value("${shreport.agent.model-id:}")
    private String intentModelId;

    private final ExecutorService flowExecutor = Executors.newFixedThreadPool(3);

    /** 对话 SSE 调度：必须先把 emitter 交还给浏览器，再在后台推 token，否则整段回复会一次性刷出 */
    private final ExecutorService sseDispatch = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "shreport-chat-sse");
        t.setDaemon(true);
        return t;
    });

    /** 当前对话轮：停止按钮据此打断 SSE / TokenStream，而不是只写 cancelled 标记 */
    private static final class LiveChat {
        final SseEmitter emitter;
        final Object sseLock;
        /** 本轮请求标识：断线重连（/receive/{requestId}）据此找回对话轮 */
        final String requestId;
        final AtomicBoolean stop = new AtomicBoolean(false);
        volatile CountDownLatch streamLatch;
        /** 已发送的对话事件缓存（不含 usage/graph），重连时按序补发 */
        final List<Object[]> history = new ArrayList<>();
        /** 事件接入端：首个为原始连接，其余为断线重连追加 */
        final List<SseEmitter> sinks = new ArrayList<>();
        final Object sinkLock = new Object();
        volatile boolean completed = false;

        LiveChat(SseEmitter emitter, Object sseLock, String requestId) {
            this.emitter = emitter;
            this.sseLock = sseLock;
            this.requestId = requestId;
            sinks.add(emitter);
        }
    }

    /**
     * 一轮对话的消息记录器：把 text/delta 事件按前端气泡的拼接规则还原成正文，
     * 在 done 事件（或连接异常结束）时写入服务端会话，保证对话内容不依赖客户端回传。
     * text 事件之间以空行分隔、delta 直接续尾，与前端 AgentChat.readStream 的拼法一致；
     * choose 事件之后的 text 开新气泡，与前端的分段渲染对齐。
     */
    private static final class TurnRecorder {
        final String conversationId;
        final String userKey;
        final String userMessage;
        final long startTs = System.currentTimeMillis();
        private final List<StringBuilder> segments = new ArrayList<>();
        private boolean deltaMode = false;

        TurnRecorder(String conversationId, String userKey, String userMessage) {
            this.conversationId = conversationId;
            this.userKey = userKey;
            this.userMessage = userMessage;
            segments.add(new StringBuilder());
        }

        void append(String event, String data) {
            StringBuilder sb = segments.get(segments.size() - 1);
            if ("choose".equals(event)) {
                if (sb.length() > 0) {
                    segments.add(new StringBuilder());
                    deltaMode = false;
                }
                return;
            }
            if ("text".equals(event)) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append(data == null ? "" : data);
                deltaMode = false;
            } else if ("delta".equals(event)) {
                if (!deltaMode) {
                    if (sb.length() > 0) {
                        sb.append("\n\n");
                    }
                    deltaMode = true;
                }
                sb.append(data == null ? "" : data);
            }
        }
    }

    private final ConcurrentHashMap<String, LiveChat> liveChats = new ConcurrentHashMap<>();

    /** 事件源 emitter → 对话轮：sendEvent 据此把事件广播到所有接入端并写入重放缓存 */
    private final Map<SseEmitter, LiveChat> sourceIndex = Collections.synchronizedMap(new WeakHashMap<>());

    /** requestId → 对话轮：重连入口；轮次结束后缓存保留供补拉，同一会话开启新一轮时回收 */
    private final ConcurrentHashMap<String, LiveChat> liveByRequestId = new ConcurrentHashMap<>();

    public boolean handleChat(String conversationId, String userMessage, String batchNoParam, String requestId, SseEmitter emitter) {
        Object sseLock = new Object();
        sessionStore.beginRun(conversationId);
        LiveChat live = new LiveChat(emitter, sseLock, requestId);
        LiveChat prev = liveChats.put(conversationId, live);
        if (prev != null && prev.requestId != null) {
            // 同一会话开启新一轮：上一轮的重连缓存可以回收了
            liveByRequestId.remove(prev.requestId);
        }
        if (requestId != null && !requestId.isEmpty()) {
            liveByRequestId.put(requestId, live);
        }
        sourceIndex.put(emitter, live);
        usageTracker.register(conversationId, emitter, sseLock);
        // 原始连接关闭（正常完成/超时）时同步关闭所有重连接入端
        emitter.onCompletion(() -> finishSinks(live));
        emitter.onTimeout(() -> finishSinks(live));
        // 用户身份必须在请求线程上解析（SSE 后台线程拿不到请求上下文）
        turnRecorders.put(emitter, new TurnRecorder(conversationId, requestUserKey(), userMessage));
        sseDispatch.execute(() -> {
            boolean generateRunning = false;
            try {
                if (live.stop.get() || sessionStore.isCancelled(conversationId)) {
                    sendEvent(emitter, sseLock, "text", "已停止。");
                    sendEvent(emitter, sseLock, "done", "cancelled");
                    return;
                }
                generateRunning = doHandleChat(conversationId, userMessage, batchNoParam, emitter, sseLock, live);
            } catch (Exception e) {
                log.error("山洪报告助手处理对话失败 conv={}", conversationId, e);
                sendEvent(emitter, sseLock, "error", "处理失败：" + e.getMessage());
                sendEvent(emitter, sseLock, "done", "ok");
            } finally {
                // 章节生成由 startGenerate 自己 complete/unregister；普通对话在这里收尾
                if (!generateRunning) {
                    // 流意外中断（未走到 done）时也把已产生的内容补写进服务端会话
                    TurnRecorder leftover = turnRecorders.remove(emitter);
                    if (leftover != null) {
                        saveTurn(leftover);
                    }
                    liveChats.remove(conversationId, live);
                    usageTracker.unregister(conversationId);
                    try {
                        emitter.complete();
                    } catch (Exception ignore) {
                    }
                }
            }
        });
        return true;
    }

    private boolean doHandleChat(String conversationId, String userMessage, String batchNoParam, SseEmitter emitter,
                                 Object sseLock, LiveChat live) {
        JSONObject ctx = sessionStore.getOrCreate(conversationId, batchNoParam);
        sessionStore.save(conversationId, ctx);
        try {
            sendEvent(emitter, sseLock, "text", "收到，我先分析一下你的需求…");
            if (stopped(conversationId, live)) {
                sendEvent(emitter, sseLock, "text", "已停止。");
                sendEvent(emitter, sseLock, "done", "cancelled");
                return false;
            }
            JSONObject intent = parseIntent(conversationId, ctx, userMessage);
            if (stopped(conversationId, live)) {
                sendEvent(emitter, sseLock, "text", "已停止。");
                sendEvent(emitter, sseLock, "done", "cancelled");
                return false;
            }
            applyWatershedHint(conversationId, ctx, intent);
            String action = intent.getString("action");
            String reply = intent.getString("reply");
            String requirement = intent.getString("requirement");
            String batchNo = ctx.getString("batchNo");

            if (!ensureWatershed(conversationId, ctx, intent, emitter, sseLock)) {
                appendHistory(ctx, conversationId, userMessage, intent.toJSONString());
                sendEvent(emitter, sseLock, "done", "ok");
                return false;
            }
            switch (action == null ? "CHAT" : action) {
                case "GENERATE_ALL":
                    sendEvent(emitter, sseLock, "text", reply == null ? "好的，开始生成全部 7 个章节。" : reply);
                    boolean allStarted = startGenerate(conversationId, ctx, requirement, null, emitter, sseLock);
                    appendHistory(ctx, conversationId, userMessage, intent.toJSONString());
                    return allStarted;
                case "GENERATE_CHAPTER": {
                    Integer chapterNo = intent.getInteger("chapterNo");
                    if (chapterNo == null || chapterNo < 1 || chapterNo > 7) {
                        sendEvent(emitter, sseLock, "text", "请告诉我要生成/修改第几章（1-7）。" + (reply == null ? "" : reply));
                        sendEvent(emitter, sseLock, "done", "ok");
                        appendHistory(ctx, conversationId, userMessage, intent.toJSONString());
                        return false;
                    }
                    sendEvent(emitter, sseLock, "text", reply == null
                            ? "好的，正在生成《" + ShReportConstants.chapterName(chapterNo) + "》。"
                            : reply);
                    boolean chStarted = startGenerate(conversationId, ctx, requirement, chapterNo, emitter, sseLock);
                    appendHistory(ctx, conversationId, userMessage, intent.toJSONString());
                    return chStarted;
                }
                case "QUERY_STATUS":
                    sendEvent(emitter, sseLock, "text", buildStatusReply(batchNo, ctx.getString("lybm"), reply));
                    sendEvent(emitter, sseLock, "refresh", batchNo);
                    sendEvent(emitter, sseLock, "done", "ok");
                    break;
                case "EXPORT":
                    handleExport(ctx, emitter, sseLock, reply, intent.getInteger("chapterNo"));
                    break;
                case "PREPARE_OUTLINE":
                    startOutline(conversationId, ctx, requirement, emitter, sseLock);
                    break;
                case "QUERY_DATA": {
                    // 数据问答不要求绑定流域；scope=bound（问"当前流域"）或用户点名了流域（wsmc 已被自动绑定）时按流域过滤
                    boolean bound = "bound".equals(intent.getString("scope"))
                            || (!empty(intent.getString("wsmc")) && !empty(ctx.getString("lybm")));
                    String lybm = ctx.getString("lybm");
                    if (bound && empty(lybm)) {
                        sendEvent(emitter, sseLock, "text", "你还没有确定要查哪个流域。请告诉我流域名称（例如「上九三公别河上游流域有多少防治对象」），我先帮你锁定流域再统计。");
                        sendEvent(emitter, sseLock, "done", "ok");
                        break;
                    }
                    try {
                        sendEvent(emitter, sseLock, "text", dataQueryService.query(intent.getString("dataQuery"),
                                intent.getString("county"), intent.getString("town"), intent.getString("ftp"),
                                intent.getString("keyword"), bound ? lybm : null));
                    } catch (Exception e) {
                        log.error("山洪报告数据问答查询失败 conv={} query={}", conversationId, intent.getString("dataQuery"), e);
                        sendEvent(emitter, sseLock, "text", "数据查询失败：" + e.getMessage());
                    }
                    sendEvent(emitter, sseLock, "done", "ok");
                    break;
                }
                case "QUERY_KB": {
                    handleQueryKb(conversationId, ctx, intent, userMessage, emitter, sseLock);
                    break;
                }
                default:
                    streamChatReply(conversationId, ctx, userMessage, reply, emitter, sseLock);
                    sendEvent(emitter, sseLock, "done", "ok");
            }
            appendHistory(ctx, conversationId, userMessage, intent.toJSONString());
            return false;
        } catch (Exception e) {
            log.error("山洪报告助手处理对话失败 conv={}", conversationId, e);
            sendEvent(emitter, sseLock, "error", "处理失败：" + e.getMessage());
            sendEvent(emitter, sseLock, "done", "ok");
            return false;
        }
    }

    public JSONObject bindWatershed(String conversationId, String lybm, String lymc) {
        JSONObject ctx = sessionStore.getOrCreate(conversationId, null);
        ctx.put("lybm", lybm);
        ctx.put("lymc", lymc);
        ctx.put("wsmc", lymc);
        sessionStore.save(conversationId, ctx);
        return ctx;
    }

    /** 页面模型选择：绑定已激活模型；modelId 传空表示恢复系统默认 */
    public JSONObject bindModel(String conversationId, String modelId, String modelLabel) {
        JSONObject ctx = sessionStore.getOrCreate(conversationId, null);
        if (modelId == null || modelId.isBlank()) {
            ctx.remove("modelId");
            ctx.remove("modelLabel");
        } else {
            if (!modelVariantService.isModelActive(modelId.trim())) {
                throw new IllegalArgumentException("模型不存在或未激活：" + modelId);
            }
            ctx.put("modelId", modelId.trim());
            ctx.put("modelLabel", modelLabel);
        }
        sessionStore.save(conversationId, ctx);
        return ctx;
    }

    /** 本会话实际生效的模型：会话选择优先，其次配置项，最后系统默认（返回 null） */
    private String resolveModelId(JSONObject ctx) {
        String sessionModel = ctx == null ? null : ctx.getString("modelId");
        if (!empty(sessionModel)) {
            return sessionModel;
        }
        return empty(intentModelId) ? null : intentModelId;
    }

    public void cancel(String conversationId) {
        JSONObject ctx = sessionStore.getOrCreate(conversationId, null);
        sessionStore.setCancelled(conversationId, true);
        generateService.cancelGenerating(ctx.getString("batchNo"), ctx.getString("lybm"), "用户取消");
        LiveChat live = liveChats.get(conversationId);
        if (live == null) {
            return;
        }
        live.stop.set(true);
        CountDownLatch latch = live.streamLatch;
        if (latch != null) {
            latch.countDown();
        }
        sendEvent(live.emitter, live.sseLock, "text", "已停止。已生成的章节会保留。");
        sendEvent(live.emitter, live.sseLock, "refresh", ctx.getString("batchNo"));
        sendEvent(live.emitter, live.sseLock, "done", "cancelled");
        try {
            live.emitter.complete();
        } catch (Exception ignore) {
        }
    }

    private boolean stopped(String conversationId, LiveChat live) {
        return (live != null && live.stop.get()) || sessionStore.isCancelled(conversationId);
    }

    public JSONObject contextSnapshot(String conversationId) {
        JSONObject ctx = sessionStore.get(conversationId);
        if (ctx == null) {
            return new JSONObject();
        }
        JSONObject out = new JSONObject();
        out.put("batchNo", ctx.getString("batchNo"));
        out.put("lybm", ctx.getString("lybm"));
        out.put("lymc", ctx.getString("lymc"));
        out.put("wsmc", ctx.getString("wsmc"));
        out.put("modelId", ctx.getString("modelId"));
        out.put("modelLabel", ctx.getString("modelLabel"));
        JSONObject usage = usageTracker.snapshot(conversationId);
        if (usage != null) {
            out.put("usage", usage);
        }
        if (materialService != null && materialService.enabled()) {
            String lybm = ctx.getString("lybm");
            String batchNo = ctx.getString("batchNo");
            try {
                out.put("materials", materialService.list(conversationId, lybm, batchNo));
                out.put("materialPending", materialService.countUnready(lybm, batchNo));
            } catch (Exception e) {
                log.debug("资料快照失败: {}", e.getMessage());
            }
        }
        return out;
    }

    /** 启动生成流程；返回 false 表示未启动（并发已满，已向用户提示） */
    private boolean startGenerate(String conversationId, JSONObject ctx, String requirement, Integer chapterNo,
                                  SseEmitter emitter, Object sseLock) {
        // 并发闸门：大模型处理耗时较长，超过上限直接提示，避免全部排队到模型超时
        if (!concurrencyLimiter.tryStartTask()) {
            sendEvent(emitter, sseLock, "text", concurrencyLimiter.busyMessage());
            sendEvent(emitter, sseLock, "refresh", ctx.getString("batchNo"));
            sendEvent(emitter, sseLock, "done", "ok");
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
            return false;
        }
        String batchNo = ctx.getString("batchNo");
        String lybm = ctx.getString("lybm");
        String lymc = ctx.getString("lymc");
        ShReportGenerateService.ProgressListener listener = new ShReportGenerateService.ProgressListener() {
            @Override
            public void onText(String text) {
                sendEvent(emitter, sseLock, "text", text);
            }

            @Override
            public void onProgress(int no, String status, String message) {
                JSONObject p = new JSONObject();
                p.put("chapterNo", no);
                p.put("status", status);
                p.put("message", message);
                p.put("batchNo", batchNo);
                sendEvent(emitter, sseLock, "progress", p.toJSONString());
            }

            @Override
            public void onRefresh(String bn) {
                sendEvent(emitter, sseLock, "refresh", bn);
            }

            @Override
            public boolean isCancelled() {
                return sessionStore.isCancelled(conversationId);
            }

            @Override
            public void onGraphNode(String node, String phase, Map<String, Object> snapshot) {
                JSONObject p = new JSONObject();
                if (snapshot != null) {
                    p.putAll(snapshot);
                }
                p.put("node", node);
                p.put("phase", phase);
                p.put("batchNo", batchNo);
                sendEvent(emitter, sseLock, "graph", p.toJSONString());
            }

            @Override
            public void onUsage(Map<String, Object> usage) {
                if (usage == null || usage.isEmpty()) {
                    return;
                }
                usageTracker.record(conversationId, new JSONObject(usage));
            }
        };
        AtomicBoolean heartbeatStop = new AtomicBoolean(false);
        startHeartbeat(emitter, sseLock, heartbeatStop);
        if (materialService != null && materialService.enabled()) {
            try {
                int pending = materialService.countUnready(lybm, batchNo);
                if (pending > 0) {
                    sendEvent(emitter, sseLock, "text",
                            pending + " 份资料尚未解析完成，本次生成只使用已解析资料。可等解析完成后再重新生成本章。");
                }
            } catch (Exception e) {
                log.debug("检查未解析资料失败: {}", e.getMessage());
            }
        }
        try {
            flowExecutor.submit(() -> {
                try {
                    String outline = ctx.getString("outline");
                    String modelId = resolveModelId(ctx);
                    if (chapterNo == null) {
                        generateService.generateAll(batchNo, lybm, lymc, requirement, outline, modelId, listener);
                    } else {
                        generateService.generateChapter(batchNo, lybm, lymc, chapterNo, requirement, outline, modelId, listener);
                    }
                    sendEvent(emitter, sseLock, "done", "ok");
                } catch (Exception e) {
                    log.error("山洪报告助手生成失败 conv={}", conversationId, e);
                    sendEvent(emitter, sseLock, "error", "生成失败：" + e.getMessage());
                    sendEvent(emitter, sseLock, "done", "ok");
                } finally {
                    concurrencyLimiter.finishTask();
                    usageTracker.unregister(conversationId);
                    heartbeatStop.set(true);
                    liveChats.remove(conversationId);
                    try {
                        emitter.complete();
                    } catch (Exception ignore) {
                    }
                }
            });
        } catch (RuntimeException e) {
            // 提交失败（线程池不可用等）：立即归还名额，避免永久占用并发额度
            concurrencyLimiter.finishTask();
            usageTracker.unregister(conversationId);
            heartbeatStop.set(true);
            liveChats.remove(conversationId);
            log.error("山洪报告生成任务提交失败 conv={}", conversationId, e);
            sendEvent(emitter, sseLock, "error", "生成任务提交失败：" + e.getMessage());
            sendEvent(emitter, sseLock, "done", "ok");
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
            return false;
        }
        return true;
    }

    /**
     * SSE 心跳：章节生成期间可能数分钟无任何事件，代理/浏览器会按空闲超时断流，
     * 断流后前端收不到后续章节完成与 done 事件，表现为「一直转圈」。
     */
    private void startHeartbeat(SseEmitter emitter, Object sseLock, AtomicBoolean stop) {
        Thread t = new Thread(() -> {
            while (!stop.get()) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    return;
                }
                if (stop.get()) {
                    return;
                }
                synchronized (sseLock) {
                    try {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } catch (Exception e) {
                        return;
                    }
                }
            }
        }, "shreport-sse-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    /** 两阶段生成之阶段一：产出大纲并推送 outline 事件，生成动作暂存 pendingAction，等用户确认 */
    private void startOutline(String conversationId, JSONObject ctx, String requirement,
                              SseEmitter emitter, Object sseLock) {
        String lymc = ctx.getString("lymc");
        com.alibaba.fastjson.JSONArray outline = outlineService.buildOutline(conversationId, lymc, requirement);
        // 暂存待放行动作：默认生成整份；后续 confirmOutline 消费
        JSONObject pending = new JSONObject();
        pending.put("action", "GENERATE_ALL");
        if (requirement != null && !requirement.isEmpty()) {
            pending.put("requirement", requirement);
        }
        ctx.put("pendingAction", pending);
        ctx.remove("outline");
        sessionStore.save(conversationId, ctx);
        sendEvent(emitter, sseLock, "text", "已整理好《" + lymc + "》报告的写作提纲，请在弹窗中勾选小节、补充要求后确认，我将严格按提纲生成。");
        sendEvent(emitter, sseLock, "outline", outline.toJSONString());
        sendEvent(emitter, sseLock, "done", "ok");
    }

    /** 两阶段生成之阶段二：用户确认大纲后放行暂存的生成动作（返回 true 表示流程异步执行中） */
    public boolean confirmOutline(String conversationId, com.alibaba.fastjson.JSONArray outline, String userMessage,
                                  String requestId, SseEmitter emitter) {
        JSONObject ctx = sessionStore.get(conversationId);
        Object sseLock = new Object();
        sessionStore.beginRun(conversationId);
        LiveChat live = new LiveChat(emitter, sseLock, requestId);
        LiveChat prev = liveChats.put(conversationId, live);
        if (prev != null && prev.requestId != null) {
            liveByRequestId.remove(prev.requestId);
        }
        if (requestId != null && !requestId.isEmpty()) {
            liveByRequestId.put(requestId, live);
        }
        sourceIndex.put(emitter, live);
        usageTracker.register(conversationId, emitter, sseLock);
        emitter.onCompletion(() -> finishSinks(live));
        emitter.onTimeout(() -> finishSinks(live));
        turnRecorders.put(emitter, new TurnRecorder(conversationId, requestUserKey(), userMessage));
        if (ctx == null) {
            sendEvent(emitter, sseLock, "error", "会话不存在或已过期，请重新发起对话。");
            sendEvent(emitter, sseLock, "done", "ok");
            liveChats.remove(conversationId, live);
            return false;
        }
        JSONObject pending = ctx.getJSONObject("pendingAction");
        if (pending == null) {
            sendEvent(emitter, sseLock, "text", "没有待确认的大纲。你可以直接说「生成整份报告」，或先说「先出一份大纲」。");
            sendEvent(emitter, sseLock, "done", "ok");
            liveChats.remove(conversationId, live);
            return false;
        }
        ctx.put("outline", outline.toJSONString());
        String notes = ShReportOutlineService.collectNotes(outline);
        String requirement = pending.getString("requirement");
        if (notes != null) {
            requirement = requirement == null ? notes : requirement + "\n" + notes;
        }
        // 一次性消费，避免后续普通指令被旧的大纲暂存动作干扰
        ctx.remove("pendingAction");
        sessionStore.save(conversationId, ctx);

        String action = pending.getString("action");
        Integer chapterNo = pending.getInteger("chapterNo");
        if ("GENERATE_CHAPTER".equals(action) && chapterNo != null
                && chapterNo >= 1 && chapterNo <= ShReportConstants.CHAPTER_COUNT) {
            sendEvent(emitter, sseLock, "text", "大纲已确认，开始按提纲生成《" + ShReportConstants.chapterName(chapterNo) + "》。");
            boolean started = startGenerate(conversationId, ctx, requirement, chapterNo, emitter, sseLock);
            if (!started) {
                liveChats.remove(conversationId, live);
            }
            return started;
        }
        sendEvent(emitter, sseLock, "text", "大纲已确认，开始按提纲生成全部 7 个章节。");
        boolean started = startGenerate(conversationId, ctx, requirement, null, emitter, sseLock);
        if (!started) {
            liveChats.remove(conversationId, live);
        }
        return started;
    }

    /** 未绑定流域时：0 条追问，1 条自动绑定，多条推 choose 并记下 pendingAction */
    private boolean ensureWatershed(String conversationId, JSONObject ctx, JSONObject intent,
                                    SseEmitter emitter, Object sseLock) {
        String action = intent.getString("action");
        boolean needWs = "GENERATE_ALL".equals(action) || "GENERATE_CHAPTER".equals(action)
                || "EXPORT".equals(action) || "PREPARE_OUTLINE".equals(action);
        if (!needWs) {
            return true;
        }
        if (!empty(ctx.getString("lybm"))) {
            sendWatershed(emitter, sseLock, ctx);
            return true;
        }
        String hint = firstNonEmpty(intent.getString("wsmc"), ctx.getString("wsmc"));
        // 兜底：规则/模型都没抽出 wsmc 时，用整句再试一次（用户常直接说库里的准确名称，如「上九三公别河上游」）
        if (hint == null) {
            hint = intent.getString("requirement");
        }
        List<Map<String, Object>> hits = hint == null ? List.of() : generateService.searchWatersheds(hint);
        if (hits.size() == 1) {
            Map<String, Object> one = hits.get(0);
            ctx.put("lybm", String.valueOf(one.get("lybm")));
            ctx.put("lymc", String.valueOf(one.get("lymc")));
            ctx.put("wsmc", ctx.getString("lymc"));
            sessionStore.save(conversationId, ctx);
            sendWatershed(emitter, sseLock, ctx);
            return true;
        }
        if (hits.size() > 1) {
            ctx.put("pendingAction", intent);
            sessionStore.save(conversationId, ctx);
            sendEvent(emitter, sseLock, "text", "找到多个同名/相近流域，请点选一个：");
            sendEvent(emitter, sseLock, "choose", JSON.toJSONString(hits));
            return false;
        }
        ctx.put("pendingAction", intent);
        sessionStore.save(conversationId, ctx);
        // 用户让系统自行挑选（「你自己选」「随便来一个」）：自动绑定一个可用流域并继续，不再拒绝
        boolean picker = hint != null && PICKER_PATTERN.matcher(hint).find();
        if (picker) {
            List<Map<String, Object>> pool = generateService.listWatersheds(1);
            if (!pool.isEmpty()) {
                Map<String, Object> one = pool.get(0);
                ctx.put("lybm", String.valueOf(one.get("lybm")));
                ctx.put("lymc", String.valueOf(one.get("lymc")));
                ctx.put("wsmc", ctx.getString("lymc"));
                ctx.remove("pendingAction");
                sessionStore.save(conversationId, ctx);
                sendWatershed(emitter, sseLock, ctx);
                sendEvent(emitter, sseLock, "text", "好的，已为你选择流域「" + ctx.getString("lymc") + "」，继续处理。");
                return true;
            }
        }
        // 没有精确命中：按相似度推荐候选流域供点选；未提供名称或相似度都不达标时，直接展示流域清单
        List<Map<String, Object>> fuzzy = hint == null
                ? List.of()
                : generateService.suggestWatersheds(hint);
        boolean matched = !fuzzy.isEmpty();
        if (fuzzy.isEmpty()) {
            fuzzy = generateService.listWatersheds(8);
        }
        if (!fuzzy.isEmpty()) {
            sendEvent(emitter, sseLock, "text", matched
                    ? "没有精确匹配到「" + hint + "」的流域，请点选最接近的一个："
                    : "请从下面点选要生成报告的流域：");
            sendEvent(emitter, sseLock, "choose", JSON.toJSONString(fuzzy));
            return false;
        }
        sendEvent(emitter, sseLock, "text", "知识库中暂时没有可用的流域清单。请直接告诉我流域名称（例如「生成上九三公别河上游的报告」），我会锁定后继续。");
        return false;
    }

    private void applyWatershedHint(String conversationId, JSONObject ctx, JSONObject intent) {
        String confirm = intent.getString("confirmLybm");
        if (!empty(confirm)) {
            ctx.put("lybm", confirm);
            ctx.put("lymc", firstNonEmpty(intent.getString("confirmLymc"), ctx.getString("lymc")));
            ctx.put("wsmc", ctx.getString("lymc"));
            JSONObject pending = ctx.getJSONObject("pendingAction");
            if (pending != null) {
                intent.put("action", pending.getString("action"));
                if (pending.get("chapterNo") != null) {
                    intent.put("chapterNo", pending.getInteger("chapterNo"));
                }
                if (empty(intent.getString("requirement"))) {
                    intent.put("requirement", pending.getString("requirement"));
                }
                ctx.remove("pendingAction");
            }
            sessionStore.save(conversationId, ctx);
            return;
        }
        if (!empty(intent.getString("wsmc"))) {
            ctx.put("wsmc", intent.getString("wsmc"));
            if (empty(ctx.getString("lybm"))) {
                List<Map<String, Object>> hits = generateService.searchWatersheds(intent.getString("wsmc"));
                if (hits.size() == 1) {
                    ctx.put("lybm", String.valueOf(hits.get(0).get("lybm")));
                    ctx.put("lymc", String.valueOf(hits.get(0).get("lymc")));
                }
            }
            sessionStore.save(conversationId, ctx);
        }
    }

    private void handleExport(JSONObject ctx, SseEmitter emitter, Object sseLock, String reply, Integer chapterNo) {
        String batchNo = ctx.getString("batchNo");
        String lybm = ctx.getString("lybm");
        List<Map<String, Object>> rows = generateService.listChapters(batchNo, lybm);
        String chapterNos = chapterNo != null && chapterNo >= 1 && chapterNo <= ShReportConstants.CHAPTER_COUNT
                ? String.valueOf(chapterNo) : "";
        List<Map<String, Object>> exportRows = ReportWordBuilder.selectExportChapters(rows, chapterNos);
        if (exportRows.isEmpty()) {
            sendEvent(emitter, sseLock, "text", "当前没有可导出的章节内容，请先生成后再导出。" + buildStatusReply(batchNo, lybm, reply));
            sendEvent(emitter, sseLock, "refresh", batchNo);
            sendEvent(emitter, sseLock, "done", "ok");
            return;
        }
        String payload = batchNo + "|" + lybm;
        if (!chapterNos.isEmpty()) {
            payload = payload + "|" + chapterNos;
        }
        String scope = chapterNos.isEmpty() ? "已有章节" : ("《" + ShReportConstants.chapterName(chapterNo) + "》");
        sendEvent(emitter, sseLock, "text", "开始下载 Word（" + scope + "，未生成或生成中的章节会跳过）。");
        sendEvent(emitter, sseLock, "download", payload);
        sendEvent(emitter, sseLock, "refresh", batchNo);
        sendEvent(emitter, sseLock, "done", "ok");
    }

    private void handleQueryKb(String conversationId, JSONObject ctx, JSONObject intent, String userMessage,
                               SseEmitter emitter, Object sseLock) {
        if (knowledgeService == null || !knowledgeService.available()) {
            sendEvent(emitter, sseLock, "text", "调查成果知识库尚未就绪。请先执行 `python ai_flows/import_sh_survey_kb.py` 导入一期报告。");
            sendEvent(emitter, sseLock, "done", "ok");
            return;
        }
        String lymc = firstNonEmpty(intent.getString("wsmc"), ctx.getString("lymc"), ctx.getString("wsmc"));
        // 多轮追问：取最近两轮用户消息并入检索，让「那塔子沟呢」这类指代能接上上文
        List<String> recentMsgs = recentUserMsgs(ctx);
        ShReportKnowledgeService.KbResult kb = knowledgeService.searchForAnswer(userMessage, lymc, recentMsgs);
        if (kb.isEmpty()) {
            sendEvent(emitter, sseLock, "text", "知识库没有检索到相关内容。目前收录的是白龙江水系大崖沟、塔子沟、噶沟里、南峪沟一期成果报告。");
            sendEvent(emitter, sseLock, "done", "ok");
            return;
        }
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from("你是山洪调查成果助手。只根据给定的检索片段回答，不要编造片段中没有的数字或对象。"));
            StringBuilder question = new StringBuilder();
            if (!recentMsgs.isEmpty()) {
                question.append("最近对话（仅用于理解本次追问的指代）：\n");
                for (String m : recentMsgs) {
                    question.append("用户：").append(m).append('\n');
                }
                question.append('\n');
            }
            question.append("用户问题：").append(userMessage).append("\n\n检索片段：\n").append(kb.getContext());
            messages.add(UserMessage.from(question.toString()));
            AIChatParams params = new AIChatParams();
            params.setTemperature(0.2);
            sendEvent(emitter, sseLock, "kb_sources", JSON.toJSONString(kb.getSources()));
            streamLlmTokens(conversationId, ctx, messages, params, "kb", emitter, sseLock);
        } catch (Exception e) {
            log.warn("调查成果知识库问答失败：{}", e.getMessage());
            sendEvent(emitter, sseLock, "kb_sources", JSON.toJSONString(kb.getSources()));
            sendEvent(emitter, sseLock, "text", "检索到以下片段：\n\n" + kb.getContext() + "\n\n" + buildSourcesText(kb.getSources()));
        }
        sendEvent(emitter, sseLock, "done", "ok");
    }

    /** 会话历史里最近的两条用户消息（去掉「用户：」前缀），用于知识库问答的追问指代 */
    private List<String> recentUserMsgs(JSONObject ctx) {
        List<String> out = new ArrayList<>();
        JSONArray history = ctx.getJSONArray("history");
        if (history == null) {
            return out;
        }
        for (int i = history.size() - 1; i >= 0 && out.size() < 2; i--) {
            String h = history.getString(i);
            if (h != null && h.startsWith("用户：")) {
                out.add(0, h.substring("用户：".length()));
            }
        }
        return out;
    }

    /** 来源清单的 Markdown 文本，附在回答正文后 */
    private static String buildSourcesText(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("📎 参考来源（一期调查成果知识库）：\n");
        int i = 1;
        for (Map<String, Object> s : sources) {
            sb.append(i++).append(". ").append(s.get("docName"));
            if (s.get("score") != null) {
                sb.append("（相似度 ").append(s.get("score")).append("）");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private JSONObject parseIntent(String conversationId, JSONObject ctx, String userMessage) {
        JSONObject rule = ruleBasedIntent(userMessage);
        String ruleAction = rule.getString("action");
        if (rule.getString("confirmLybm") != null
                || "QUERY_STATUS".equals(ruleAction)
                || "EXPORT".equals(ruleAction)
                || "GENERATE_CHAPTER".equals(ruleAction)
                || "GENERATE_ALL".equals(ruleAction)
                || "PREPARE_OUTLINE".equals(ruleAction)
                || "QUERY_DATA".equals(ruleAction)
                || "QUERY_KB".equals(ruleAction)) {
            return rule;
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(INTENT_SYSTEM_PROMPT));
        JSONArray history = ctx.getJSONArray("history");
        if (history != null) {
            for (int i = 0; i < history.size(); i++) {
                messages.add(UserMessage.from(history.getString(i)));
            }
        }
        JSONObject status = new JSONObject();
        status.put("batchNo", ctx.getString("batchNo"));
        status.put("wsmc", ctx.getString("wsmc"));
        status.put("lymc", ctx.getString("lymc"));
        List<Map<String, Object>> brief = new ArrayList<>();
        for (Map<String, Object> r : generateService.listChapters(ctx.getString("batchNo"), ctx.getString("lybm"))) {
            Map<String, Object> one = new java.util.HashMap<>();
            one.put("chapter_no", r.get("chapter_no") == null ? r.get("chapterNo") : r.get("chapter_no"));
            one.put("chapter_name", r.get("chapter_name") == null ? r.get("chapterName") : r.get("chapter_name"));
            one.put("status", r.get("status"));
            brief.add(one);
        }
        status.put("chapters", brief);
        messages.add(UserMessage.from("当前报告状态：" + status.toJSONString() + "\n用户消息：" + userMessage));

        // 意图解析要求确定性输出：temperature=0；模型 JSON 非法（枚举/章节号越界等）时携带错误原因重试 1 次
        // 模型选择优先级：会话内用户选择 > 配置 shreport.agent.model-id > 系统默认
        String chosenModelId = resolveModelId(ctx);
        AIChatParams params = new AIChatParams();
        params.setTemperature(0.0);
        String lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            List<ChatMessage> tryMessages = new ArrayList<>(messages);
            if (lastError != null) {
                tryMessages.add(UserMessage.from("你上一次输出无法被解析，原因：" + lastError
                        + "。请重新只输出一个合法 JSON 对象，不要输出其他文字。"));
            }
            try {
                // completionsWithUsage 对 null 模型走系统默认，模型未返回用量时按字符估算
                com.alibaba.fastjson.JSONObject respWithUsage =
                        aiChatHandler.completionsWithUsage(chosenModelId, tryMessages, params);
                recordIntentUsage(conversationId, chosenModelId, respWithUsage);
                String resp = respWithUsage.getString("text");
                return ShReportIntentValidator.validate(JSON.parseObject(extractJson(resp)));
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("意图解析第{}次输出非法：{}", attempt + 1, lastError);
            }
        }
        log.warn("意图解析两次均失败，降级为规则解析");
        return rule;
    }

    /** 意图解析调用的用量入账（解析失败重试的调用同样计入） */
    private void recordIntentUsage(String conversationId, String modelId, com.alibaba.fastjson.JSONObject respWithUsage) {
        if (conversationId == null || conversationId.isEmpty() || respWithUsage == null) {
            return;
        }
        try {
            JSONObject u = new JSONObject();
            u.put("scene", "intent");
            u.put("inputTokens", respWithUsage.getLongValue("inputTokens"));
            u.put("outputTokens", respWithUsage.getLongValue("outputTokens"));
            u.put("estimated", respWithUsage.getBooleanValue("estimated"));
            if (modelId != null && !modelId.isEmpty()) {
                u.put("model", modelId);
            }
            usageTracker.record(conversationId, u);
        } catch (Exception e) {
            log.debug("意图解析用量统计失败：{}", e.getMessage());
        }
    }

    public JSONObject ruleBasedIntent(String msg) {
        JSONObject o = new JSONObject();
        String m = msg == null ? "" : msg.trim();
        Matcher confirm = Pattern.compile("确认(?:使用)?流域[：:]\\s*(.+?)\\|(.+)").matcher(m);
        if (confirm.find()) {
            o.put("action", "CHAT");
            o.put("confirmLymc", confirm.group(1).trim());
            o.put("confirmLybm", confirm.group(2).trim());
            o.put("wsmc", confirm.group(1).trim());
            o.put("reply", "已选择流域：" + confirm.group(1).trim());
            return o;
        }
        // 状态/进度类查询词必须带生成语境：「状态」单字过宽，会把
        // 「把第三章各隐患点的治理状态写清楚」这类改稿请求误判成查进度（规则优先，模型没有纠正机会）
        if (m.matches(".*(进度|生成状态|报告状态|章节状态|批次状态|生成了吗|好了吗|写完了吗|完成了吗|完成没有|生成到哪|到哪一步了|怎么样了).*")) {
            o.put("action", "QUERY_STATUS");
        } else if (m.matches(".*(导出|下载).*(word|Word|WORD|报告).*") || m.matches(".*导出.*(文档|文件).*") || "导出".equals(m)
                || "导出Word".equalsIgnoreCase(m.replace(" ", ""))
                || m.matches(".*导出.*第\\s*[0-9０-９1-7１-７一二三四五六七]\\s*章.*")) {
            o.put("action", "EXPORT");
            int exportCh = extractChapterNo(m);
            if (exportCh > 0) {
                o.put("chapterNo", exportCh);
            }
        } else if (extractChapterNo(m) == 0 && ShReportKnowledgeService.looksLikeKbQuestion(m)) {
            o.put("action", "QUERY_KB");
        } else if (extractChapterNo(m) == 0 && isStatsQuestion(m)) {
            // 统计类提问兜底：模型意图解析不可用时仍能回答数据问题
            o.put("action", "QUERY_DATA");
            o.put("dataQuery", guessDataQuery(m));
            o.put("scope", m.matches(".*(当前|该|此)流域.*") ? "bound" : "all");
        } else if (extractChapterNo(m) == 0
                && (m.matches(".*(大纲|提纲|框架|章节安排|写作结构).*")
                || m.matches(".*先(列|出|给).*(章节|提纲|大纲).*"))) {
            // 两阶段生成：先出提纲确认，再放行暂存的生成动作
            o.put("action", "PREPARE_OUTLINE");
        } else {
            int chapterNo = extractChapterNo(m);
            boolean whole = m.matches(".*(整份|全部|完整|整个).*(报告|章节).*") || m.contains("生成报告")
                    || (m.contains("报告") && chapterNo == 0 && m.matches(".*(生成|编写|生成一下|帮我).*"))
                    || (chapterNo > 0 && m.contains("报告") && !m.contains("修改"));
            if (chapterNo > 0 && !whole && m.matches(".*(生成|修改|调整|重新|细化|改|写).*")) {
                o.put("action", "GENERATE_CHAPTER");
                o.put("chapterNo", chapterNo);
                o.put("requirement", m);
            } else if (whole || (chapterNo == 0 && m.contains("重新生成"))) {
                o.put("action", "GENERATE_ALL");
            } else {
                o.put("action", "CHAT");
            }
        }
        Matcher wsmc = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]{2,20}流域)").matcher(m);
        if (wsmc.find()) {
            o.put("wsmc", wsmc.group(1));
        }
        // requirement 默认值按动作区分：单章生成/修改才把整句当修改要求；
        // GENERATE_ALL 用 sanitize 滤掉纯触发语（「帮我生成整份报告」不是内容要求，注入会诱导模型写全篇）；
        // 其余动作不需要 requirement，置 null 避免污染意图历史
        if ("GENERATE_CHAPTER".equals(o.getString("action"))) {
            o.put("requirement", o.get("requirement") == null ? m : o.get("requirement"));
        } else if ("GENERATE_ALL".equals(o.getString("action"))) {
            o.put("requirement", ShReportGenerateService.sanitizeRequirement(
                    o.get("requirement") == null ? m : o.getString("requirement")));
        } else {
            o.put("requirement", null);
        }
        o.put("reply", "（规则解析）" + ("CHAT".equals(o.getString("action"))
                ? "我暂时无法调用智能解析，你可以直接说「生成整份报告」或「修改第三章：表格再细化」。"
                : "明白了，我将按你的要求执行。"));
        return o;
    }

    /** 统计类提问兜底识别：问数量/分布/清单，且不含章节号（避免误伤「第三章多统计一些数据」） */
    static boolean isStatsQuestion(String m) {
        boolean asking = m.matches(".*(多少|几个|几条|几处|统计|分布|占比|汇总|清单|列表|有哪些|哪些|情况).*");
        boolean topic = m.matches(".*(小流域|流域|防治对象|对象|县区|区县|乡镇|人口|风险|隐患|桥涵坝|阻水|占地|占压|类型).*");
        return asking && topic;
    }

    /** 从问题措辞猜统计类别（仅规则兜底用，主链路由模型给 dataQuery） */
    static String guessDataQuery(String m) {
        if (m.matches(".*(哪些|清单|列表).*(小流域|流域).*")) {
            return ShReportConstants.Q_LIST_WATERSHEDS;
        }
        if (m.matches(".*(桥涵坝|阻水|R1|涉水工程).*")) {
            return ShReportConstants.Q_CULVERT;
        }
        if (m.matches(".*(占地|占压|沟滩).*")) {
            return ShReportConstants.Q_GULLY;
        }
        if (m.matches(".*(风险|隐患|壅水|漫流|泥石流|滑坡).*")) {
            return ShReportConstants.Q_RISK;
        }
        if (m.matches(".*(乡镇).*")) {
            return ShReportConstants.Q_BY_TOWN;
        }
        if (m.matches(".*(县区|区县|县|区).*")) {
            return ShReportConstants.Q_BY_COUNTY;
        }
        if (m.matches(".*(类型|居民|学校|医院).*")) {
            return ShReportConstants.Q_BY_TYPE;
        }
        if (m.matches(".*(哪些|清单|列表|名单).*")) {
            return ShReportConstants.Q_LIST_OBJECTS;
        }
        return ShReportConstants.Q_OVERVIEW;
    }

    private static int extractChapterNo(String m) {
        Matcher num = Pattern.compile("第\\s*([0-9０-９1-7１-７一二三四五六七])\\s*章").matcher(m);
        if (num.find()) {
            return parseChapterToken(num.group(1));
        }
        String[] names = {"流域概况", "前期准备", "外业调查", "影响分析", "历史灾害", "防治区", "成果与建议"};
        for (int i = 0; i < names.length; i++) {
            if (m.contains(names[i])) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int parseChapterToken(String t) {
        int cn = "一二三四五六七".indexOf(t);
        if (cn >= 0) {
            return cn + 1;
        }
        int fw = "１２３４５６７".indexOf(t);
        if (fw >= 0) {
            return fw + 1;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String buildStatusReply(String batchNo, String lybm, String modelReply) {
        if (batchNo == null || batchNo.isBlank()) {
            // 新会话还没绑批次就问进度（快捷指令「查看当前进度」任何时候都可点），给引导而不是空括号
            return "还没有开始生成报告。请先在顶部选择流域，然后说「生成整份报告」，或点对话区的快捷指令「生成整份报告」。";
        }
        List<Map<String, Object>> rows = generateService.listChapters(batchNo, lybm);
        if (rows.isEmpty()) {
            return "当前批次（" + batchNo + "）还没有生成任何章节。你可以说「生成整份报告」开始。";
        }
        StringBuilder sb = new StringBuilder("当前批次（" + batchNo + "）章节状态：\n\n");
        for (Map<String, Object> r : rows) {
            // Markdown 无序列表：聊天气泡按 Markdown 渲染，单换行会被折叠，必须用列表语法
            sb.append("- ").append(r.get("chapter_name") == null ? r.get("chapterName") : r.get("chapter_name"))
                    .append("：").append(r.get("status")).append('\n');
        }
        if (modelReply != null && !modelReply.isEmpty()) {
            sb.append('\n').append(modelReply);
        }
        return sb.toString();
    }

    private void appendHistory(JSONObject ctx, String conversationId, String userMessage, String intentJson) {
        JSONArray history = ctx.getJSONArray("history");
        if (history == null) {
            history = new JSONArray();
            ctx.put("history", history);
        }
        history.add("用户：" + userMessage);
        history.add("解析结果：" + intentJson);
        while (history.size() > 20) {
            history.remove(0);
        }
        sessionStore.save(conversationId, ctx);
    }

    private void sendWatershed(SseEmitter emitter, Object sseLock, JSONObject ctx) {
        String lybm = ctx.getString("lybm");
        String lymc = ctx.getString("lymc");
        if (!empty(lybm)) {
            sendEvent(emitter, sseLock, "watershed", lybm + "|" + (lymc == null ? "" : lymc));
        }
    }

    private static String extractJson(String resp) {
        if (resp == null) {
            return "{}";
        }
        int s = resp.indexOf('{');
        int e = resp.lastIndexOf('}');
        if (s >= 0 && e > s) {
            return resp.substring(s, e + 1);
        }
        return "{}";
    }

    /**
     * 闲聊/咨询：按 token 推 delta，让对话框边生成边显示。
     */
    private void streamChatReply(String conversationId, JSONObject ctx, String userMessage, String intentReply,
                                 SseEmitter emitter, Object sseLock) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(
                "你是山洪灾害风险隐患调查报告生成助手。用简洁中文直接回答用户，不要输出 JSON，不要重复「收到，我先分析」。"
                        + "章节正文在页面右侧章节面板，生成后可点「刷新」查看；不要编造调查数据。"));
        JSONArray history = ctx.getJSONArray("history");
        if (history != null) {
            int from = Math.max(0, history.size() - 6);
            for (int i = from; i < history.size(); i++) {
                messages.add(UserMessage.from(history.getString(i)));
            }
        }
        StringBuilder user = new StringBuilder();
        if (!empty(ctx.getString("lymc"))) {
            user.append("当前流域：").append(ctx.getString("lymc")).append('\n');
        }
        if (!empty(intentReply)) {
            user.append("系统意图摘要：").append(intentReply).append('\n');
        }
        user.append("用户：").append(userMessage == null ? "" : userMessage);
        messages.add(UserMessage.from(user.toString()));
        AIChatParams params = new AIChatParams();
        params.setTemperature(0.5);
        streamLlmTokens(conversationId, ctx, messages, params, "chat", emitter, sseLock);
    }

    /**
     * 把大模型 TokenStream 逐段推成 SSE delta；阻塞到结束，供当前请求线程写完再 done。
     */
    private void streamLlmTokens(String conversationId, JSONObject ctx, List<ChatMessage> messages,
                                 AIChatParams params, String scene, SseEmitter emitter, Object sseLock) {
        String modelId = resolveModelId(ctx);
        TokenStream stream;
        try {
            stream = empty(modelId)
                    ? aiChatHandler.chatByDefaultModel(messages, params)
                    : aiChatHandler.chat(modelId, messages, params);
        } catch (Exception e) {
            log.warn("开启流式对话失败 scene={}：{}", scene, e.getMessage());
            sendEvent(emitter, sseLock, "text", "模型调用失败：" + e.getMessage());
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean thinking = new AtomicBoolean(false);
        AtomicBoolean anyDelta = new AtomicBoolean(false);
        LiveChat live = liveChats.get(conversationId);
        if (live != null) {
            live.streamLatch = done;
        }
        if (stopped(conversationId, live)) {
            sendEvent(emitter, sseLock, "text", "已停止。");
            return;
        }
        stream.onPartialResponse(token -> {
                    if (stopped(conversationId, live)) {
                        done.countDown();
                        return;
                    }
                    if (token == null || token.isEmpty()) {
                        return;
                    }
                    if ("<think>".equals(token)) {
                        thinking.set(true);
                        return;
                    }
                    if ("</think>".equals(token)) {
                        thinking.set(false);
                        return;
                    }
                    if (thinking.get()) {
                        return;
                    }
                    anyDelta.set(true);
                    sendEvent(emitter, sseLock, "delta", token);
                })
                .onCompleteResponse((ChatResponse response) -> {
                    try {
                        recordStreamUsage(conversationId, modelId, scene, response);
                    } finally {
                        done.countDown();
                    }
                })
                .onError(err -> {
                    log.warn("流式对话失败 scene={}：{}", scene, err.getMessage());
                    if (!anyDelta.get()) {
                        sendEvent(emitter, sseLock, "text", "模型回复失败：" + err.getMessage());
                    }
                    done.countDown();
                })
                .start();
        try {
            if (!done.await(3, TimeUnit.MINUTES)) {
                log.warn("流式对话超时 scene={} conv={}", scene, conversationId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordStreamUsage(String conversationId, String modelId, String scene, ChatResponse response) {
        if (conversationId == null || conversationId.isEmpty() || response == null) {
            return;
        }
        TokenUsage usage = response.tokenUsage();
        if (usage == null) {
            return;
        }
        try {
            JSONObject u = new JSONObject();
            u.put("scene", scene);
            u.put("inputTokens", usage.inputTokenCount() == null ? 0 : usage.inputTokenCount());
            u.put("outputTokens", usage.outputTokenCount() == null ? 0 : usage.outputTokenCount());
            if (modelId != null && !modelId.isEmpty()) {
                u.put("model", modelId);
            }
            usageTracker.record(conversationId, u);
        } catch (Exception e) {
            log.debug("流式用量统计失败：{}", e.getMessage());
        }
    }

    private void sendEvent(SseEmitter emitter, Object lock, String event, Object data) {
        LiveChat live = sourceIndex.get(emitter);
        String payload = data == null ? "" : String.valueOf(data);
        boolean recorded = live != null && !"usage".equals(event) && !"graph".equals(event);
        if (recorded) {
            // 对话轮事件：写入重放缓存并广播到所有接入端（原始连接 + 断线重连）
            synchronized (live.sinkLock) {
                if (!live.completed) {
                    live.history.add(new Object[]{event, payload});
                }
                for (SseEmitter sink : new ArrayList<>(live.sinks)) {
                    safeSend(sink, event, payload, live);
                }
            }
        } else {
            synchronized (lock) {
                try {
                    // 多行用多条 data: 行，前端按 \n 拼回；不能压成空格，否则 Markdown/流式 token 会丢换行
                    SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event);
                    String[] lines = payload.split("\n", -1);
                    for (String line : lines) {
                        builder.data(line.replace("\r", ""));
                    }
                    emitter.send(builder);
                } catch (Exception e) {
                    log.warn("SSE 推送失败（客户端可能已断开）：{}", e.getMessage());
                }
            }
        }
        recordTurnEvent(emitter, event, data);
    }

    /** 向单个接入端推事件；发送失败（客户端已断开）时把它从接入端列表移除 */
    private void safeSend(SseEmitter sink, String event, String payload, LiveChat live) {
        try {
            // 多行用多条 data: 行，前端按 \n 拼回；不能压成空格，否则 Markdown/流式 token 会丢换行
            SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event);
            String[] lines = payload.split("\n", -1);
            for (String line : lines) {
                builder.data(line.replace("\r", ""));
            }
            sink.send(builder);
        } catch (Exception e) {
            live.sinks.remove(sink);
        }
    }

    /** 对话轮结束（原始连接 complete）时关闭全部重连接入端；历史缓存保留供迟到的重连补拉 */
    private void finishSinks(LiveChat live) {
        synchronized (live.sinkLock) {
            live.completed = true;
            for (SseEmitter sink : new ArrayList<>(live.sinks)) {
                try {
                    sink.complete();
                } catch (Exception ignore) {
                }
            }
            live.sinks.clear();
        }
    }

    /** 断线重连：按 requestId 找回对话轮，按序补发缓存事件后继续实时推送；轮次不存在时发 done 让前端清理重连标记 */
    public SseEmitter reconnect(String requestId) {
        SseEmitter emitter = new SseEmitter(1800_000L);
        LiveChat live = requestId == null || requestId.isEmpty() ? null : liveByRequestId.get(requestId);
        if (live == null) {
            try {
                emitter.send(SseEmitter.event().name("done").data("gone"));
            } catch (Exception ignore) {
            }
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
            return emitter;
        }
        synchronized (live.sinkLock) {
            for (Object[] ev : live.history) {
                safeSend(emitter, (String) ev[0], (String) ev[1], live);
            }
            if (!live.completed) {
                live.sinks.add(emitter);
            }
        }
        emitter.onCompletion(() -> {
            synchronized (live.sinkLock) {
                live.sinks.remove(emitter);
            }
        });
        emitter.onTimeout(() -> {
            synchronized (live.sinkLock) {
                live.sinks.remove(emitter);
            }
        });
        return emitter;
    }

    /** 对话内容服务端落库（对齐通用 AI 助手：后端是消息的唯一写入方，客户端推送仅作补充） */
    private void recordTurnEvent(SseEmitter emitter, String event, Object data) {
        TurnRecorder rec;
        synchronized (turnRecorders) {
            rec = turnRecorders.get(emitter);
        }
        if (rec == null) {
            return;
        }
        if ("text".equals(event) || "delta".equals(event) || "choose".equals(event)) {
            rec.append(event, data == null ? "" : String.valueOf(data));
        }
        if ("done".equals(event)) {
            turnRecorders.remove(emitter);
            saveTurn(rec);
        }
    }

    /** 把本轮「用户消息 + 助手回复」并集写入服务端会话；失败只记日志，不影响对话 */
    private void saveTurn(TurnRecorder rec) {
        try {
            if (rec.userKey == null || rec.userKey.isEmpty()) {
                return;
            }
            // 用户在生成过程中删掉了会话（或直接在库里删了）：这一轮不能再把会话写回去
            if (sessionStore.isSessionDeleted(rec.userKey, rec.conversationId)) {
                log.info("会话已删除，跳过本轮落库 conv={}", rec.conversationId);
                return;
            }
            JSONArray msgs = new JSONArray();
            long now = System.currentTimeMillis();
            if (rec.userMessage != null && !rec.userMessage.isEmpty()) {
                JSONObject u = new JSONObject(true);
                u.put("role", "user");
                u.put("content", rec.userMessage);
                u.put("time", rec.startTs);
                msgs.add(u);
            }
            for (StringBuilder seg : rec.segments) {
                String text = seg.toString();
                if (!text.trim().isEmpty()) {
                    JSONObject a = new JSONObject(true);
                    a.put("role", "assistant");
                    a.put("content", text);
                    a.put("time", now);
                    msgs.add(a);
                }
            }
            if (msgs.isEmpty()) {
                return;
            }
            JSONObject session = new JSONObject(true);
            session.put("conversationId", rec.conversationId);
            session.put("messages", msgs);
            session.put("updateTime", now);
            agentSessionDao.save(rec.userKey, session);
        } catch (Exception e) {
            log.warn("对话消息服务端落库失败 conv={}: {}", rec.conversationId, e.getMessage());
        }
    }

    /** 与 ShReportAgentController.currentUserKey 同一用户维度：登录名，取不到降级 sessionId */
    private String requestUserKey() {
        try {
            String username = JwtUtil.getUserNameByToken(SpringContextUtils.getHttpServletRequest());
            if (username != null && !username.isEmpty()) {
                return username;
            }
            return SpringContextUtils.getHttpServletRequest().getSession().getId();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    private static String firstNonEmpty(String a, String b) {
        return empty(a) ? b : a;
    }

    private static String firstNonEmpty(String a, String b, String c) {
        return firstNonEmpty(firstNonEmpty(a, b), c);
    }
}
