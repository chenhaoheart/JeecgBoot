package org.jeecg.modules.airag.shreport.graph;

import org.bsc.langgraph4j.state.AgentState;
import org.jeecg.modules.airag.shreport.entity.ShReportQcIssue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 山洪报告生成图状态。节点返回的 Map 按 key 整份覆盖（不使用 appender）。
 */
public class ShReportGraphState extends AgentState {

    public static final String BATCH_NO = "batchNo";
    public static final String LYBM = "lybm";
    public static final String LYMC = "lymc";
    public static final String OUTLINE = "outline";
    public static final String MODEL_ID = "modelId";
    public static final String BASE_REQUIREMENT = "baseRequirement";
    public static final String REQUIREMENT = "requirement";
    public static final String TARGET_CHAPTERS = "targetChapters";
    public static final String CHAPTERS = "chapters";
    public static final String QC_ISSUES = "qcIssues";
    public static final String CONSISTENCY_ISSUES = "consistencyIssues";
    public static final String RETRY_COUNT = "retryCount";
    public static final String MAX_RETRY = "maxRetry";
    public static final String AUTO_REWRITE = "autoRewrite";
    public static final String CANCELLED = "cancelled";
    public static final String FULL_REPORT = "fullReport";
    public static final String CH7_WRITTEN = "ch7Written";
    public static final String SUCCESS = "success";

    public static final String ROUTE_REWRITE = "rewrite";
    public static final String ROUTE_WRITE_CH7 = "write_ch7";
    public static final String ROUTE_CONSISTENCY = "consistency";
    public static final String ROUTE_PERSIST = "persist";

    public ShReportGraphState(Map<String, Object> initData) {
        super(initData);
    }

    public static Map<String, Object> initial(String batchNo, String lybm, String lymc, String requirement,
                                              String outline, String modelId, List<Integer> targetChapters,
                                              boolean fullReport, int maxRetry) {
        return initial(batchNo, lybm, lymc, requirement, outline, modelId, targetChapters, fullReport, maxRetry, false);
    }

    public static Map<String, Object> initial(String batchNo, String lybm, String lymc, String requirement,
                                              String outline, String modelId, List<Integer> targetChapters,
                                              boolean fullReport, int maxRetry, boolean autoRewrite) {
        Map<String, Object> m = new HashMap<>();
        m.put(BATCH_NO, nvl(batchNo));
        m.put(LYBM, nvl(lybm));
        m.put(LYMC, nvl(lymc));
        m.put(OUTLINE, nvl(outline));
        m.put(MODEL_ID, nvl(modelId));
        m.put(BASE_REQUIREMENT, nvl(requirement));
        m.put(REQUIREMENT, nvl(requirement));
        m.put(TARGET_CHAPTERS, new ArrayList<>(targetChapters));
        m.put(CHAPTERS, new LinkedHashMap<String, String>());
        m.put(QC_ISSUES, new LinkedHashMap<String, List<ShReportQcIssue>>());
        m.put(CONSISTENCY_ISSUES, new ArrayList<ShReportQcIssue>());
        m.put(RETRY_COUNT, 0);
        m.put(MAX_RETRY, maxRetry);
        m.put(AUTO_REWRITE, autoRewrite);
        m.put(CANCELLED, false);
        m.put(FULL_REPORT, fullReport);
        m.put(CH7_WRITTEN, false);
        m.put(SUCCESS, false);
        return m;
    }

    public String batchNo() {
        return str(BATCH_NO);
    }

    public String lybm() {
        return str(LYBM);
    }

    public String lymc() {
        return str(LYMC);
    }

    public String outline() {
        return str(OUTLINE);
    }

    public String modelId() {
        return str(MODEL_ID);
    }

    public String baseRequirement() {
        return str(BASE_REQUIREMENT);
    }

    public String requirement() {
        return str(REQUIREMENT);
    }

    public List<Integer> targetChapters() {
        return intList(TARGET_CHAPTERS);
    }

    @SuppressWarnings("unchecked")
    public Map<Integer, String> chapters() {
        Object v = value(CHAPTERS).orElse(null);
        if (v instanceof Map<?, ?> raw) {
            Map<Integer, String> out = new LinkedHashMap<>();
            raw.forEach((k, val) -> out.put(toInt(k), val == null ? "" : String.valueOf(val)));
            return out;
        }
        return new LinkedHashMap<>();
    }

