package org.jeecg.modules.airag.shreport.component;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.jeecg.modules.airag.shreport.entity.ShReportQcIssue;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphState;
import org.jeecg.modules.airag.shreport.util.ShReportConsistencyChecker;
import org.jeecg.modules.airag.shreport.service.ShReportGenerateService;
import org.jeecg.modules.airag.shreport.service.ShReportMetricService;
import org.jeecg.modules.airag.shreport.service.ShReportMaterialService;
import org.jeecg.modules.airag.shreport.util.ShReportTextChecker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 报告图节点。测试可走三参构造，不碰 Spring / 数据库。
 */
@Slf4j
@Component
public class ShReportGraphNodes {

    public interface ChapterWriter {
        String write(ShReportGraphState state, int chapterNo, String requirement);

        /** usageSink 非空时回传本次模型调用的 token 用量 */
        default String write(ShReportGraphState state, int chapterNo, String requirement,
                             java.util.function.Consumer<com.alibaba.fastjson.JSONObject> usageSink) {
            return write(state, chapterNo, requirement);
        }
    }

    public interface SideEffects {
        default void prepare(ShReportGraphState state, ShReportGenerateService.ProgressListener listener) {
        }

        /** 单章写作开始前记录起点。整份模式下第 7 章不经过 prepare，由 writeCh7 调用它补记生成开始时间 */
        default void markStart(ShReportGraphState state, int chapterNo, ShReportGenerateService.ProgressListener listener) {
        }

        default void afterWrite(ShReportGraphState state, int chapterNo, String content,
                                ShReportGenerateService.ProgressListener listener) {
        }

        default void persist(ShReportGraphState state, ShReportGenerateService.ProgressListener listener) {
        }

        default Map<Integer, String> loadPersistedChapters(String batchNo, String lybm) {
            return Map.of();
        }

        default Map<String, String> metrics(String batchNo, String lybm) {
            return Map.of();
        }
    }

    static final String META_LISTENER = "listener";

    private final ChapterWriter writer;
    private final ShReportQualityChecker qualityChecker;
    private final SideEffects effects;
    private final ExecutorService chapterPool;

    @Autowired(required = false)
    private ShReportGraphTraceStore traceStore;

    @Autowired(required = false)
    private ShReportMaterialService materialService;

    @Autowired
    public ShReportGraphNodes(ShReportLlmChapterWriter llmWriter,
                              ShReportQualityChecker qualityChecker,
                              @Lazy ShReportGenerateService generateService,
                              ShReportMetricService metricService,
                              ShReportConcurrencyLimiter concurrencyLimiter) {
        // 直接传实例而不是方法引用：reportKbSources 需要 instanceof 取回范文来源
        this(llmWriter, qualityChecker, productionEffects(generateService, metricService),
                // 章节写作线程数与大模型并发额度对齐：多出的线程只会在额度上空等
                Executors.newFixedThreadPool(Math.max(2, concurrencyLimiter.maxLlmCalls())));
    }

    public ShReportGraphNodes(ChapterWriter writer, ShReportQualityChecker qualityChecker, SideEffects effects) {
        this(writer, qualityChecker, effects, Executors.newFixedThreadPool(2));
    }

    public ShReportGraphNodes(ChapterWriter writer, ShReportQualityChecker qualityChecker,
                       SideEffects effects, ExecutorService chapterPool) {
        this.writer = writer;
        this.qualityChecker = qualityChecker;
        this.effects = effects;
        this.chapterPool = chapterPool;
    }

    public void setTraceStore(ShReportGraphTraceStore traceStore) {
        this.traceStore = traceStore;
    }

    public Map<String, Object> prepare(ShReportGraphState state, RunnableConfig config) {
        return traced(ShReportGraphRunner.NODE_PREPARE, state, config, () -> {
            ShReportGenerateService.ProgressListener l = listener(config);
            boolean cancelled = l.isCancelled();
            if (!cancelled) {
                effects.prepare(state, l);
            }
            return Map.of(ShReportGraphState.CANCELLED, cancelled);
        });
    }

