package org.jeecg.modules.airag.shreport.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.common.handler.AIChatParams;
import org.jeecg.modules.airag.llm.handler.AIChatHandler;
import org.jeecg.modules.airag.shreport.ShReportUsageTracker;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 两阶段生成之「大纲」：内置 7 章标准提纲（小节 + 应有表格），
 * 可选用大模型按流域名称与用户要求润色；模型不可用或输出非法时一律回退内置模板，保证功能可用。
 */
@Slf4j
@Service
public class ShReportOutlineService {

    private static final String OUTLINE_PROMPT =
            "你是山洪灾害风险隐患调查报告的编制专家。请根据流域名称与用户要求，对给定的标准章节提纲做微调（不要增减小章），" +
            "严格只输出 JSON 数组，每个元素形如：" +
            "{\"chapterNo\":1,\"chapterName\":\"一、流域概况\",\"sections\":[\"小节1\",\"小节2\"],\"tables\":[\"表1-1 名称\"]}";

    @Autowired(required = false)
    private AIChatHandler aiChatHandler;

    @Autowired(required = false)
    private ShReportUsageTracker usageTracker;

    @Value("${shreport.agent.model-id:}")
    private String intentModelId;

    /**
     * 构建大纲。
     *
     * @param lymc        流域名称
     * @param requirement 用户补充要求（可为空）
     * @return 7 个章节的提纲数组
     */
    public JSONArray buildOutline(String lymc, String requirement) {
        return buildOutline(null, lymc, requirement);
    }

