package org.jeecg.modules.airag.shreport.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.llm.entity.AiragKnowledgeDoc;
import org.jeecg.modules.airag.llm.handler.EmbeddingHandler;
import org.jeecg.modules.airag.llm.service.IAiragKnowledgeDocService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一期调查成果知识库检索：写作时作体例参考，对话时可问答已入库报告。
 * <p>
 * 知识库文档按「流域 · 第X章 标题」切片（PDF 报告为 5 章结构），写作范文优先按
 * 章节标题精确整篇取用（确定性、上下文完整），语义向量检索作为兜底；
 * 本机未装 pgvector 时再回退到文档表关键词检索。
 */
@Slf4j
@Service
public class ShReportKnowledgeService {

    public static final String DEFAULT_KNOW_ID = "2099990000000000101";
    public static final String DEFAULT_APP_ID = "2099990000000000102";
    public static final String DEFAULT_APP_NAME = "山洪调查成果问答";
    private static final Pattern WATERSHED_NAME = Pattern.compile("([\\u4e00-\\u9fa5]{2,12}流域)");
    private static final String[] EXAMPLE_QUESTIONS = {
            "大崖沟流域报告摘要写了什么？",
            "塔子沟识别出哪些风险隐患？",
            "噶沟里的监测预警建议是什么？",
            "南峪沟第一章流域概况怎么写的？"
    };

    /** 报告 7 章到知识库标题片段的映射（按序尝试命中任意一个片段即取用） */
    private static final String[][] CHAPTER_TITLE_HINTS = {
            {"第一章", "流域概况"},
            {"第二章", "前期准备"},
            {"第三章", "风险隐患调查", "外业调查"},
            {"第四章", "影响分析"},
            {"历史", "灾害"},
            {"防治区", "危险区", "分区"},
            {"第五章", "成果与建议", "建议"}
    };

    /** 向量兜底检索用的章节主题词（与 CHAPTER_TITLE_HINTS 对应，供无法标题命中时语义检索） */
    private static final String[] CHAPTER_QUERY = {
            "",
            "流域概况 自然地理 社会经济 水系",
            "前期准备 内业排查",
            "风险隐患调查 外业调查 桥涵 沟滩占地 急弯 泥石流",
            "影响分析 壅水 漫流 溃决",
            "历史山洪灾害",
            "防治区",
            "成果与建议 监测预警 工程措施"
    };

    @Autowired(required = false)
    private EmbeddingHandler embeddingHandler;

    @Autowired(required = false)
    private IAiragKnowledgeDocService knowledgeDocService;

    @Value("${shreport.knowledge.enabled:true}")
    private boolean enabled;

    @Value("${shreport.knowledge.id:" + DEFAULT_KNOW_ID + "}")
    private String knowId;

    @Value("${shreport.knowledge.top-number:4}")
    private int topNumber;

    @Value("${shreport.knowledge.similarity:0.45}")
    private double similarity;

    @Value("${shreport.knowledge.max-chars:2800}")
    private int maxChars;

    /** 写作范文：每章取整篇的其他流域文档数 */
    @Value("${shreport.knowledge.example-docs:2}")
    private int exampleDocs;

    /** 写作范文：单篇文档截断长度 */
    @Value("${shreport.knowledge.example-chars:1800}")
    private int exampleChars;

    /** 写作范文：拼进提示词的总长度上限 */
    @Value("${shreport.knowledge.writing-max-chars:4200}")
    private int writingMaxChars;

    /** 检索结果：拼给模型的上下文 + 结构化来源（docName/score），供前端引用展示 */
    public static class KbResult {
        private final String context;
        private final List<Map<String, Object>> sources;

        public KbResult(String context, List<Map<String, Object>> sources) {
            this.context = context == null ? "" : context;
            this.sources = sources == null ? List.of() : sources;
        }

        public String getContext() {
            return context;
        }

        public List<Map<String, Object>> getSources() {
            return sources;
        }

        public boolean isEmpty() {
            return context.isBlank();
        }
    }

    public boolean available() {
        return enabled && knowId != null && !knowId.isBlank()
                && (embeddingHandler != null || knowledgeDocService != null);
    }

