package org.jeecg.modules.airag.shreport.service;

import com.yomahub.liteflow.flow.FlowBus;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.flow.entity.AiragFlow;
import org.jeecg.modules.airag.flow.service.IAiragFlowService;
import org.jeecg.modules.airag.flow.vo.api.FlowDebugParams;
import org.jeecg.modules.airag.flow.vo.api.FlowRunParams;
import org.jeecg.modules.airag.shreport.component.ShReportBatchLock;
import org.jeecg.modules.airag.shreport.component.ShReportConcurrencyLimiter;
import org.jeecg.modules.airag.shreport.component.ShReportModelVariant;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.jeecg.modules.airag.shreport.entity.ShReportQcIssue;
import org.jeecg.modules.airag.shreport.exception.ShReportBatchBusyException;
import org.jeecg.modules.airag.shreport.component.ShReportGraphRunner;
import org.jeecg.modules.airag.shreport.mapper.ShReportPgMapper;
import org.jeecg.modules.airag.shreport.util.ShReportTextChecker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 山洪报告生成入口：批次锁、AI 单章流程调用与章节落库；调度与质检闭环由 ShReportGraphRunner 编排。
 */
@Slf4j
@Service
public class ShReportGenerateService {

    public interface ProgressListener {
        default void onText(String text) {
        }

        default void onProgress(int chapterNo, String status, String message) {
        }

        default void onRefresh(String batchNo) {
        }

        default boolean isCancelled() {
            return false;
        }

        /** 图节点进出：phase=enter|leave，供编排可视化 */
        default void onGraphNode(String node, String phase, java.util.Map<String, Object> snapshot) {
        }

