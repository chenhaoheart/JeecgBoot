package org.jeecg.modules.airag.shreport.component;

import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.jeecg.modules.airag.shreport.entity.ShReportQcIssue;
import org.jeecg.modules.airag.shreport.util.ShReportTextChecker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节内容规则质检（不依赖大模型，纯规则可单测）：
 * 阻断级 BLOCK：占位符残留 / 篇幅过短 / Markdown 表格崩坏；
 * 提示级 HINT：数值异常 / 未提及目标流域 / 章节缺少应有表格。
 */
@Component
public class ShReportQualityChecker {

    /** 占位符/未完成标记（命中即 BLOCK） */
    private static final Pattern[] PLACEHOLDER_PATTERNS = {
            Pattern.compile("TBD|TODO|tbd|todo"),
            // 「待补充/待填写/待完善」只有明确占位形态才阻断：括号包裹、XX 前缀。
            // 正式行文里的「资料需待补充合规调查后完成」（尤其第五章历史灾害）不判占位，否则 rewrite 永远过不去。
            Pattern.compile("[（(【\\[]\\s*待(?:补充|填写|完善)\\s*[）)】\\]]"
                    + "|X{2,}\\s*待(?:补充|填写|完善)"),
            Pattern.compile("此处省略|【此处|此处插入|xxx|XXX|×××"),
            Pattern.compile("\\{\\{[^}]*}}"),
            Pattern.compile("[A-Za-z]{0,3}X{2,}流域"),
    };

    /**
     * 表格里的空占位行（如「| 待补充 | — | — |」）：只降级为提示，不阻断。
     * 数据包里本就没有该章数据时（如第五章历史灾害），模型如实写「资料缺失 + 待补充空表」是正确行为，
     * 判 BLOCK 会让 rewrite 反复重写同一份内容直到耗尽重试，整章直接失败。
     */
    private static final Pattern FILLABLE_PLACEHOLDER_PATTERN =
            Pattern.compile("(^|\\|)\\s*待(?:补充|填写|完善)\\s*($|\\|)", Pattern.MULTILINE);

    /** 百分数超 100 */
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");

    /** 关键计量项出现负值（人口/面积/长度/河长/高程/比降） */
    private static final Pattern NEGATIVE_NUMBER_PATTERN = Pattern.compile("(人口|面积|长度|河长|沟口高程|比降|高差)[^\\n]{0,12}?-\\s*\\d+(?:\\.\\d+)?");

    /** 1/3/4/6 章至少应包含一个 Markdown 表格 */
    private static final Set<Integer> TABLE_REQUIRED_CHAPTERS = Set.of(1, 3, 4, 6);

    /** 各章应出现的标志性关键词（缺失给 HINT）：先粗后细对齐一期报告体例。可用 shreport.qc.chapter-keywords 覆盖 */
    private static final String[][] CHAPTER_KEYWORDS = {
            {}, // 占位，章节号从 1 开始
            {"流域", "面积"},                          // 一、流域概况
            {},                                        // 二、前期准备
            {"防治对象"},                              // 三、外业调查成果
            {"壅水|漫流|溃决"},                        // 四、影响分析（任一即可）
            {"历史|灾害"},                             // 五、历史山洪灾害情况
            {"危险区|警戒区|分区"},                    // 六、防治区分析（任一即可）
            {"建议|对策"}                              // 七、成果与建议
    };

    /** 提示词标记混入正文（模型把提示词当内容输出了） */
    private static final Pattern PROMPT_LEAK_PATTERN =
            Pattern.compile("实测数据包|同类流域.{0,6}调查成果|只学习体例");

    /** 章节最小字数（可配置 shreport.qc.min-length） */
    @Value("${shreport.qc.min-length:200}")
    private int minLength;

    /** 需要 Markdown 数据表格的章节，逗号分隔（可配置 shreport.qc.table-chapters） */
    @Value("${shreport.qc.table-chapters:1,3,4,6}")
    private String tableChaptersConfig;

    /** 各章体例关键词，JSON：章节号 → 关键词组数组，组内 | 分隔备选词，命中任一即可（缺省用内置默认） */
    @Value("${shreport.qc.chapter-keywords:}")
    private String chapterKeywordsConfig;

    private static final Pattern HISTORY_EVENT_YEAR = Pattern.compile("(?:19|20)\\d{2}\\s*年");
    private static final Pattern HISTORY_EVENT_LOSS = Pattern.compile("(?:死亡|倒塌|受灾)\\s*\\d+\\s*人");