    /** conversationId 非空时把润色调用的 token 用量计入会话统计 */
    public JSONArray buildOutline(String conversationId, String lymc, String requirement) {
        JSONArray fallback = defaultOutline();
        if (aiChatHandler == null) {
            return fallback;
        }
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(OUTLINE_PROMPT));
            messages.add(UserMessage.from("流域名称：" + (lymc == null ? "" : lymc)
                    + "\n用户要求：" + (requirement == null ? "" : requirement)
                    + "\n标准提纲：" + fallback.toJSONString()));
            AIChatParams params = new AIChatParams();
            params.setTemperature(0.0);
            JSONObject resp = aiChatHandler.completionsWithUsage(
                    (intentModelId == null || intentModelId.isEmpty()) ? null : intentModelId, messages, params);
            recordUsage(conversationId, resp);
            JSONArray refined = JSON.parseArray(extractJsonArray(resp == null ? null : resp.getString("text")));
            JSONArray normalized = normalize(refined);
            return normalized != null ? normalized : fallback;
        } catch (Exception e) {
            log.warn("大纲模型润色失败，使用内置模板：{}", e.getMessage());
            return fallback;
        }
    }

    private void recordUsage(String conversationId, JSONObject resp) {
        if (conversationId == null || conversationId.isEmpty() || resp == null || usageTracker == null) {
            return;
        }
        try {
            JSONObject u = new JSONObject();
            u.put("scene", "outline");
            u.put("inputTokens", resp.getLongValue("inputTokens"));
            u.put("outputTokens", resp.getLongValue("outputTokens"));
            u.put("estimated", resp.getBooleanValue("estimated"));
            if (intentModelId != null && !intentModelId.isEmpty()) {
                u.put("model", intentModelId);
            }
            usageTracker.record(conversationId, u);
        } catch (Exception e) {
            log.debug("大纲用量统计失败：{}", e.getMessage());
        }
    }

    /** 校验模型输出：必须是 7 章、章节号/名称与标准一致，sections 非空 */
    private JSONArray normalize(JSONArray refined) {
        if (refined == null || refined.size() != ShReportConstants.CHAPTER_COUNT) {
            return null;
        }
        JSONArray out = new JSONArray();
        for (int i = 1; i <= ShReportConstants.CHAPTER_COUNT; i++) {
            JSONObject src = refined.getJSONObject(i - 1);
            if (src == null || src.getIntValue("chapterNo") != i) {
                return null;
            }
            JSONArray sections = src.getJSONArray("sections");
            if (sections == null || sections.isEmpty()) {
                return null;
            }
            JSONObject o = new JSONObject();
            o.put("chapterNo", i);
            o.put("chapterName", ShReportConstants.chapterName(i));
            List<String> secs = new ArrayList<>();
            for (int j = 0; j < sections.size(); j++) {
                String s = sections.getString(j);
                if (s != null && !s.isBlank()) {
                    secs.add(s.trim());
                }
            }
            if (secs.isEmpty()) {
                return null;
            }
            o.put("sections", secs);
            JSONArray tables = src.getJSONArray("tables");
            o.put("tables", tables == null ? List.of() : tables.toJavaList(String.class));
            o.put("note", "");
            out.add(o);
        }
        return out;
    }

    /** 内置标准大纲（与《山洪灾害风险隐患调查报告》7 章结构对齐） */
    public static JSONArray defaultOutline() {
        JSONArray arr = new JSONArray();
        arr.add(chapter(1, new String[]{"1.1 流域自然概况", "1.2 水文气象特征", "1.3 社会经济情况", "1.4 防治对象分布"},
                new String[]{"表1-1 小流域基础信息表", "表1-2 防治对象类型统计表", "表1-3 防治对象行政区分布表"}));
        arr.add(chapter(2, new String[]{"2.1 组织准备", "2.2 技术准备", "2.3 资料收集", "2.4 人员与设备安排"},
                new String[]{"表2-1 前期工作安排表"}));
        arr.add(chapter(3, new String[]{"3.1 沟道现状调查", "3.2 防治对象现场调查", "3.3 险工险段与沟滩占地", "3.4 调查成果汇总"},
                new String[]{"表3-1 沟道调查成果表", "表3-2 防治对象调查成果表", "表3-3 沟滩占地统计表"}));
        arr.add(chapter(4, new String[]{"4.1 洪水计算参数与方法", "4.2 壅水/溃决影响分析", "4.3 影响范围与程度", "4.4 影响对象汇总"},
                new String[]{"表4-1 洪水影响分析计算表"}));
        arr.add(chapter(5, new String[]{"5.1 历史山洪灾害事件", "5.2 灾害损失情况", "5.3 灾害成因与痕迹调查"},
                new String[]{}));
        arr.add(chapter(6, new String[]{"6.1 防治分区原则与方法", "6.2 危险区/警戒区划分", "6.3 分区成果"},
                new String[]{"表6-1 防治分区统计表", "表6-2 分区防治对象一览表"}));
        arr.add(chapter(7, new String[]{"7.1 主要调查结论", "7.2 存在的主要问题", "7.3 防治对策与建议", "7.4 下一步工作计划"},
                new String[]{}));
        return arr;
    }

    private static JSONObject chapter(int no, String[] sections, String[] tables) {
        JSONObject o = new JSONObject();
        o.put("chapterNo", no);
        o.put("chapterName", ShReportConstants.chapterName(no));
        o.put("sections", sections);
        o.put("tables", tables);
        o.put("note", "");
        return o;
    }

    /** 从大纲 JSON 中提取指定章节的写作提纲文本（注入流程 tigang 入参）；无匹配返回 null */
    public static String toChapterPrompt(String outlineJson, int chapterNo) {
        if (outlineJson == null || outlineJson.isBlank()) {
            return null;
        }
        try {
            JSONArray arr = JSON.parseArray(outlineJson);
            if (arr == null) {
                return null;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject ch = arr.getJSONObject(i);
                if (ch == null || ch.getIntValue("chapterNo") != chapterNo) {
                    continue;
                }
                StringBuilder sb = new StringBuilder("《").append(ShReportConstants.chapterName(chapterNo)).append("》写作提纲：\n");
                JSONArray sections = ch.getJSONArray("sections");
                if (sections != null) {
                    for (int j = 0; j < sections.size(); j++) {
                        sb.append("- ").append(sections.getString(j)).append('\n');
                    }
                }
                JSONArray tables = ch.getJSONArray("tables");
                if (tables != null && !tables.isEmpty()) {
                    sb.append("应有表格：").append(String.join("、", tables.toJavaList(String.class))).append('\n');
                }
                String note = ch.getString("note");
                if (note != null && !note.isBlank()) {
                    sb.append("本章额外要求：").append(note.trim());
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** 汇总大纲中各章的额外要求，作为生成时的补充要求 */
    public static String collectNotes(JSONArray outline) {
        if (outline == null || outline.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < outline.size(); i++) {
            JSONObject ch = outline.getJSONObject(i);
            if (ch == null) {
                continue;
            }
            String note = ch.getString("note");
            if (note != null && !note.isBlank()) {
                sb.append("《").append(ShReportConstants.chapterName(ch.getIntValue("chapterNo"))).append("》：")
                        .append(note.trim()).append('\n');
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String extractJsonArray(String resp) {
        if (resp == null) {
            return "[]";
        }
        int s = resp.indexOf('[');
        int e = resp.lastIndexOf(']');
        if (s >= 0 && e > s) {
            return resp.substring(s, e + 1);
        }
        return "[]";
    }
}