    /** LangGraph 序列化要求 Map key 必须是 String */
    public static Map<String, String> chaptersPayload(Map<Integer, String> chapters) {
        Map<String, String> out = new LinkedHashMap<>();
        if (chapters != null) {
            chapters.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : v));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<Integer, List<ShReportQcIssue>> qcIssues() {
        Object v = value(QC_ISSUES).orElse(null);
        if (v instanceof Map<?, ?> raw) {
            Map<Integer, List<ShReportQcIssue>> out = new LinkedHashMap<>();
            raw.forEach((k, val) -> {
                List<ShReportQcIssue> list = new ArrayList<>();
                if (val instanceof Collection<?> c) {
                    for (Object o : c) {
                        if (o instanceof ShReportQcIssue issue) {
                            list.add(issue);
                        }
                    }
                }
                out.put(toInt(k), list);
            });
            return out;
        }
        return new LinkedHashMap<>();
    }

    public static Map<String, List<ShReportQcIssue>> qcIssuesPayload(Map<Integer, List<ShReportQcIssue>> issues) {
        Map<String, List<ShReportQcIssue>> out = new LinkedHashMap<>();
        if (issues != null) {
            issues.forEach((k, v) -> out.put(String.valueOf(k), v == null ? List.of() : v));
        }
        return out;
    }

    public List<ShReportQcIssue> consistencyIssues() {
        Object v = value(CONSISTENCY_ISSUES).orElse(null);
        List<ShReportQcIssue> list = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                if (o instanceof ShReportQcIssue issue) {
                    list.add(issue);
                }
            }
        }
        return list;
    }

    public int retryCount() {
        return toInt(value(RETRY_COUNT).orElse(0));
    }

    public int maxRetry() {
        return toInt(value(MAX_RETRY).orElse(2));
    }

    /** 是否允许质检/跨章不达标时自动回写重试。默认关闭：质检结果只作参考，由用户决定编辑或重新生成 */
    public boolean autoRewrite() {
        return Boolean.TRUE.equals(value(AUTO_REWRITE).orElse(false));
    }

    public boolean cancelled() {
        return Boolean.TRUE.equals(value(CANCELLED).orElse(false));
    }

    public boolean fullReport() {
        return Boolean.TRUE.equals(value(FULL_REPORT).orElse(false));
    }

    public boolean ch7Written() {
        return Boolean.TRUE.equals(value(CH7_WRITTEN).orElse(false));
    }

    public boolean success() {
        return Boolean.TRUE.equals(value(SUCCESS).orElse(false));
    }

    public boolean hasBlocking() {
        for (List<ShReportQcIssue> issues : qcIssues().values()) {
            if (hasBlockingIssues(issues)) {
                return true;
            }
        }
        return false;
    }

    public List<Integer> failedChapters() {
        List<Integer> failed = new ArrayList<>();
        qcIssues().forEach((ch, issues) -> {
            if (hasBlockingIssues(issues)) {
                failed.add(ch);
            }
        });
        Collections.sort(failed);
        return failed;
    }

    /** 跨章矛盾涉及的章：取自 issue 的结构化 chapterNos，不解析文案 */
    public List<Integer> consistencyConflictChapters() {
        List<Integer> nos = new ArrayList<>();
        for (ShReportQcIssue issue : consistencyIssues()) {
            if (issue == null) {
                continue;
            }
            for (int n : issue.chapterNos()) {
                if (n >= 1 && n <= 7 && !nos.contains(n)) {
                    nos.add(n);
                }
            }
        }
        Collections.sort(nos);
        return nos;
    }

    /** 质检后：回写 / 写第 7 章 / 落库。autoRewrite=false（默认）时质检不达标不回写，直接续流程 */
    public String routeAfterQc() {
        if (cancelled()) {
            return ROUTE_PERSIST;
        }
        if (autoRewrite() && hasBlocking() && retryCount() < maxRetry()) {
            return ROUTE_REWRITE;
        }
        if (fullReport() && !ch7Written()) {
            return ROUTE_WRITE_CH7;
        }
        return ROUTE_PERSIST;
    }

    /** 跨章校验后：矛盾回写或落库（HINT 不阻断最终落库）。autoRewrite=false（默认）时只记录不回写 */
    public String routeAfterConsistency() {
        if (cancelled()) {
            return ROUTE_PERSIST;
        }
        if (autoRewrite() && !consistencyConflictChapters().isEmpty() && retryCount() < maxRetry()) {
            return ROUTE_REWRITE;
        }
        return ROUTE_PERSIST;
    }

    private static boolean hasBlockingIssues(List<ShReportQcIssue> issues) {
        if (issues == null) {
            return false;
        }
        return issues.stream().anyMatch(ShReportQcIssue::isBlock);
    }

    private String str(String key) {
        return value(key).map(v -> v == null ? "" : String.valueOf(v)).orElse("");
    }

    private List<Integer> intList(String key) {
        Object v = value(key).orElse(null);
        List<Integer> out = new ArrayList<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) {
                out.add(toInt(o));
            }
        }
        return out;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