        /** 单次模型调用 token 用量：scene（intent/outline/chapter）、chapterNo、inputTokens/outputTokens/estimated */
        default void onUsage(java.util.Map<String, Object> usage) {
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShReportPgQueryService pgQuery;

    @Autowired
    private ShReportPgMapper pgMapper;

    @Autowired
    private IAiragFlowService airagFlowService;

    /**
     * 启动自愈：生成线程随 JVM 存活，服务重启后残留的「生成中」章节永远不会更新，
     * 这里统一标记为失败，交由用户重新生成；同时清理遗留的「批次+流域」Redis 生成锁，
     * 否则重启后该批次会一直报「已有生成任务正在进行」。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resetOrphanGeneratingChapters() {
        try {
            int n = pgQuery.update(
                    "UPDATE sh_report_chapter SET status=?, audit_note=? WHERE status=?",
                    ShReportConstants.STATUS_FAILED, "服务重启导致生成中断，请重新生成",
                    ShReportConstants.STATUS_GENERATING);
            if (n > 0) {
                log.warn("山洪报告：检测到 {} 个因服务重启中断的章节，已标记为失败", n);
            }
        } catch (Exception e) {
            log.error("重置中断章节状态失败", e);
        }
        try {
            int locks = batchLock.clearAll();
            if (locks > 0) {
                log.warn("山洪报告：清理 {} 个服务重启前遗留的批次生成锁", locks);
            }
        } catch (Exception e) {
            log.error("清理遗留批次锁失败", e);
        }
    }

    @Autowired
    private ShReportConstants.ShReportDataPacketService dataPacketService;

    @Autowired
    private ShReportModelVariant modelVariantService;

    @Autowired
    @Lazy
    private ShReportGraphRunner graphRunner;

    @Autowired
    private ShReportConcurrencyLimiter concurrencyLimiter;

    @Autowired
    private ShReportBatchLock batchLock;

    @Autowired
    @Lazy
    private ShReportMaterialService materialService;

    /** 最近一次流程写作所用版本，按 batch|chapter 记录，persist 落库时取用 */
    private final java.util.concurrent.ConcurrentMap<String, String> flowVerByChapter =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 取出本章最近一次流程写作的版本（流程名@设计更新时间）；不移除，同批重写会覆盖 */
    public String lastFlowVersion(String batchNo, int chapterNo) {
        return flowVerByChapter.get((batchNo == null ? "" : batchNo) + "|" + chapterNo);
    }

    /** 本章引用的上传资料清单；无资料时可能为 "" 或 null */
    public String lastMaterialVer(String batchNo, int chapterNo) {
        if (materialService == null) {
            return null;
        }
        String v = materialService.lastMaterialVer(batchNo, chapterNo);
        return v == null || v.isBlank() ? null : v;
    }

    /**
     * Flyway 在本项目里失败不阻断启动，可能没跑到对应版本。
     * 启动时在 PostgreSQL 上补齐增量列（已存在则跳过），避免列表查询缺列。
     */
    @PostConstruct
    public void ensureSchema() {
        ensureColumn("content_prev", "text", "上一版内容(重新生成前保留)");
        ensureColumn("fail_reason", "varchar(500)", "生成失败原因(失败状态下有值)");
        ensureColumn("flow_ver", "varchar(200)", "生成所用流程版本(流程名@设计更新时间)");
        ensureColumn("gen_start_ts", "bigint", "本章本次生成开始时间(毫秒时间戳)");
        ensureColumn("gen_cost_ms", "bigint", "本章从开始生成到最终完成的耗时(毫秒)");
        ensureColumn("material_ver", "text", "本章引用的上传资料清单(文件名@更新时间，分号分隔)");
    }

    private void ensureColumn(String column, String type, String comment) {
        try {
            pgQuery.execute("ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS " + column + " " + type);
            pgQuery.execute("COMMENT ON COLUMN sh_report_chapter." + column + " IS '" + comment + "'");
        } catch (Exception e) {
            log.warn("检查/补齐 {} 失败，列表查询可能异常：{}", column, e.getMessage());
        }
    }

    /** 生成整份：1-6 并行，全部成功后再生成第 7 章，最后做跨章一致性校验 */
    public boolean generateAll(String batchNo, String lybm, String lymc, String requirement, ProgressListener listener) {
        return generateAll(batchNo, lybm, lymc, requirement, null, null, listener);
    }

    public boolean generateAll(String batchNo, String lybm, String lymc, String requirement,
                               String outline, ProgressListener listener) {
        return generateAll(batchNo, lybm, lymc, requirement, outline, null, listener);
    }

    public boolean generateAll(String batchNo, String lybm, String lymc, String requirement,
                               String outline, String modelId, ProgressListener listener) {
        // 按「批次+流域」互斥：两个入口（对话/重新生成）或两个页面对同一份报告同时触发生成时，
        // 后到者立即被拒，避免章节先删后写互相交错覆盖
        String token = batchLock.tryLock(batchNo, lybm);
        if (token == null) {
            throw new ShReportBatchBusyException("该批次该流域已有生成任务正在进行，请等待完成或查看章节面板状态。");
        }
        try {
            return doGenerateAll(batchNo, lybm, lymc, requirement, outline, modelId, listener);
        } finally {
            batchLock.unlock(batchNo, lybm, token);
        }
    }

    private boolean doGenerateAll(String batchNo, String lybm, String lymc, String requirement,
                                  String outline, String modelId, ProgressListener listener) {
        ProgressListener l = listener == null ? new ProgressListener() {
        } : listener;
        return graphRunner.runAll(batchNo, lybm, lymc, requirement, outline, modelId, l);
    }

    /** 单章生成（无大纲，兼容旧调用） */
    public boolean generateChapter(String batchNo, String lybm, String lymc, int chapterNo,
                                   String requirement, ProgressListener listener) {
        return generateChapter(batchNo, lybm, lymc, chapterNo, requirement, null, null, listener);
    }

    /** 单章生成（有大纲，兼容旧调用） */
    public boolean generateChapter(String batchNo, String lybm, String lymc, int chapterNo,
                                   String requirement, String outline, ProgressListener listener) {
        return generateChapter(batchNo, lybm, lymc, chapterNo, requirement, outline, null, listener);
    }

    /**
     * 生成单章。成功返回 true（内容已落库且非失败占位）。
     * 调度、质检与重试由 ShReportGraphRunner 的图节点完成。
     * modelId 非空时在内存覆盖基础流程 LLM 节点，不复制 #M 变体。
     */
    public boolean generateChapter(String batchNo, String lybm, String lymc, int chapterNo,
                                   String requirement, String outline, String modelId, ProgressListener listener) {
        // 与 generateAll 共用「批次+流域」互斥锁
        String token = batchLock.tryLock(batchNo, lybm);
        if (token == null) {
            throw new ShReportBatchBusyException("该批次该流域已有生成任务正在进行，请等待完成或查看章节面板状态。");
        }
        try {
            return doGenerateChapter(batchNo, lybm, lymc, chapterNo, requirement, outline, modelId, listener);
        } finally {
            batchLock.unlock(batchNo, lybm, token);
        }
    }

    private boolean doGenerateChapter(String batchNo, String lybm, String lymc, int chapterNo,
                                      String requirement, String outline, String modelId, ProgressListener listener) {
        ProgressListener l = listener == null ? new ProgressListener() {
        } : listener;
        if (chapterNo < 1 || chapterNo > ShReportConstants.CHAPTER_COUNT) {
            l.onText("章节号必须在 1-7 之间。");
            return false;
        }
        return graphRunner.runChapter(batchNo, lybm, lymc, chapterNo, requirement, outline, modelId, l);
    }

    /** 重写某章质检问题（先清空该章旧问题） */
    public void persistIssues(String batchNo, String lybm, int chapterNo, List<ShReportQcIssue> issues) {
        try {
            deleteIssues(batchNo, lybm, chapterNo, null);
            if (issues == null) {
                return;
            }
            for (ShReportQcIssue q : issues) {
                insertIssue(batchNo, lybm, chapterNo, q);
            }
        } catch (Exception e) {
            log.debug("写入质检问题失败 chapter={}：{}", chapterNo, e.getMessage());
        }
    }

    private void deleteIssues(String batchNo, String lybm, int chapterNo, String type) {
        if (type == null) {
            pgQuery.update(
                    "DELETE FROM sh_report_qc_issue WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND chapter_no=?",
                    batchNo, lybm, chapterNo);
        } else {
            pgQuery.update(
                    "DELETE FROM sh_report_qc_issue WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND chapter_no=? AND type=?",
                    batchNo, lybm, chapterNo, type);
        }
    }

    private void insertIssue(String batchNo, String lybm, int chapterNo, ShReportQcIssue q) {
        pgQuery.update(
                "INSERT INTO sh_report_qc_issue (id,batch_no,lybm,chapter_no,level,type,message,create_time) " +
                        "VALUES (?,?,?,?,?,?,?,NOW())",
                UUID.randomUUID().toString(), batchNo, lybm, chapterNo, q.getLevel(), q.getType(), q.getMessage());
    }

    public void cancelGenerating(String batchNo, String lybm, String note) {
        pgQuery.update(
                "UPDATE sh_report_chapter SET status=?, audit_note=?, update_time=NOW() WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND status=?",
                ShReportConstants.STATUS_FAILED, note == null ? "用户取消" : note, batchNo, lybm, ShReportConstants.STATUS_GENERATING);
    }

    public List<Map<String, Object>> listChapters(String batchNo, String lybm) {
        if (lybm == null || lybm.isEmpty()) {
            return pgQuery.queryForList(
                    "SELECT id, batch_no, lybm, lymc, chapter_no, chapter_name, content, content_prev, status, audit_note, fail_reason, flow_ver, material_ver, gen_cost_ms, update_time " +
                            "FROM sh_report_chapter WHERE batch_no=? ORDER BY lybm NULLS FIRST, chapter_no", batchNo);
        }
        return pgQuery.queryForList(
                "SELECT id, batch_no, lybm, lymc, chapter_no, chapter_name, content, content_prev, status, audit_note, fail_reason, flow_ver, material_ver, gen_cost_ms, update_time " +
                        "FROM sh_report_chapter WHERE batch_no=? AND lybm=? ORDER BY chapter_no", batchNo, lybm);
    }

    public String queryWatershedName(String wscd) {
        Map<String, Object> row = pgMapper.selectWatershedByCode(wscd);
        if (row == null || row.isEmpty()) {
            return wscd;
        }
        return ShReportPgQueryService.displayName(row.get("wsnm"), row.get("alias_name"));
    }

    /** 流域检索：精确 → 正向模糊 → 去口语前后缀 → 反向包含，逐级降级，最多 8 条（长名优先） */
    public List<Map<String, Object>> searchWatersheds(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String key = name.trim();
        List<Map<String, Object>> rows = toWatershedRows(pgMapper.selectWatershedsExact(key));
        if (rows.isEmpty()) {
            // 全称（wsnm）与别名（alias_name）都参与模糊匹配；用户说「官鹅沟流域」时别名「官鹅沟」可命中
            String shortKey = key.endsWith("流域") ? key.substring(0, key.length() - 2) : key;
            rows = toWatershedRows(pgMapper.selectWatershedsLike("%" + key + "%", "%" + shortKey + "%"));
        }
        if (rows.isEmpty()) {
            // 用户输入常带口语前后缀（「就是阿乌河小流域」「上九三公别河上游流域」），归一化后再匹配一轮
            String stripped = stripWatershedFillers(key);
            if (!stripped.equals(key)) {
                rows = toWatershedRows(pgMapper.selectWatershedsExact(stripped));
                if (rows.isEmpty()) {
                    rows = toWatershedRows(pgMapper.selectWatershedsLike("%" + stripped + "%", "%" + stripped + "%"));
                }
            }
        }
        if (rows.isEmpty()) {
            // 反向包含：用户输入比库名长时命中（「…上游流域」包含库名「…上游」）
            rows = toWatershedRows(pgMapper.selectWatershedsContaining(key));
        }
        return rows;
    }

    /** 精确检索全部落空时的兜底：按名称相似度（LCS）推荐最接近的流域，供用户点选 */
    public List<Map<String, Object>> suggestWatersheds(String name) {
        String key = stripWatershedFillers(name == null ? "" : name.trim());
        if (key.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> all = pgMapper.selectAllWatersheds();
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> row : all) {
            String nm = ShReportPgQueryService.displayName(row.get("wsnm"), row.get("alias_name"));
            double score = 2.0 * lcsLength(key, nm) / Math.max(1, key.length() + nm.length());
            if (score >= 0.3) {
                Map<String, Object> one = new java.util.HashMap<>();
                one.put("lybm", row.get("wscd"));
                one.put("lymc", nm);
                one.put("score", Math.round(score * 100) / 100.0);
                ranked.add(one);
            }
        }
        ranked.sort((a, b) -> Double.compare(((Number) b.get("score")).doubleValue(), ((Number) a.get("score")).doubleValue()));
        return ranked.size() > 8 ? new ArrayList<>(ranked.subList(0, 8)) : ranked;
    }

    /** 流域清单（按名称排序，供「让系统自行选择」或未提供名称时展示候选） */
    public List<Map<String, Object>> listWatersheds(int limit) {
        return toWatershedRows(pgMapper.selectWatershedsOrdered(limit));
    }

    private static int lcsLength(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return dp[a.length()][b.length()];
    }

    /** sh_map_watershed 行 → 流域候选（lybm=wscd，lymc=别名+流域 或全称） */
    private static List<Map<String, Object>> toWatershedRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> one = new HashMap<>();
            one.put("lybm", row.get("wscd"));
            one.put("lymc", ShReportPgQueryService.displayName(row.get("wsnm"), row.get("alias_name")));
            out.add(one);
        }
        return out;
    }