    public Map<String, Object> write(ShReportGraphState state, RunnableConfig config) {
        return traced(ShReportGraphRunner.NODE_WRITE, state, config, () -> {
            ShReportGenerateService.ProgressListener l = listener(config);
            Map<String, Object> out = new HashMap<>();
            if (l.isCancelled() || state.cancelled()) {
                out.put(ShReportGraphState.CANCELLED, true);
                return out;
            }
            List<Integer> targets = state.targetChapters();
            if (targets.isEmpty()) {
                return out;
            }
            Map<Integer, String> chapters = new LinkedHashMap<>(state.chapters());
            Map<Integer, String> written = writeTargets(state, targets, l);
            chapters.putAll(written);
            out.put(ShReportGraphState.CHAPTERS, ShReportGraphState.chaptersPayload(chapters));
            if (targets.size() == 1 && targets.get(0) == 7) {
                out.put(ShReportGraphState.CH7_WRITTEN, true);
            }
            return out;
        });
    }

    public Map<String, Object> writeCh7(ShReportGraphState state, RunnableConfig config) {
        return traced(ShReportGraphRunner.NODE_WRITE_CH7, state, config, () -> {
            ShReportGenerateService.ProgressListener l = listener(config);
            Map<String, Object> out = new HashMap<>();
            out.put(ShReportGraphState.CH7_WRITTEN, true);
            if (l.isCancelled() || state.cancelled()) {
                out.put(ShReportGraphState.CANCELLED, true);
                return out;
            }
            Map<Integer, String> chapters = new LinkedHashMap<>(state.chapters());
            if (chapters.size() < 6) {
                chapters.putAll(effects.loadPersistedChapters(state.batchNo(), state.lybm()));
            }
            // 缺章/失败章提示：第七章汇总基于现有内容继续，但让用户知道数据不完整
            for (int i = 1; i <= 6; i++) {
                String c = chapters.get(i);
                if (c == null || c.isBlank() || ShReportTextChecker.isPlaceholder(c)) {
                    l.onText("《" + ShReportConstants.chapterName(i) + "》缺失或失败，第七章汇总将不含该章内容。");
                }
            }
            ShReportGraphState withChapters = new ShReportGraphState(merge(state.data(),
                    Map.of(ShReportGraphState.CHAPTERS, ShReportGraphState.chaptersPayload(chapters))));
            effects.markStart(state, 7, l);
            String content = safeWrite(withChapters, 7, state.requirement(), l);
            chapters.put(7, content);
            out.put(ShReportGraphState.CHAPTERS, ShReportGraphState.chaptersPayload(chapters));
            out.put(ShReportGraphState.TARGET_CHAPTERS, List.of(7));
            return out;
        });
    }

    public Map<String, Object> qc(ShReportGraphState state, RunnableConfig config) {
        return traced(ShReportGraphRunner.NODE_QC, state, config, () -> {
            ShReportGenerateService.ProgressListener l = listener(config);
            Map<String, Object> out = new HashMap<>();
            if (l.isCancelled() || state.cancelled()) {
                out.put(ShReportGraphState.CANCELLED, true);
                return out;
            }
            Map<Integer, List<ShReportQcIssue>> all = new LinkedHashMap<>(state.qcIssues());
            for (int ch : state.targetChapters()) {
                String content = state.chapters().getOrDefault(ch, "");
                boolean hasHistory = ch == 5 && materialService != null
                        && materialService.hasHistoryMaterials(state.lybm(), state.batchNo());
                List<ShReportQcIssue> issues = qualityChecker.check(content, ch, state.lymc(), hasHistory);
                all.put(ch, issues);
                if (ShReportQualityChecker.hasBlocking(issues)) {
                    l.onText("《" + ShReportConstants.chapterName(ch) + "》质检未过（"
                            + ShReportQualityChecker.toNote(issues) + "）");
                }
            }
            out.put(ShReportGraphState.QC_ISSUES, ShReportGraphState.qcIssuesPayload(all));
            return out;
        });
    }

    public Map<String, Object> rewrite(ShReportGraphState state, RunnableConfig config) {
        return traced(ShReportGraphRunner.NODE_REWRITE, state, config, () -> {
            Map<String, Object> out = new HashMap<>();
            out.put(ShReportGraphState.RETRY_COUNT, state.retryCount() + 1);
            List<Integer> failed = state.failedChapters();
            if (failed.isEmpty()) {
                failed = state.consistencyConflictChapters();
            }
            if (failed.isEmpty()) {
                failed = state.targetChapters();
            }
            String note = collectNotes(state, failed);
            boolean placeholderRetry = failed.stream().allMatch(ch ->
                    ShReportTextChecker.isPlaceholder(state.chapters().get(ch)));
            String req = state.baseRequirement();
            if (!note.isBlank() && !placeholderRetry) {
                req = (req.isBlank() ? "" : req + "\n")
                        + "上一版存在以下问题，请重新完整撰写并逐项改正：" + note;
            }
            out.put(ShReportGraphState.REQUIREMENT, req);
            out.put(ShReportGraphState.TARGET_CHAPTERS, failed);
            if (failed.stream().anyMatch(n -> n < 7)) {
                out.put(ShReportGraphState.CH7_WRITTEN, false);
            }
            ShReportGenerateService.ProgressListener l = listener(config);
            l.onText("质检/跨章未过，第 " + failed + " 章回写（第 " + (state.retryCount() + 1) + " 次）…");
            log.info("报告图回写 batch={} chapters={} retry={}", state.batchNo(), failed, state.retryCount() + 1);
            return out;
        });
    }