    public List<ShReportQcIssue> check(String content, int chapterNo, String lymc) {
        return check(content, chapterNo, lymc, false);
    }

    public List<ShReportQcIssue> check(String content, int chapterNo, String lymc, boolean hasHistoryMaterial) {
        List<ShReportQcIssue> issues = new ArrayList<>();
        String text = content == null ? "" : content;
        String trimmed = text.trim();

        boolean placeholder = ShReportTextChecker.isPlaceholder(trimmed);
        if (placeholder) {
            issues.add(ShReportQcIssue.block(ShReportQcIssue.TYPE_PLACEHOLDER, "存在系统占位/失败文案，未形成正式内容"));
        }
        for (Pattern p : PLACEHOLDER_PATTERNS) {
            if (p.matcher(trimmed).find()) {
                issues.add(ShReportQcIssue.block(ShReportQcIssue.TYPE_PLACEHOLDER,
                        "存在占位符或未完成标记（如 XX/TBD/（待补充）/{{}}），请补全后再审核"));
                break;
            }
        }
        if (FILLABLE_PLACEHOLDER_PATTERN.matcher(trimmed).find()) {
            issues.add(ShReportQcIssue.hint(ShReportQcIssue.TYPE_PLACEHOLDER,
                    "表格中存在「待补充」空行：该项在本流域客观不存在或本次调查未发现时，"
                            + "应写成「本次调查未发现」的调查结论并删除空表，而不是留占位行"));
        }
        if (trimmed.length() < minLength) {
            issues.add(ShReportQcIssue.block(ShReportQcIssue.TYPE_SHORT,
                    "篇幅过短（" + trimmed.length() + " 字，要求不少于 " + minLength + " 字），内容可能不完整"));
        }

        String tableIssue = findTableIssue(trimmed);
        if (tableIssue != null) {
            issues.add(ShReportQcIssue.block(ShReportQcIssue.TYPE_TABLE, tableIssue));
        }

        if (tableRequiredChapters().contains(chapterNo) && !hasMarkdownTable(trimmed)) {
            issues.add(ShReportQcIssue.hint(ShReportQcIssue.TYPE_REQUIRED_TABLE,
                    "《" + ShReportConstants.chapterName(chapterNo) + "》通常应包含至少一个数据表格"));
        }

        Matcher pm = PERCENT_PATTERN.matcher(trimmed);
        while (pm.find()) {
            double v = Double.parseDouble(pm.group(1));
            if (v > 100) {
                issues.add(ShReportQcIssue.hint(ShReportQcIssue.TYPE_NUMBER,
                        "出现超过 100% 的百分数：" + pm.group(0) + "，请核对"));
                break;
            }
        }
        if (NEGATIVE_NUMBER_PATTERN.matcher(trimmed).find()) {
            issues.add(ShReportQcIssue.hint(ShReportQcIssue.TYPE_NUMBER, "人口/面积/长度等计量项出现负值，请核对"));
        }

        if (lymc != null && !lymc.isEmpty() && trimmed.length() >= minLength) {
            String shortName = lymc.replace("流域", "");
            if (!trimmed.contains(lymc) && (shortName.isEmpty() || !trimmed.contains(shortName))) {
                issues.add(ShReportQcIssue.hint(ShReportQcIssue.TYPE_WATERSHED, "正文未提及目标流域「" + lymc + "」，存在串流域风险"));
            }
        }

        if (PROMPT_LEAK_PATTERN.matcher(trimmed).find()) {
            issues.add(ShReportQcIssue.block(ShReportQcIssue.TYPE_PLACEHOLDER, "正文混入了提示词标记（如“实测数据包/范文”），说明模型把提示词当内容输出，请重新生成"));
        }

        String missingKeyword = missingChapterKeywords(trimmed, chapterNo, chapterKeywords());
        if (missingKeyword != null) {
            issues.add(ShReportQcIssue.hint(ShReportQcIssue.TYPE_STYLE, missingKeyword));
        }
        if (chapterNo == 5 && !hasHistoryMaterial
                && (HISTORY_EVENT_YEAR.matcher(trimmed).find() || HISTORY_EVENT_LOSS.matcher(trimmed).find())
                && !trimmed.contains("暂无调查数据")) {
            issues.add(ShReportQcIssue.hint(ShReportQcIssue.TYPE_MATERIAL,
                    "疑似虚构历史灾害：正文出现具体年份或受灾人数，但本章没有用户上传的历史灾害资料"));
        }
        return issues;
    }