    /** 去掉流域口语前后缀（「就是阿乌河小流域」→「阿乌河」） */
    private static String stripWatershedFillers(String key) {
        return key.replaceAll("^(好的|那么|就是|就|是|请|帮我|我想|我要|给我)+", "")
                .replaceAll("(山洪沟|小流域|流域|沟道|河道|河沟)+$", "")
                .trim();
    }

    public void saveChapter(String batchNo, String lybm, String lymc, int chapterNo, String content,
                           String status, String auditNote) {
        saveChapter(batchNo, lybm, lymc, chapterNo, content, status, auditNote, null, null, null);
    }

    /**
     * failReason：状态=失败 时的明确原因，落独立字段，content 留空（不再用占位文案承载原因）。
     * flowVersion：生成所用流程版本（流程名@设计更新时间），供追溯。
     */
    public void saveChapter(String batchNo, String lybm, String lymc, int chapterNo, String content,
                           String status, String auditNote, String failReason, String flowVersion) {
        saveChapter(batchNo, lybm, lymc, chapterNo, content, status, auditNote, failReason, flowVersion, null);
    }

    /**
     * preservePrevVersion：是否把旧正文挡在 content_prev 之外（质检/回写过程中落库时必须为 true，
     * 否则中间稿会覆盖用户已审核的上一版）。
     * 传 null 表示按旧规则自动判断（任一侧处于「生成中」即保留）；true 强制保留；false 强制不保留。
     */
    public void saveChapter(String batchNo, String lybm, String lymc, int chapterNo, String content,
                           String status, String auditNote, String failReason, String flowVersion,
                           Boolean preservePrevVersion) {
        content = trimToChapter(content, chapterNo);
        boolean failed = ShReportConstants.STATUS_FAILED.equals(status);
        if (failed && (content == null || content.isBlank() || ShReportTextChecker.isPlaceholder(content))) {
            content = "";
        } else if (content == null || content.trim().isEmpty()) {
            content = "【系统默认内容】本章内容生成结果为空，请重新生成本章节。";
        }
        if (failed && (failReason == null || failReason.isBlank())) {
            failReason = "生成失败";
        }
        String chapterName = ShReportConstants.chapterName(chapterNo);
        String materialVer = lastMaterialVer(batchNo, chapterNo);
        List<Map<String, Object>> exist = pgQuery.queryForList(
                "SELECT id, content, status FROM sh_report_chapter WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND chapter_no=?",
                batchNo, lybm, chapterNo);
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        if (!exist.isEmpty()) {
            String oldContent = exist.get(0).get("content") == null ? "" : exist.get(0).get("content").toString();
            String oldStatus = exist.get(0).get("status") == null ? "" : exist.get(0).get("status").toString();
            // 占位/生成中文案不覆盖上一版；质检过程中的落库由调用方显式要求保留上一版
            boolean keepPrev = ShReportTextChecker.isPlaceholder(oldContent)
                    || Boolean.TRUE.equals(preservePrevVersion)
                    || (preservePrevVersion == null
                        && (ShReportConstants.STATUS_GENERATING.equals(status)
                            || ShReportConstants.STATUS_GENERATING.equals(oldStatus)));
            if (keepPrev) {
                pgQuery.update(
                        "UPDATE sh_report_chapter SET content=?, lymc=?, status=?, audit_note=?, fail_reason=?, flow_ver=?, material_ver=?, update_time=? " +
                                "WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND chapter_no=?",
                        content, lymc, status, auditNote, failReason, flowVersion, materialVer, now, batchNo, lybm, chapterNo);
            } else {
                pgQuery.update(
                        "UPDATE sh_report_chapter SET content_prev=content, content=?, lymc=?, status=?, audit_note=?, fail_reason=?, flow_ver=?, material_ver=?, update_time=? " +
                                "WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND chapter_no=?",
                        content, lymc, status, auditNote, failReason, flowVersion, materialVer, now, batchNo, lybm, chapterNo);
            }
        } else {
            pgQuery.update(
                    "INSERT INTO sh_report_chapter (id, batch_no, lybm, lymc, chapter_no, chapter_name, content, content_prev, status, audit_note, fail_reason, flow_ver, material_ver, create_time, update_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), batchNo, lybm, lymc, chapterNo, chapterName,
                    content, null, status, auditNote, failReason, flowVersion, materialVer, now, now);
        }
    }