    public Map<String, Object> consistency(ShReportGraphState state, RunnableConfig config) {
        return traced(ShReportGraphRunner.NODE_CONSISTENCY, state, config, () -> {
            ShReportGenerateService.ProgressListener l = listener(config);
            Map<String, Object> out = new HashMap<>();
            if (l.isCancelled() || state.cancelled()) {
                out.put(ShReportGraphState.CANCELLED, true);
                return out;
            }
            Map<Integer, String> chapters = new LinkedHashMap<>(state.chapters());
            if (chapters.size() < 2) {
                chapters.putAll(effects.loadPersistedChapters(state.batchNo(), state.lybm()));
            }
            List<ShReportQcIssue> issues = ShReportConsistencyChecker.check(
                    chapters, effects.metrics(state.batchNo(), state.lybm()));
            out.put(ShReportGraphState.CONSISTENCY_ISSUES, issues);
            if (!issues.isEmpty()) {
                l.onText("跨章一致性校验发现 " + issues.size() + " 处疑似矛盾："
                        + issues.get(0).getMessage() + (issues.size() > 1 ? " 等。" : ""));
            }
            return out;
        });
    }

    public Map<String, Object> persist(ShReportGraphState state, RunnableConfig config) {
        return traced(ShReportGraphRunner.NODE_PERSIST, state, config, () -> {
            ShReportGenerateService.ProgressListener l = listener(config);
            boolean cancelled = l.isCancelled() || state.cancelled();
            // 质检结果只作参考：整体成败只看有无异常占位（整份还需第 7 章在），质检不达标不影响
            boolean success = !cancelled && state.chapters().values().stream()
                    .noneMatch(ShReportTextChecker::isPlaceholder);
            if (state.fullReport() && !state.chapters().containsKey(7) && !cancelled) {
                success = false;
            }
            Map<String, Object> out = new HashMap<>();
            out.put(ShReportGraphState.CANCELLED, cancelled);
            out.put(ShReportGraphState.SUCCESS, success);
            ShReportGraphState next = new ShReportGraphState(merge(state.data(), out));
            effects.persist(next, l);
            return out;
        });
    }

    private Map<String, Object> traced(String node, ShReportGraphState state, RunnableConfig config,
                                       Supplier<Map<String, Object>> body) {
        emitGraph(node, "enter", state, config);
        Map<String, Object> out = body.get();
        emitGraph(node, "leave", new ShReportGraphState(merge(state.data(), out == null ? Map.of() : out)), config);
        return out;
    }