    /** 写作提示词附录：同类报告体例范文，严禁把其他流域数字当成本流域事实。 */
    public String writingExamples(int chapterNo, String lymc) {
        return writingExamplesResult(chapterNo, lymc).getContext();
    }

    /** 同上，并携带范文来源清单（文档名），供生成链路向用户展示「本章参考了哪些一期成果」 */
    public KbResult writingExamplesResult(int chapterNo, String lymc) {
        if (!available()) {
            return new KbResult("", List.of());
        }
        List<Map<String, Object>> hits = searchChapterDocs(chapterNo, lymc);
        if (hits.isEmpty()) {
            hits = searchVector(buildChapterQuery(chapterNo, lymc));
        }
        if (hits.isEmpty()) {
            hits = searchKeyword(buildChapterQuery(chapterNo, lymc), lymc);
        }
        if (hits.isEmpty()) {
            return new KbResult("", List.of());
        }
        String hitsText = formatHits(hits, exampleChars, writingMaxChars);
        if (hitsText.isBlank()) {
            return new KbResult("", List.of());
        }
        String header = "\n【同类流域一期调查成果范文（只学习体例、章节结构与写法；范文中的流域、数字一律不得照抄，"
                + "本流域所有数字必须以实测数据包为准）】\n";
        return new KbResult(header + hitsText + "\n", sourceNames(hits));
    }

    /** 对话检索：把命中片段拼成可直接给模型的上下文，并返回来源；recentMsgs 为最近几轮用户消息，用于多轮追问 */
    public KbResult searchForAnswer(String question, String lymc, List<String> recentMsgs) {
        if (!available()) {
            return new KbResult("", List.of());
        }
        StringBuilder q = new StringBuilder();
        if (lymc != null && !lymc.isBlank()) {
            q.append(lymc).append(' ');
        }
        if (recentMsgs != null) {
            int from = Math.max(0, recentMsgs.size() - 2);
            for (int i = from; i < recentMsgs.size(); i++) {
                String m = recentMsgs.get(i);
                if (m != null && !m.isBlank()) {
                    q.append(m.trim()).append(' ');
                }
            }
        }
        if (question != null && !question.isBlank()) {
            q.append(question.trim());
        }
        String query = q.toString().trim();
        if (query.isBlank()) {
            return new KbResult("", List.of());
        }
        List<Map<String, Object>> hits = searchVector(query);
        if (hits.isEmpty()) {
            hits = searchKeyword(query, lymc);
        }
        return new KbResult(formatHits(hits, 900, maxChars), sourceNames(hits));
    }

    /** 是否绑定了山洪调查成果库（AI 应用聊天用） */
    public boolean boundToApp(String appId, String appName, String knowledgeIds) {
        if (DEFAULT_APP_ID.equals(appId) || DEFAULT_APP_NAME.equals(appName)) {
            return true;
        }
        return knowledgeIds != null && !knowledgeIds.isBlank() && knowledgeIds.contains(knowId);
    }

    /**
     * 应用聊天增强：有命中则注入片段；未命中则给出可查流域和示例问法，避免只说「知识库未收录」。
     * 未点名流域时不做关键词兜底，防止把随便一篇报告当成「小流域介绍」。
     */
    public String chatAugment(String question, List<String> recentMsgs) {
        if (!available()) {
            return buildMissHint(List.of(), true);
        }
        if (needsMissHint(question) && namedWatershed(question) == null) {
            return buildMissHint(listWatershedNames(), false);
        }
        String lymc = namedWatershed(question);
        if (lymc == null && recentMsgs != null) {
            for (int i = recentMsgs.size() - 1; i >= 0; i--) {
                lymc = namedWatershed(recentMsgs.get(i));
                if (lymc != null) {
                    break;
                }
            }
        }
        String query = buildChatQuery(question, lymc, recentMsgs);
        List<Map<String, Object>> hits = query.isBlank() ? List.of() : searchVector(query);
        if (hits.isEmpty() && lymc != null) {
            hits = searchKeyword(question == null ? lymc : question, lymc);
        }
        if (!hits.isEmpty()) {
            String ctx = formatHits(hits, 900, maxChars);
            if (!ctx.isBlank()) {
                return "【检索到的一期调查成果】\n" + ctx
                        + "\n请依据上述片段回答用户问题；片段中没有的数字不要编造。";
            }
        }
        return buildMissHint(listWatershedNames(), false);
    }