    /** Markdown 标题行里的中文数字章节编号（一、二、…），七 章 + 附录/附件 */
    private static final Pattern CHAPTER_HEADING = Pattern.compile("(?m)^#{1,6}\\s*([一二三四五六七八])\\s*、");
    private static final Pattern APPENDIX_HEADING = Pattern.compile("(?m)^#{1,6}\\s*(附录|附件)\\s*(：|:|$)");
    private static final String CN_NUMERALS = "一二三四五六七八";

    /**
     * 落库防线：把模型输出裁剪到本章范围。
     * 模型偶尔无视「只写本章」的指令（尤其是 requirement 里带着「整份报告」字样时），
     * 会把报告题名、前言和其他章节整篇带出来。这里按 Markdown 标题行定位：
     * - 找到本章编号标题行；chapterNo&gt;1 时丢弃其之前的杂项，chapterNo==1 时仅在前缀含「前言」时保留
     *   （报告题名与前言没有独立的存储章节，由第一章承载）；
     * - 从本章内容开始，在第一处其他章节编号标题（或附录标题）处截断。
     * 没有任何可识别标题时原样返回，不影响正常内容与「正在生成…」等状态文案。
     */
    static String trimToChapter(String content, int chapterNo) {
        if (content == null || content.isEmpty() || chapterNo < 1 || chapterNo > 7) {
            return content;
        }
        List<int[]> heads = new ArrayList<>();
        Matcher ch = CHAPTER_HEADING.matcher(content);
        while (ch.find()) {
            heads.add(new int[]{CN_NUMERALS.indexOf(ch.group(1)) + 1, ch.start()});
        }
        if (chapterNo <= 6) {
            Matcher ap = APPENDIX_HEADING.matcher(content);
            while (ap.find()) {
                heads.add(new int[]{0, ap.start()});
            }
            heads.sort((a, b) -> a[1] - b[1]);
        }
        if (heads.isEmpty()) {
            return promoteChapterTitle(content, chapterNo);
        }
        int ownStart = -1;
        for (int[] h : heads) {
            if (h[0] == chapterNo) {
                ownStart = h[1];
                break;
            }
        }
        int bodyStart = 0;
        if (ownStart >= 0) {
            if (chapterNo > 1) {
                bodyStart = ownStart;
            } else {
                String prefix = content.substring(0, ownStart);
                bodyStart = prefix.contains("前言") ? 0 : ownStart;
            }
        }
        int tailCut = content.length();
        for (int[] h : heads) {
            if (h[1] > Math.max(bodyStart, 0) && h[0] != chapterNo) {
                tailCut = h[1];
                break;
            }
        }
        String trimmed = content.substring(bodyStart, tailCut).trim();
        return promoteChapterTitle(trimmed.isEmpty() ? content.trim() : trimmed, chapterNo);
    }