    private void emitGraph(String node, String phase, ShReportGraphState state, RunnableConfig config) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("batchNo", state.batchNo());
        snap.put("retryCount", state.retryCount());
        snap.put("maxRetry", state.maxRetry());
        snap.put("targetChapters", state.targetChapters());
        snap.put("fullReport", state.fullReport());
        snap.put("cancelled", state.cancelled());
        snap.put("success", state.success());
        snap.put("ch7Written", state.ch7Written());
        snap.put("hasBlocking", state.hasBlocking());
        snap.put("routeAfterQc", state.routeAfterQc());
        snap.put("routeAfterConsistency", state.routeAfterConsistency());
        listener(config).onGraphNode(node, phase, snap);
        if (traceStore != null && state.batchNo() != null && !state.batchNo().isBlank()) {
            traceStore.accept(node, phase, snap);
        }
    }

    private Map<Integer, String> writeTargets(ShReportGraphState state, List<Integer> targets,
                                              ShReportGenerateService.ProgressListener l) {
        Map<Integer, String> written = new LinkedHashMap<>();
        List<Integer> parallel = new ArrayList<>();
        for (int ch : targets) {
            if (ch >= 1 && ch <= 6) {
                parallel.add(ch);
            }
        }
        if (parallel.size() > 1) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            Map<Integer, String> sync = new LinkedHashMap<>();
            for (int ch : parallel) {
                final int chapterNo = ch;
                futures.add(CompletableFuture.runAsync(() -> {
                    String content = safeWrite(state, chapterNo, state.requirement(), l);
                    synchronized (sync) {
                        sync.put(chapterNo, content);
                    }
                }, chapterPool));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            written.putAll(sync);
        } else {
            for (int ch : parallel) {
                written.put(ch, safeWrite(state, ch, state.requirement(), l));
            }
        }
        if (targets.contains(7) && !state.fullReport()) {
            written.put(7, safeWrite(state, 7, state.requirement(), l));
        }
        return written;
    }

    private String safeWrite(ShReportGraphState state, int chapterNo, String requirement,
                             ShReportGenerateService.ProgressListener l) {
        if (l.isCancelled()) {
            return "【系统默认内容】用户取消";
        }
        l.onProgress(chapterNo, ShReportConstants.STATUS_GENERATING, "开始生成");
        l.onText("正在生成《" + ShReportConstants.chapterName(chapterNo) + "》…");
        try {
            String content = writer.write(state, chapterNo, requirement, l::onUsage);
            if (content == null || content.isBlank()) {
                content = "【系统默认内容】本章生成结果为空。";
            }
            reportKbSources(state, chapterNo, l);
            effects.afterWrite(state, chapterNo, content, l);
            return content;
        } catch (Exception e) {
            log.error("章节写作失败 batch={} chapter={}", state.batchNo(), chapterNo, e);
            String fallback = "【系统默认内容】本章生成异常：" + e.getMessage();
            effects.afterWrite(state, chapterNo, fallback, l);
            return fallback;
        }
    }

    /** 本章若参考了一期成果范文，把来源文档名推给用户（范文经流程入参注入，来源由写作器记录） */
    private void reportKbSources(ShReportGraphState state, int chapterNo,
                                 ShReportGenerateService.ProgressListener l) {
        if (!(writer instanceof ShReportLlmChapterWriter llmWriter)) {
            return;
        }
        List<String> sources = llmWriter.consumeKbSources(state.batchNo(), chapterNo);
        if (!sources.isEmpty()) {
            l.onText("《" + ShReportConstants.chapterName(chapterNo) + "》参考一期成果范文："
                    + String.join("、", sources));
        }
    }

    private String collectNotes(ShReportGraphState state, List<Integer> chapters) {
        List<String> notes = new ArrayList<>();
        Map<Integer, List<ShReportQcIssue>> qc = state.qcIssues();
        for (int ch : chapters) {
            String n = ShReportQualityChecker.toNote(qc.get(ch));
            if (n != null && !n.isBlank()) {
                notes.add("《" + ShReportConstants.chapterName(ch) + "》" + n);
            }
        }
        for (ShReportQcIssue issue : state.consistencyIssues()) {
            if (issue != null && issue.getMessage() != null) {
                notes.add(issue.getMessage());
            }
        }
        return String.join("；", notes);
    }

    static ShReportGenerateService.ProgressListener listener(RunnableConfig config) {
        if (config == null) {
            return new ShReportGenerateService.ProgressListener() {
            };
        }
        return config.metadata(META_LISTENER)
                .filter(ShReportGenerateService.ProgressListener.class::isInstance)
                .map(ShReportGenerateService.ProgressListener.class::cast)
                .orElseGet(() -> new ShReportGenerateService.ProgressListener() {
                });
    }

    static SideEffects productionEffects(ShReportGenerateService generateService, ShReportMetricService metricService) {
        return new SideEffects() {
            @Override
            public void prepare(ShReportGraphState state, ShReportGenerateService.ProgressListener listener) {
                metricService.lockDataMetrics(state.batchNo(), state.lybm());
                if (state.fullReport()) {
                    listener.onText("开始并发生成第 1～6 章，质检通过后自动汇总第七章。");
                    for (int ch = 1; ch <= 6; ch++) {
                        generateService.markGenerating(state.batchNo(), state.lybm(), state.lymc(), ch);
                        listener.onProgress(ch, ShReportConstants.STATUS_GENERATING, "开始生成");
                    }
                } else {
                    for (int ch : state.targetChapters()) {
                        generateService.markGenerating(state.batchNo(), state.lybm(), state.lymc(), ch);
                        listener.onProgress(ch, ShReportConstants.STATUS_GENERATING, "开始生成");
                    }
                }
                listener.onRefresh(state.batchNo());
            }

            @Override
            public void markStart(ShReportGraphState state, int chapterNo, ShReportGenerateService.ProgressListener listener) {
                generateService.markGenerating(state.batchNo(), state.lybm(), state.lymc(), chapterNo);
            }

            @Override
            public void afterWrite(ShReportGraphState state, int chapterNo, String content,
                                   ShReportGenerateService.ProgressListener listener) {
                // 本章正文已落库即可进入「待审核」：再挂着「生成中」会让右侧面板在整轮质检/汇总
                // （6 章并行 + 第 7 章 + 跨章校验，常需十几分钟）期间一直转圈。
                // 质检结论由 persist 节点最终覆盖（未过则改为「失败」），并强制保留上一版正文。
                generateService.saveChapter(state.batchNo(), state.lybm(), state.lymc(), chapterNo,
                        content, ShReportConstants.STATUS_PENDING, null, null, null, Boolean.TRUE);
                generateService.markChapterWritten(state.batchNo(), state.lybm(), chapterNo);
                listener.onProgress(chapterNo, ShReportConstants.STATUS_PENDING, "正文已生成，质检中");
                listener.onRefresh(state.batchNo());
            }

            @Override
            public void persist(ShReportGraphState state, ShReportGenerateService.ProgressListener listener) {
                if (state.cancelled()) {
                    generateService.cancelGenerating(state.batchNo(), state.lybm(), "用户取消");
                    listener.onText("已停止生成。");
                    listener.onRefresh(state.batchNo());
                    return;
                }
                Map<Integer, List<ShReportQcIssue>> qc = state.qcIssues();
                state.chapters().forEach((ch, content) -> {
                    List<ShReportQcIssue> issues = qc.getOrDefault(ch, List.of());
                    // 质检结果只作参考：只有模型异常占位才置「失败」，质检不达标保持「待审核」，
                    // 问题留在结构化 issue 表（面板徽标展示），由用户逐章决定编辑或重新生成
                    boolean failed = ShReportTextChecker.isPlaceholder(content);
                    String status = failed ? ShReportConstants.STATUS_FAILED : ShReportConstants.STATUS_PENDING;
                    String note = failed
                            ? (ShReportQualityChecker.toNote(issues) == null ? "生成失败" : ShReportQualityChecker.toNote(issues))
                            : ShReportQualityChecker.toNote(issues);
                    // 失败原因结构化：占位文案还原为原因落 fail_reason，content 留空；流程版本落 flow_ver
                    String failReason = null;
                    if (failed) {
                        failReason = ShReportGenerateService.placeholderReason(content);
                        if (failReason == null) {
                            failReason = note;
                        }
                    }
                    String flowVersion = generateService.lastFlowVersion(state.batchNo(), ch);
                    // preservePrevVersion=true：落库的是质检循环的最终稿，content_prev 必须留给用户已看过的上一版
                    // audit_note 传 null：机器质检提示只落结构化 issue 表（前端 issueMap 展示），
                    // 不再占用人工审核意见字段——否则章节卡片里同一句提示显示两遍，
                    // 且驳回时会把「质检提示：…」预填进人工意见输入框
                    generateService.saveChapter(state.batchNo(), state.lybm(), state.lymc(), ch, content, status, null,
                            failReason, flowVersion, Boolean.TRUE);
                    generateService.persistIssues(state.batchNo(), state.lybm(), ch, issues);
                    if (ch == 1 && !failed) {
                        metricService.lockFromChapterOne(state.batchNo(), state.lybm(), content);
                    }
                    listener.onProgress(ch, status, failed ? "生成失败" : (note == null ? "已完成" : note));
                    listener.onText("《" + ShReportConstants.chapterName(ch) + "》"
                            + (failed ? "生成失败。" : "已生成完毕" + (note == null ? "。" : "（" + note + "）。")));
                });
                List<ShReportQcIssue> cons = state.consistencyIssues();
                if (!cons.isEmpty()) {
                    generateService.replaceConsistencyIssues(state.batchNo(), state.lybm(), cons);
                }
                listener.onRefresh(state.batchNo());
            }

            @Override
            public Map<Integer, String> loadPersistedChapters(String batchNo, String lybm) {
                return generateService.loadChapterContents(batchNo, lybm);
            }

            @Override
            public Map<String, String> metrics(String batchNo, String lybm) {
                return metricService.metricMap(batchNo, lybm);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> patch) {
        Map<String, Object> m = new HashMap<>(base);
        m.putAll(patch);
        return m;
    }
}