    static String buildChatQuery(String question, String lymc, List<String> recentMsgs) {
        StringBuilder q = new StringBuilder();
        if (lymc != null && !lymc.isBlank()) {
            q.append(lymc).append(' ');
        }
        if (recentMsgs != null) {
            int from = Math.max(0, recentMsgs.size() - 2);
            for (int i = from; i < recentMsgs.size(); i++) {
                String m = recentMsgs.get(i);
                if (m != null && !m.isBlank()) {
                    q.append(m.trim()).append(' ');
                }
            }
        }
        if (question != null && !question.isBlank()) {
            q.append(question.trim());
        }
        return q.toString().trim();
    }

    /** 联网/模型职责/未点名介绍等：不要硬检索，直接引导可查流域 */
    static boolean needsMissHint(String question) {
        if (question == null || question.isBlank()) {
            return true;
        }
        String q = question.trim();
        if (namedWatershed(q) != null) {
            return false;
        }
        return q.length() <= 6
                || q.matches(".*(联网|外网|网上|访问数据|模型|你是谁|干什么|做什么|怎么用|如何使用|介绍一下|小流域介绍|能查什么|查什么|你好|在吗).*");
    }

    /** 点名了具体沟/河流域，排除「小流域」这种泛称 */
    static String namedWatershed(String text) {
        String ws = extractWatershed(text);
        if (ws == null || "小流域".equals(ws) || ws.endsWith("小流域")) {
            return null;
        }
        if (ws.contains("沟") || ws.contains("河")) {
            return ws;
        }
        return null;
    }

    List<String> listWatershedNames() {
        if (knowledgeDocService == null) {
            return List.of();
        }
        try {
            LambdaQueryWrapper<AiragKnowledgeDoc> qw = new LambdaQueryWrapper<>();
            qw.eq(AiragKnowledgeDoc::getKnowledgeId, knowId);
            qw.select(AiragKnowledgeDoc::getTitle);
            qw.orderByAsc(AiragKnowledgeDoc::getTitle);
            List<AiragKnowledgeDoc> records = knowledgeDocService.list(qw);
            List<String> titles = new ArrayList<>();
            for (AiragKnowledgeDoc doc : records) {
                titles.add(doc.getTitle());
            }
            return watershedsFromTitles(titles);
        } catch (Exception e) {
            log.warn("读取已入库流域名单失败：{}", e.getMessage());
            return List.of();
        }
    }