    /**
     * 章标题统一为一级 Markdown（行首一个 #），与其余各章一致。
     * 模型有时把「一、流域概况」写成 ##，这里只提升本章标题那一行，小节级别不动。
     */
    static String promoteChapterTitle(String content, int chapterNo) {
        if (content == null || content.isEmpty() || chapterNo < 1 || chapterNo > CN_NUMERALS.length()) {
            return content;
        }
        String cn = String.valueOf(CN_NUMERALS.charAt(chapterNo - 1));
        Matcher m = Pattern.compile("(?m)^(#{2,6})[ \\t]+((?:第\\s*)?" + cn + "(?:\\s*章)?[、.．\\s].*)$")
                .matcher(content);
        if (!m.find()) {
            return content;
        }
        return content.substring(0, m.start()) + "# " + m.group(2) + content.substring(m.end());
    }

    /** 查询章节质检问题（供面板展示） */
    public List<Map<String, Object>> listQcIssues(String batchNo, String lybm) {        if (batchNo == null || batchNo.isEmpty()) {
            return List.of();
        }
        try {
            return lybm == null || lybm.isEmpty()
                    ? pgQuery.queryForList(
                            "SELECT id,batch_no AS batchNo,lybm,chapter_no AS chapterNo,level,type,message,create_time AS createTime " +
                                    "FROM sh_report_qc_issue WHERE batch_no=? ORDER BY chapter_no, level DESC", batchNo)
                    : pgQuery.queryForList(
                            "SELECT id,batch_no AS batchNo,lybm,chapter_no AS chapterNo,level,type,message,create_time AS createTime " +
                                    "FROM sh_report_qc_issue WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') ORDER BY chapter_no, level DESC",
                            batchNo, lybm);
        } catch (Exception e) {
            log.debug("查询质检问题失败：{}", e.getMessage());
            return List.of();
        }
    }

    public void markGenerating(String batchNo, String lybm, String lymc, int chapterNo) {
        saveChapter(batchNo, lybm, lymc, chapterNo, "正在生成…", ShReportConstants.STATUS_GENERATING, null);
        // 记录本章本次生成的开始时间（重置上一次的耗时），写完后由 markChapterWritten 结算
        try {
            pgQuery.update(
                    "UPDATE sh_report_chapter SET gen_start_ts=?, gen_cost_ms=NULL " +
                            "WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND chapter_no=?",
                    System.currentTimeMillis(), batchNo, lybm, chapterNo);
        } catch (Exception e) {
            log.debug("记录章节生成开始时间失败：{}", e.getMessage());
        }
    }

    /**
     * 单章正文写完（质检/汇总前）调用：结算本章耗时 = markGenerating 以来的时长。
     * 质检回写重试不重新计起点，耗时覆盖"开始生成→最终完成"的全过程。
     */
    public void markChapterWritten(String batchNo, String lybm, int chapterNo) {
        try {
            long now = System.currentTimeMillis();
            pgQuery.update(
                    "UPDATE sh_report_chapter SET gen_cost_ms=? - gen_start_ts " +
                            "WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND chapter_no=? AND gen_start_ts IS NOT NULL",
                    now, batchNo, lybm, chapterNo);
        } catch (Exception e) {
            log.debug("记录章节生成耗时失败：{}", e.getMessage());
        }
    }

    /** 跨章矛盾挂在第 7 章质检表（先清旧 CONSISTENCY） */
    public void replaceConsistencyIssues(String batchNo, String lybm, List<ShReportQcIssue> issues) {
        try {
            deleteIssues(batchNo, lybm, ShReportConstants.CHAPTER_COUNT, ShReportQcIssue.TYPE_CONSISTENCY);
            if (issues == null) {
                return;
            }
            for (ShReportQcIssue q : issues) {
                insertIssue(batchNo, lybm, ShReportConstants.CHAPTER_COUNT, q);
            }
        } catch (Exception e) {
            log.debug("写入跨章质检失败：{}", e.getMessage());
        }
    }

