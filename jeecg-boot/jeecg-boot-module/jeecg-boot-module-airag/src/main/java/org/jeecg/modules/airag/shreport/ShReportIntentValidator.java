package org.jeecg.modules.airag.shreport;

import com.alibaba.fastjson.JSONObject;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.jeecg.modules.airag.shreport.service.ShReportDataQueryService;

import java.util.Set;

/**
 * 山洪报告助手意图 JSON 校验与规范化（纯静态逻辑，便于单测）。
 * 模型输出结构非法时抛出 {@link IllegalArgumentException}，由调用方重试或降级规则解析。
 */
public final class ShReportIntentValidator {

    public static final String ACTION_GENERATE_ALL = "GENERATE_ALL";
    public static final String ACTION_GENERATE_CHAPTER = "GENERATE_CHAPTER";
    public static final String ACTION_QUERY_STATUS = "QUERY_STATUS";
    public static final String ACTION_EXPORT = "EXPORT";
    public static final String ACTION_CHAT = "CHAT";
    public static final String ACTION_PREPARE_OUTLINE = "PREPARE_OUTLINE";
    public static final String ACTION_QUERY_DATA = "QUERY_DATA";
    public static final String ACTION_QUERY_KB = "QUERY_KB";

    private static final Set<String> ACTIONS = Set.of(
            ACTION_GENERATE_ALL,
            ACTION_GENERATE_CHAPTER,
            ACTION_QUERY_STATUS,
            ACTION_EXPORT,
            ACTION_CHAT,
            ACTION_PREPARE_OUTLINE,
            ACTION_QUERY_DATA,
            ACTION_QUERY_KB);

    private static final Set<String> DATA_QUERIES = ShReportDataQueryService.SUPPORTED;
    private static final Set<String> DATA_FILTERS = Set.of("county", "town", "ftp", "keyword");

    private ShReportIntentValidator() {
    }

    /**
     * 校验并规范化模型输出的意图 JSON。
     *
     * @param raw 模型解析后的 JSON（可为 null）
     * @return 规范化后的安全 JSON（字段齐全、类型固定）
     * @throws IllegalArgumentException 结构非法（未知 action、chapterNo 越界等）
     */
    public static JSONObject validate(JSONObject raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("意图 JSON 为空");
        }
        String action = raw.getString("action");
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("缺少 action 字段");
        }
        action = action.trim().toUpperCase();
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("未知 action：" + action);
        }

        JSONObject out = new JSONObject();
        out.put("action", action);

        // 章节号：仅 GENERATE_CHAPTER 必填且必须 1~7；其他动作忽略非法值
        Integer chapterNo = raw.getInteger("chapterNo");
        if (ACTION_GENERATE_CHAPTER.equals(action)) {
            if (chapterNo == null) {
                throw new IllegalArgumentException("GENERATE_CHAPTER 缺少 chapterNo");
            }
            if (chapterNo < 1 || chapterNo > ShReportConstants.CHAPTER_COUNT) {
                throw new IllegalArgumentException("chapterNo 超出范围 1-" + ShReportConstants.CHAPTER_COUNT + "：" + chapterNo);
            }
            out.put("chapterNo", chapterNo);
        } else if (chapterNo != null && chapterNo >= 1 && chapterNo <= ShReportConstants.CHAPTER_COUNT) {
            out.put("chapterNo", chapterNo);
        }

        out.put("wsmc", trimToNull(raw.getString("wsmc")));
        out.put("requirement", trimToNull(raw.getString("requirement")));
        out.put("reply", trimToNull(raw.getString("reply")));

        // 流域快捷确认（与规则解析对齐）
        String confirmLybm = trimToNull(raw.getString("confirmLybm"));
        if (confirmLybm != null) {
            out.put("confirmLybm", confirmLybm);
            out.put("confirmLymc", trimToNull(raw.getString("confirmLymc")));
        }

        // 数据问答：查询类别必须来自白名单；模型漏给时按总体概况兜底，给错则报错触发重试
        String dataQuery = trimToNull(raw.getString("dataQuery"));
        if (ACTION_QUERY_DATA.equals(action)) {
            if (dataQuery == null) {
                dataQuery = ShReportDataQueryService.Q_OVERVIEW;
            } else if (!DATA_QUERIES.contains(dataQuery)) {
                throw new IllegalArgumentException("未知 dataQuery：" + dataQuery);
            }
            out.put("dataQuery", dataQuery);
            String scope = trimToNull(raw.getString("scope"));
            if (scope != null) {
                scope = scope.toLowerCase();
                if (!"bound".equals(scope) && !"all".equals(scope)) {
                    throw new IllegalArgumentException("未知 scope：" + scope);
                }
                out.put("scope", scope);
            }
        } else if (dataQuery != null && DATA_QUERIES.contains(dataQuery)) {
            out.put("dataQuery", dataQuery);
        }
        // 统计过滤槽位：仅透传合法字段，非法名忽略
        for (String f : DATA_FILTERS) {
            String v = trimToNull(raw.getString(f));
            if (v != null) {
                out.put(f, v);
            }
        }
        return out;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