    /** 需要数据表格的章节集合：读配置，解析失败回退内置默认 */
    private Set<Integer> tableRequiredChapters() {
        if (tableChaptersConfig == null || tableChaptersConfig.isBlank()) {
            return TABLE_REQUIRED_CHAPTERS;
        }
        try {
            Set<Integer> out = new java.util.HashSet<>();
            for (String p : tableChaptersConfig.split(",")) {
                out.add(Integer.parseInt(p.trim()));
            }
            return out;
        } catch (Exception e) {
            return TABLE_REQUIRED_CHAPTERS;
        }
    }

    /** 各章体例关键词：读 JSON 配置，未配置/解析失败回退内置默认 */
    private String[][] chapterKeywords() {
        if (chapterKeywordsConfig == null || chapterKeywordsConfig.isBlank()) {
            return CHAPTER_KEYWORDS;
        }
        try {
            com.alibaba.fastjson.JSONObject obj =
                    com.alibaba.fastjson.JSON.parseObject(chapterKeywordsConfig);
            String[][] arr = new String[CHAPTER_KEYWORDS.length][];
            for (int i = 1; i < arr.length; i++) {
                com.alibaba.fastjson.JSONArray groups = obj.getJSONArray(String.valueOf(i));
                if (groups == null) {
                    arr[i] = new String[0];
                    continue;
                }
                String[] gs = new String[groups.size()];
                for (int j = 0; j < groups.size(); j++) {
                    gs[j] = String.valueOf(groups.get(j));
                }
                arr[i] = gs;
            }
            return arr;
        } catch (Exception e) {
            return CHAPTER_KEYWORDS;
        }
    }

    /** 章节标志性关键词缺失检查（内置默认体例），返回提示文案；全覆盖返回 null */
    static String missingChapterKeywords(String text, int chapterNo) {
        return missingChapterKeywords(text, chapterNo, CHAPTER_KEYWORDS);
    }

    /** 章节标志性关键词缺失检查，返回提示文案；全覆盖返回 null */
    static String missingChapterKeywords(String text, int chapterNo, String[][] keywords) {
        if (chapterNo < 1 || keywords == null || chapterNo >= keywords.length) {
            return null;
        }
        for (String group : keywords[chapterNo]) {
            boolean hit = false;
            for (String kw : group.split("\\|")) {
                if (text.contains(kw)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return "《" + ShReportConstants.chapterName(chapterNo) + "》未出现体例关键词「"
                        + group.replace("|", "/") + "」，与一期成果报告体例不符，请核对";
            }
        }
        return null;
    }

    public static boolean hasBlocking(List<ShReportQcIssue> issues) {
        if (issues == null) {
            return false;
        }
        return issues.stream().anyMatch(ShReportQcIssue::isBlock);
    }

    public static String toNote(List<ShReportQcIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return null;
        }
        List<String> msgs = new ArrayList<>();
        for (ShReportQcIssue i : issues) {
            msgs.add(i.getMessage());
        }
        return "质检提示：" + String.join("；", msgs);
    }

    /** 是否包含至少一个结构合法的 Markdown 表格 */
    public static boolean hasMarkdownTable(String text) {
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("|") && t.endsWith("|") && t.contains("---")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 Markdown 表格结构：连续 | 开头的行视为一个表格块，要求：
     * 1) 第 2 行是分隔行；2) 块内每行列数一致。
     */
    public static String findTableIssue(String text) {
        String[] lines = text.split("\n");
        List<String> block = new ArrayList<>();
        for (int i = 0; i <= lines.length; i++) {
            String t = i < lines.length ? lines[i].trim() : "";
            boolean tableLine = t.startsWith("|") && t.endsWith("|");
            if (tableLine) {
                block.add(t);
            } else if (!block.isEmpty()) {
                String issue = checkBlock(block);
                if (issue != null) {
                    return issue;
                }
                block.clear();
            }
        }
        return null;
    }

    private static String checkBlock(List<String> block) {
        if (block.size() == 1) {
            return "存在仅一行的 Markdown 表格，缺少分隔行与数据行";
        }
        String separator = block.get(1);
        if (!separator.contains("---")) {
            return "Markdown 表格缺少分隔行（第二行应为 |---|---| 形式）";
        }
        int expected = columnCount(block.get(0));
        for (int i = 2; i < block.size(); i++) {
            if (columnCount(block.get(i)) != expected) {
                return "Markdown 表格第 " + (i + 1) + " 行列数与表头不一致（表头 " + expected + " 列）";
            }
        }
        return null;
    }

    /** 粗略统计管道符表格列数：按未转义的 | 切分 */
    private static int columnCount(String row) {
        String t = row.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.split("\\|", -1).length;
    }
}