    public Map<Integer, String> loadChapterContents(String batchNo, String lybm) {
        Map<Integer, String> chapters = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = pgQuery.queryForList(
                    "SELECT chapter_no, content FROM sh_report_chapter WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') " +
                            "AND status<>'失败' ORDER BY chapter_no", batchNo, lybm);
            for (Map<String, Object> r : rows) {
                int no = ((Number) r.get("chapter_no")).intValue();
                Object c = r.get("content");
                chapters.put(no, c == null ? "" : c.toString());
            }
        } catch (Exception e) {
            log.debug("读取已落库章节失败：{}", e.getMessage());
        }
        return chapters;
    }

    /** 单章流程写作的附加出入参容器：范文来源回填、上一稿（修订重写）、token 用量回传 */
    public static class FlowWriteContext {
        /** 回填本章参考的一期成果范文文档名 */
        public final List<String> kbSourcesOut = new ArrayList<>();
        /** 上一稿正文（质检未过的版本），非空时作为「修订重写」上下文注入流程 */
        public String prevDraft;
        /** 流程事件里解析到的 token 用量（scene/chapterNo 由调用方补充） */
        public java.util.function.Consumer<com.alibaba.fastjson.JSONObject> usageSink;
    }

    /** 从占位文案提取失败原因（【系统默认内容】后的部分）；非占位返回 null */
    public static String placeholderReason(String content) {
        if (content == null) {
            return null;
        }
        String t = content.trim();
        String marker = "【系统默认内容】";
        if (t.startsWith(marker)) {
            String r = t.substring(marker.length()).trim();
            return r.isEmpty() ? "生成失败" : r;
        }
        return null;
    }

    public String writeViaFlow(String batchNo, String lybm, String lymc, int chapterNo,
                               String requirement, String outline, String modelId) {
        return writeViaFlow(batchNo, lybm, lymc, chapterNo, requirement, outline, modelId, new FlowWriteContext());
    }

    public String writeViaFlow(String batchNo, String lybm, String lymc, int chapterNo,
                               String requirement, String outline, String modelId, FlowWriteContext ctx) {
        return runChapterFlow(batchNo, lybm, lymc, chapterNo, requirement, outline, modelId, ctx);
    }

    private String runChapterFlow(String batchNo, String lybm, String lymc, int chapterNo,
                                  String requirement, String outline, String modelId, FlowWriteContext ctx) {
        FlowWriteContext c = ctx == null ? new FlowWriteContext() : ctx;
        String flowName = ShReportConstants.flowName(chapterNo);
        Map<String, Object> flowRow = modelVariantService.loadEnabledChapterFlow(chapterNo);
        if (flowRow == null) {
            return "【系统默认内容】未找到流程：" + flowName;
        }
        String chainEl = String.valueOf(flowRow.get("chain"));
        if (ShReportModelVariant.isBrokenChapterChain(chainEl)) {
            return "【系统默认内容】流程「" + flowName + "」编排损坏：降级节点被拖成了并行分支（WHEN 且无 CATCH），"
                    + "并行默认只等 15 秒，主链路会被取消。请在设计器中把 end-fb 改回 CATCH 兜底节点后重试。";
        }
        // 记录流程版本（流程名@设计更新时间），persist 时落 flow_ver，便于追溯生成所用提示词版本
        Object designTime = flowRow.get("design_time");
        flowVerByChapter.put((batchNo == null ? "" : batchNo) + "|" + chapterNo,
                flowName + "@" + (designTime == null ? "unknown" : String.valueOf(designTime)));
        Map<String, Object> input = buildChapterFlowInput(batchNo, lybm, lymc, chapterNo, requirement, outline, c);
        AtomicReference<String> contentRef = new AtomicReference<>(null);
        String runtimeModel = modelVariantService.resolveRuntimeModelId(modelId);
        // 一次流程运行只上报一次用量（流程事件可能多条都带 token 字段）
        java.util.concurrent.atomic.AtomicBoolean usageReported = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.function.Consumer<com.alibaba.fastjson.JSONObject> usageSink = c.usageSink == null ? null
                : u -> {
                    if (usageReported.compareAndSet(false, true)) {
                        c.usageSink.accept(u);
                    }
                };
        try {
            concurrencyLimiter.withLlmPermit(() -> {
                if (runtimeModel == null) {
                    runStoredChapterFlow(flowRow, input, contentRef, usageSink);
                } else {
                    runOverlayChapterFlow(flowRow, input, runtimeModel, contentRef, usageSink);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("山洪报告章节流程失败 batch={} lybm={} chapter={}", batchNo, lybm, chapterNo, e);
            return "【系统默认内容】本章生成异常：" + e.getMessage();
        }
        String fromCallback = contentRef.get();
        String fromDb = latestContent(batchNo, lybm, chapterNo);
        if (fromDb != null && !ShReportTextChecker.isPlaceholder(fromDb) && !"正在生成…".equals(fromDb.trim())) {
            return fromDb;
        }
        if (fromCallback != null && !fromCallback.isEmpty()) {
            return fromCallback;
        }
        return "【系统默认内容】本章生成失败（模型未配置或调用异常）。请确认已配置可用的大模型 API Key 后重新生成本章节。";
    }

    /** 组装单章流程入参：数据包、提纲、补充资料（用户要求 + 第4章计算参数 + 上一稿）。不注入知识库范文。 */
    private Map<String, Object> buildChapterFlowInput(String batchNo, String lybm, String lymc, int chapterNo,
                                                      String requirement, String outline, FlowWriteContext ctx) {
        Map<String, Object> input = new HashMap<>();
        input.put("batch_no", batchNo);
        input.put("lybm", lybm);
        input.put("lymc", lymc);
        if (lymc != null) {
            input.put("wsmc", lymc);
        }
        boolean hasHistory = materialService != null && materialService.enabled()
                && materialService.hasHistoryMaterials(lybm, batchNo);
        input.put("sjbao", dataPacketService.buildPacket(chapterNo, lybm, lymc, hasHistory));
        String chapterOutline = ShReportOutlineService.toChapterPrompt(outline, chapterNo);
        if (chapterOutline != null && !chapterOutline.isEmpty()) {
            input.put("tigang", chapterOutline);
        }
        boolean halfBudget = ctx != null && ctx.prevDraft != null && !ctx.prevDraft.isBlank();
        String materials = materialService == null || !materialService.enabled()
                ? null : materialService.buildChapterMaterials(batchNo, lybm, chapterNo, halfBudget);
        String buochong = buildBuochong(requirement,
                chapterNo == 4 ? dataPacketService.ch4ParamsText() : null,
                ctx.prevDraft);
        if (chapterNo == 5 && materials != null) {
            if (materials.contains("洪痕")) {
                input.put("honghen", materials);
            }
            input.put("lishijilv", materials);
            String note = "本章历史灾害资料已注入「历史灾害记录」入参，请依据该入参撰写；引用年份、场次、损失时必须写明来源文件名。";
            buochong = buochong == null ? note : buochong + "\n\n" + note;
        } else if (materials != null) {
            String block = materialService.materialRulesBlock() + materials;
            buochong = buochong == null ? block : buochong + "\n\n" + block;
        }
        if (buochong != null) {
            input.put("buochong", buochong);
        }
        if (chapterNo == 7) {
            List<Map<String, Object>> rows = pgQuery.queryForList(
                    "SELECT chapter_no, content FROM sh_report_chapter WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') ORDER BY chapter_no",
                    batchNo, lybm);
            for (Map<String, Object> r : rows) {
                input.put("ch" + ((Number) r.get("chapter_no")).intValue(),
                        r.get("content") == null ? "" : r.get("content").toString());
            }
        }
        return input;
    }

    /**
     * 组装「补充资料」入参：用户要求 + 第4章计算参数 + 上一稿（修订重写）。
     * 知识库范文不在生成时注入，只在对话里问写法时检索。全部为空时返回 null。
     */
    static String buildBuochong(String requirement, String ch4Params, String prevDraft) {
        StringBuilder sb = new StringBuilder();
        String req = sanitizeRequirement(requirement);
        if (req != null && !req.isEmpty()) {
            sb.append("用户要求（请重点落实）：").append(req);
        }
        if (ch4Params != null && !ch4Params.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(ch4Params);
        }
        if (prevDraft != null && !prevDraft.isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("【上一稿】本次为修订重写：请在改正问题的同时，尽量保留上一稿中正确的内容、体例与数字：\n")
                    .append(prevDraft);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 纯生成触发语（如「帮我生成整份报告」「帮我生成XX流域的整份报告」）。
     * 它只是发起生成的指令，不是对章节内容的补充要求；原样注入每章提示词会诱导模型
     * 把整份报告（标题/前言/其他章节）都写进当前章。只有整条 requirement 都是触发语时才丢弃，
     * 带实质要求的（如「帮我生成整份报告，表格再细化」）原样保留。
     */
    static final Pattern TRIGGER_ONLY = Pattern.compile(
            "^[\\s，,。！!请帮麻烦]*我?(重新)?生成(整份|完整|全部|全本|整个|所有)?(的)?"
                    + "([\\u4e00-\\u9fa5a-zA-Z0-9]{0,30}?)?报告$");

    static String sanitizeRequirement(String requirement) {
        if (requirement == null) {
            return null;
        }
        String t = requirement.trim();
        return (t.isEmpty() || TRIGGER_ONLY.matcher(t).matches()) ? null : t;
    }

    /** 未选模型：跑设计器里保存的基础流程。 */
    private void runStoredChapterFlow(Map<String, Object> flowRow, Map<String, Object> input,
                                      AtomicReference<String> contentRef,
                                      java.util.function.Consumer<com.alibaba.fastjson.JSONObject> usageSink) {
        Object designObj = flowRow.get("design");
        String patched = ShReportModelVariant.relaxPacketOnlySource(
                designObj == null ? null : String.valueOf(designObj));
        if (patched != null && designObj != null && !patched.equals(String.valueOf(designObj))) {
            // 库内流程尚未打补丁时走内存 design，避免仍写「唯一事实来源」
            AiragFlow overlay = new AiragFlow();
            overlay.setChain(String.valueOf(flowRow.get("chain")));
            overlay.setDesign(patched);
            overlay.setName(String.valueOf(flowRow.get("name")));
            FlowDebugParams debug = new FlowDebugParams();
            debug.setFlow(overlay);
            debug.setInputParams(input);
            debug.setResponseMode("blocking");
            try {
                Object ret = airagFlowService.debugFlow(debug);
                extractUsage(ret, usageSink);
                String text = extractFlowOutputText(ret);
                if (text != null && !text.isBlank()) {
                    contentRef.set(stripJsonWrapper(text));
                    return;
                }
            } catch (Exception e) {
                log.warn("PACKET_LINE 补丁流程运行失败，回退原流程: {}", e.getMessage());
            } finally {
                removeTempFlowChainQuietly(debug.getFlowId());
            }
        }
        FlowRunParams params = new FlowRunParams();
        params.setFlowId(String.valueOf(flowRow.get("id")));
        params.setInputParams(input);
        params.setResponseMode("streaming");
        params.setEventCallback(eventData -> captureFlowOutput(eventData, contentRef, usageSink));
        airagFlowService.runFlow(params);
    }

    /**
     * 选了模型：debugFlow 用临时 ID 加载内存 design（只改 LLM 节点模型），不写 airag_flow。
     * debugFlow 会覆盖 eventCallback，因此用 blocking 从 Result 取正文。
     */
    private void runOverlayChapterFlow(Map<String, Object> flowRow, Map<String, Object> input, String modelId,
                                       AtomicReference<String> contentRef,
                                       java.util.function.Consumer<com.alibaba.fastjson.JSONObject> usageSink) {
        Object designObj = flowRow.get("design");
        String modelName = lookupModelName(modelId);
        String designJson = designObj == null ? null : String.valueOf(designObj);
        designJson = ShReportModelVariant.relaxPacketOnlySource(designJson);
        String overlaid = ShReportModelVariant.replaceLlmModelId(designJson, modelId, modelName);
        if (overlaid == null) {
            log.warn("基础流程无 llm 节点，按原流程运行 name={}", flowRow.get("name"));
            runStoredChapterFlow(flowRow, input, contentRef, usageSink);
            return;
        }
        AiragFlow overlay = new AiragFlow();
        overlay.setChain(String.valueOf(flowRow.get("chain")));
        overlay.setDesign(overlaid);
        overlay.setName(String.valueOf(flowRow.get("name")));
        FlowDebugParams params = new FlowDebugParams();
        params.setFlow(overlay);
        params.setInputParams(input);
        params.setResponseMode("blocking");
        try {
            Object ret = airagFlowService.debugFlow(params);
            extractUsage(ret, usageSink);
            String text = extractFlowOutputText(ret);
            if (text != null && !text.isBlank()) {
                contentRef.set(stripJsonWrapper(text));
            } else {
                log.warn("debugFlow 未解析到章节正文，回退基础流程 name={} result={}",
                        flowRow.get("name"), describeFlowResult(ret));
                runStoredChapterFlow(flowRow, input, contentRef, usageSink);
            }
        } finally {
            removeTempFlowChainQuietly(params.getFlowId());
        }
    }

    /**
     * 移除 debugFlow 的临时链。debugFlow 内部通常已经清理过，这里若直接 removeChain，
     * LiteFlow 会在链不存在时打 ERROR「cannot find the chain[...]」，排障时容易被误当成失败原因。
     */
    private void removeTempFlowChainQuietly(String flowId) {
        if (flowId == null || flowId.isBlank() || !FlowBus.containChain(flowId)) {
            return;
        }
        try {
            FlowBus.removeChain(flowId);
        } catch (Exception e) {
            log.debug("移除临时流程链失败：{}", e.getMessage());
        }
    }

    private void captureFlowOutput(Object eventData, AtomicReference<String> contentRef,
                                   java.util.function.Consumer<com.alibaba.fastjson.JSONObject> usageSink) {
        try {
            Map<String, Object> data = eventDataToMap(eventData);
            extractUsage(data, usageSink);
            Object finished = data.get("outputs");
            if (finished instanceof Map) {
                String text = unwrapOutputText(finished);
                if (text != null && !text.isEmpty()) {
                    contentRef.set(stripJsonWrapper(text));
                    return;
                }
            }
            String direct = unwrapOutputText(data);
            if (direct != null && !direct.isEmpty()) {
                contentRef.set(stripJsonWrapper(direct));
            }
        } catch (Exception ex) {
            log.warn("解析流程事件失败", ex);
        }
    }

    /**
     * 从流程事件数据里递归查找 token 用量字段（LLM 节点/结束节点的具体位置随设计器版本变化）。
     * 命中 inputTokens+outputTokens（或 snake_case 等价字段）时组装一次用量回调。
     */
    static void extractUsage(Object data, java.util.function.Consumer<com.alibaba.fastjson.JSONObject> sink) {
        if (data == null || sink == null) {
            return;
        }
        if (data instanceof Map<?, ?> map) {
            Object in = firstNumber(map, "inputTokens", "input_tokens", "promptTokens", "prompt_tokens");
            Object out = firstNumber(map, "outputTokens", "output_tokens", "completionTokens", "completion_tokens");
            if (in != null && out != null) {
                com.alibaba.fastjson.JSONObject u = new com.alibaba.fastjson.JSONObject();
                u.put("inputTokens", in);
                u.put("outputTokens", out);
                Object total = firstNumber(map, "totalTokens", "total_tokens");
                if (total != null) {
                    u.put("totalTokens", total);
                }
                Object estimated = map.get("estimated");
                u.put("estimated", Boolean.TRUE.equals(estimated));
                sink.accept(u);
                return;
            }
            for (Object v : map.values()) {
                extractUsage(v, sink);
            }
        } else if (data instanceof Iterable<?> it) {
            for (Object item : it) {
                extractUsage(item, sink);
            }
        }
    }

    private static Number firstNumber(Map<?, ?> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v instanceof Number n) {
                return n;
            }
        }
        return null;
    }

    /** 从 debugFlow / runFlow 的 Result 或结束节点 Map 取出章节正文。 */
    public static String extractFlowOutputText(Object ret) {
        if (ret == null) {
            return null;
        }
        Object data = ret;
        if (ret instanceof Result) {
            Result<?> r = (Result<?>) ret;
            if (!r.isSuccess()) {
                return null;
            }
            data = r.getResult();
        }
        return unwrapOutputText(data);
    }

    public static String unwrapOutputText(Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof CharSequence) {
            String s = data.toString().trim();
            return s.isEmpty() ? null : s;
        }
        if (data instanceof Map<?, ?> map) {
            for (String key : new String[]{"outputText", "output_text", "text", "outputContent", "content"}) {
                Object text = map.get(key);
                if (text instanceof CharSequence && !text.toString().isBlank()) {
                    return text.toString();
                }
            }
            for (String key : new String[]{"outputs", "result", "data"}) {
                Object nested = map.get(key);
                if (nested != null && nested != data) {
                    String inner = unwrapOutputText(nested);
                    if (inner != null) {
                        return inner;
                    }
                }
            }
        }
        if (data instanceof Iterable<?> it) {
            for (Object item : it) {
                if (item == data) {
                    continue;
                }
                String inner = unwrapOutputText(item);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    private String lookupModelName(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT model_name FROM airag_model WHERE id=?", String.class, modelId.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    static String describeFlowResult(Object ret) {
        if (ret == null) {
            return "null";
        }
        if (ret instanceof Result<?> r) {
            Object data = r.getResult();
            String keys = "";
            if (data instanceof Map<?, ?> map) {
                keys = String.valueOf(map.keySet());
            }
            return "Result success=" + r.isSuccess() + " dataType="
                    + (data == null ? "null" : data.getClass().getName()) + " keys=" + keys;
        }
        if (ret instanceof Map<?, ?> map) {
            return ret.getClass().getName() + " keys=" + map.keySet();
        }
        return ret.getClass().getName();
    }

    private String latestContent(String batchNo, String lybm, int chapterNo) {
        List<String> rows = pgQuery.queryForList(
                "SELECT content FROM sh_report_chapter WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND chapter_no=?",
                String.class, batchNo, lybm, chapterNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static String stripJsonWrapper(String text) {
        String t = text.trim();
        if (t.startsWith("[\"" ) || t.startsWith("[{")) {
            Matcher m = Pattern.compile("\"result\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(t);
            if (m.find()) {
                return m.group(1).replace("\\n", "\n").replace("\\\"", "\"");
            }
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> eventDataToMap(Object eventData) {
        Map<String, Object> out = new HashMap<>();
        for (java.lang.reflect.Field f : eventData.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                out.put(f.getName(), f.get(eventData));
            } catch (Exception ignore) {
            }
        }
        Object data = out.get("data");
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        out.put("outputs", null);
        return out;
    }
}