    static List<String> watershedsFromTitles(List<String> titles) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (titles == null) {
            return List.of();
        }
        for (String title : titles) {
            String w = extractWatershed(title);
            if (w != null) {
                names.add(w);
            }
        }
        return new ArrayList<>(names);
    }

    public static String buildMissHint(List<String> watersheds, boolean unavailable) {
        String names = (watersheds == null || watersheds.isEmpty())
                ? "暂时读不到流域名单（请确认知识库文档已导入，并在「AI知识库」重建为 complete）"
                : String.join("、", watersheds);
        StringBuilder sb = new StringBuilder();
        sb.append("【检索未命中引导】本次没有匹配到报告片段。禁止只回复「知识库未收录」后结束，必须按下面引导用户继续问。\n");
        if (unavailable) {
            sb.append("调查成果知识库当前不可用。\n");
        } else {
            sb.append("我不会上网，只查已入库的甘肃省一期山洪调查成果报告。\n");
        }
        sb.append("目前可查流域：").append(names).append("。\n");
        sb.append("请直接说出流域名和想看的内容，例如：\n");
        for (String example : EXAMPLE_QUESTIONS) {
            sb.append("- ").append(example).append('\n');
        }
        sb.append("若用户在问能否联网、模型是干什么的：先用一句话说明只检索已入库报告，再列出上面的流域和示例，请对方点名提问。");
        return sb.toString();
    }

    static String buildChapterQuery(int chapterNo, String lymc) {
        String topic = (chapterNo >= 1 && chapterNo < CHAPTER_QUERY.length)
                ? CHAPTER_QUERY[chapterNo] : "调查成果";
        String ws = lymc == null ? "" : lymc.trim();
        return (ws + " " + topic + " 调查成果").trim();
    }

    static boolean looksLikeKbQuestion(String msg) {
        if (msg == null || msg.isBlank()) {
            return false;
        }
        return msg.matches(".*(一期|成果报告|知识库|已上报|调查成果报告|报告摘要|原来怎么写|范文).*");
    }

    static String extractWatershed(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = WATERSHED_NAME.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 按 title 升序分页取整篇文档。先只查 id 排序分页、再按 id 回查完整字段：
     * 文档 content 平均 12KB、最大 64KB，整行进 MySQL 排序缓冲会撑爆默认 256KB
     * sort_buffer_size（报 Out of sort memory, error 1038），只排 id/title 窄行可规避。
     */
    private List<AiragKnowledgeDoc> pageDocsSortedByTitle(Consumer<LambdaQueryWrapper<AiragKnowledgeDoc>> conditions, int limit) {
        LambdaQueryWrapper<AiragKnowledgeDoc> idQw = new LambdaQueryWrapper<>();
        idQw.select(AiragKnowledgeDoc::getId);
        conditions.accept(idQw);
        idQw.orderByAsc(AiragKnowledgeDoc::getTitle);
        List<AiragKnowledgeDoc> idRows = knowledgeDocService
                .page(new Page<>(1, Math.max(limit, 1)), idQw).getRecords();
        if (idRows.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (AiragKnowledgeDoc r : idRows) {
            ids.add(r.getId());
        }
        Map<String, AiragKnowledgeDoc> byId = new LinkedHashMap<>();
        for (AiragKnowledgeDoc full : knowledgeDocService.listByIds(ids)) {
            byId.put(full.getId(), full);
        }
        List<AiragKnowledgeDoc> out = new ArrayList<>();
        for (AiragKnowledgeDoc r : idRows) {
            AiragKnowledgeDoc full = byId.get(r.getId());
            if (full != null) {
                out.add(full);
            }
        }
        return out;
    }

    /**
     * 按章节标题精确取其他流域的整篇文档作范文。
     * 文档标题形如「大崖沟流域 · 第一章 流域概况」，含「（续N）」的是同篇超长拆分块，排后。
     */
    private List<Map<String, Object>> searchChapterDocs(int chapterNo, String lymc) {
        if (knowledgeDocService == null) {
            return List.of();
        }
        String[] hints = (chapterNo >= 1 && chapterNo <= CHAPTER_TITLE_HINTS.length)
                ? CHAPTER_TITLE_HINTS[chapterNo - 1] : new String[0];
        String shortWs = lymc == null ? "" : lymc.replace("流域", "").trim();
        for (String hint : hints) {
            try {
                List<AiragKnowledgeDoc> records = pageDocsSortedByTitle(qw -> {
                    qw.eq(AiragKnowledgeDoc::getKnowledgeId, knowId);
                    qw.like(AiragKnowledgeDoc::getTitle, hint);
                    if (!shortWs.isEmpty()) {
                        // 范文必须来自其他流域，排除本流域同名文档
                        qw.notLike(AiragKnowledgeDoc::getTitle, shortWs);
                    }
                }, Math.max(exampleDocs * 3 + 3, 9));
                List<Map<String, Object>> picked = pickChapterDocs(records);
                if (!picked.isEmpty()) {
                    return picked;
                }
            } catch (Exception e) {
                log.warn("按标题取{}范文失败：{}", hint, e.getMessage());
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> pickChapterDocs(List<AiragKnowledgeDoc> records) {
        List<AiragKnowledgeDoc> main = new ArrayList<>();
        List<AiragKnowledgeDoc> continued = new ArrayList<>();
        for (AiragKnowledgeDoc doc : records) {
            if (doc.getTitle() != null && doc.getTitle().contains("（续")) {
                continued.add(doc);
            } else {
                main.add(doc);
            }
        }
        List<AiragKnowledgeDoc> ordered = new ArrayList<>(main);
        ordered.addAll(continued);
        List<Map<String, Object>> hits = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (AiragKnowledgeDoc doc : ordered) {
            if (doc.getContent() == null || doc.getContent().isBlank()) {
                continue;
            }
            if (!seen.add(doc.getTitle())) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("docName", doc.getTitle());
            one.put("content", doc.getContent());
            hits.add(one);
            if (hits.size() >= Math.max(exampleDocs, 1)) {
                break;
            }
        }
        return hits;
    }

    private List<Map<String, Object>> searchVector(String query) {
        if (embeddingHandler == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> hits = embeddingHandler.searchEmbedding(knowId, query, topNumber, similarity);
            return hits == null ? List.of() : hits;
        } catch (Exception e) {
            log.warn("山洪调查成果向量检索不可用，回退关键词：{}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> searchKeyword(String query, String lymc) {
        if (knowledgeDocService == null) {
            return List.of();
        }
        try {
            String ws = extractWatershed(query);
            List<AiragKnowledgeDoc> records;
            if (ws != null) {
                records = pageDocsSortedByTitle(qw -> {
                    qw.eq(AiragKnowledgeDoc::getKnowledgeId, knowId);
                    qw.like(AiragKnowledgeDoc::getTitle, ws);
                }, Math.max(topNumber, 1));
            } else {
                String key = query.length() > 30 ? query.substring(0, 30) : query;
                records = pageDocsSortedByTitle(qw -> {
                    qw.eq(AiragKnowledgeDoc::getKnowledgeId, knowId);
                    qw.and(w -> w.like(AiragKnowledgeDoc::getTitle, key)
                            .or().like(AiragKnowledgeDoc::getContent, key));
                }, Math.max(topNumber, 1));
            }
            if (records.isEmpty() && ws != null) {
                records = pageDocsSortedByTitle(
                        qw -> qw.eq(AiragKnowledgeDoc::getKnowledgeId, knowId), Math.max(topNumber, 1));
            }
            List<Map<String, Object>> hits = new ArrayList<>();
            for (AiragKnowledgeDoc doc : records) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("docName", doc.getTitle());
                one.put("content", snippetAround(doc.getContent(), query, ws));
                hits.add(one);
            }
            return hits;
        } catch (Exception e) {
            log.warn("山洪调查成果关键词检索失败：{}", e.getMessage());
            return List.of();
        }
    }

    static String snippetAround(String content, String query, String watershed) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String needle = firstKeyword(query, watershed);
        int idx = needle == null ? -1 : content.indexOf(needle);
        if (idx < 0) {
            return content;
        }
        int from = Math.max(0, idx - 80);
        return content.substring(from);
    }

    static String firstKeyword(String query, String watershed) {
        if (query == null) {
            return watershed;
        }
        for (String part : query.split("\\s+")) {
            if (part.length() >= 2 && !"调查成果".equals(part)) {
                if (watershed == null || !watershed.contains(part.replace("流域", ""))) {
                    return part;
                }
            }
        }
        return watershed;
    }

    static String formatHits(List<Map<String, Object>> hits, int perHit, int maxTotal) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        List<String> blocks = new ArrayList<>();
        int used = 0;
        for (Map<String, Object> hit : hits) {
            String name = String.valueOf(hit.getOrDefault("docName", hit.getOrDefault("title", "片段")));
            String content = String.valueOf(hit.getOrDefault("content", ""));
            if (content.isBlank() || "null".equals(content)) {
                continue;
            }
            if (content.length() > perHit) {
                content = content.substring(0, perHit) + "…";
            }
            String block = "来源：" + name + "\n" + content;
            if (used + block.length() > maxTotal) {
                int remain = maxTotal - used;
                if (remain < 80) {
                    break;
                }
                block = block.substring(0, remain) + "…";
            }
            blocks.add(block);
            used += block.length();
            if (used >= maxTotal) {
                break;
            }
        }
        return String.join("\n\n---\n\n", blocks);
    }

    /** 从命中里提取来源清单：docName + 相似度分（关键词/整篇路径无分数） */
    private static List<Map<String, Object>> sourceNames(List<Map<String, Object>> hits) {
        List<Map<String, Object>> sources = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> hit : hits) {
            String name = String.valueOf(hit.getOrDefault("docName", hit.getOrDefault("title", "")));
            if (name.isBlank() || "null".equals(name) || !seen.add(name)) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("docName", name);
            Object score = hit.get("score");
            if (score instanceof Number n) {
                one.put("score", Math.round(n.doubleValue() * 1000.0) / 1000.0);
            }
            sources.add(one);
        }
        return sources;
    }
}
