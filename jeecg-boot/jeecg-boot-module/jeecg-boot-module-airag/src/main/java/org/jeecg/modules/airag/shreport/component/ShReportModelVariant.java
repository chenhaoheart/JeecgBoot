package org.jeecg.modules.airag.shreport.component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 章节流程解析：始终使用 AI 流程设计里的基础流程（山洪报告_01…_07）。
 * 会话选了模型时，只在内存里替换 LLM 节点 modelId，经 debugFlow 运行，不再入库复制 #M 变体。
 */
@Slf4j
@Component
public class ShReportModelVariant {

    private static final String VARIANT_SUFFIX = "#M";

    /** upgrade_data_packet.py 写入的旧 PACKET_LINE */
    public static final String PACKET_LINE_OLD =
            "以上数据包是本章唯一允许使用的事实数据来源，包中没有的数据写「暂无调查数据」，严禁编造或引用其他流域数字。";
    public static final String PACKET_LINE_NEW =
            "以上数据包是结构化事实的主要来源；用户上传资料为补充或修正，使用规则见补充资料（第5章历史灾害见「历史灾害记录」）。包中与资料中都没有的数据写「暂无调查数据」，严禁编造或引用其他流域数字。";

    public static String relaxPacketOnlySource(String designJson) {
        if (designJson == null || !designJson.contains(PACKET_LINE_OLD)) {
            return designJson;
        }
        return designJson.replace(PACKET_LINE_OLD, PACKET_LINE_NEW);
    }

    /** 降级节点标签，正常编排里它只出现在 CATCH(...).DO(end.tag('end-fb')) 中 */
    private static final String END_FB_TAG = "end-fb";
    /** end 节点写法：end.tag('xx') 或 end.tag("xx") */
    private static final java.util.regex.Pattern END_TAG =
            java.util.regex.Pattern.compile("end\\.tag\\(\\s*['\"]([\\w-]+)['\"]\\s*\\)");
    private static final java.util.regex.Pattern LLM_TAG =
            java.util.regex.Pattern.compile("llm\\.tag\\(\\s*['\"]([\\w-]+)['\"]\\s*\\)");

    /**
     * 坏编排判定：设计器里删连线/重排后，降级节点 end-fb 会被当成孤立节点吐成一条并行分支，
     * 编排变成 WHEN(end.tag('end-fb'), THEN(...)) 而不再有 CATCH 兜底。LiteFlow 并行默认只等 15 秒
     * （LiteflowConfig.whenMaxWaitTime 未配置时 15000ms），主链路（SQL + LLM 写正文）必然超时，
     * 于是整条 chain 被取消 → 章节生成失败。这里识别该形态，供还原与快速失败使用。
     */
    public static boolean isBrokenChapterChain(String chain) {
        return chain != null && chain.contains("WHEN(") && !chain.contains("CATCH(");
    }

    /**
     * 把 WHEN(end.tag('end-fb'), THEN(<body>).tag(...)) 还原成标准编排
     * THEN(<body 去掉末尾 end 节点>, CATCH(THEN(llm.tag('<llm>'),end.tag('<end>'))).DO(end.tag('end-fb'))).tag(...)。
     * 只认得这一种形态，认不出就返回 null，交给调用方明确报错，不做猜测式改写。
     */
    public static String repairBrokenWhenChain(String chain) {
        if (!isBrokenChapterChain(chain) || !chain.trim().startsWith("WHEN")) {
            return null;
        }
        String s = chain.trim();
        int open = s.indexOf('(');
        int close = matchBracket(s, open);
        if (open < 0 || close < 0) {
            return null;
        }
        java.util.List<String> whenItems = splitTopLevel(s.substring(open + 1, close));
        if (whenItems.size() != 2) {
            return null;
        }
        java.util.regex.Matcher fb = END_TAG.matcher(whenItems.get(0).trim());
        if (!fb.matches() || !END_FB_TAG.equals(fb.group(1))) {
            return null;
        }
        String thenExpr = whenItems.get(1).trim();
        if (!thenExpr.startsWith("THEN(")) {
            return null;
        }
        int thenOpen = thenExpr.indexOf('(');
        int thenClose = matchBracket(thenExpr, thenOpen);
        if (thenOpen < 0 || thenClose < 0) {
            return null;
        }
        // 保留 THEN 后面的 .tag("...") 原样
        String tagSuffix = thenExpr.substring(thenClose + 1).trim();
        java.util.List<String> bodyItems = splitTopLevel(thenExpr.substring(thenOpen + 1, thenClose));
        java.util.List<String> main = new java.util.ArrayList<>();
        for (String item : bodyItems) {
            main.add(item.trim());
        }
        // 末尾的 end 节点挪进 CATCH 里执行
        String endPart = "end.tag('end-node')";
        if (!main.isEmpty()) {
            java.util.regex.Matcher endM = END_TAG.matcher(main.get(main.size() - 1));
            if (endM.matches()) {
                endPart = "end.tag('" + endM.group(1) + "')";
                main.remove(main.size() - 1);
            }
        }
        if (main.isEmpty()) {
            return null;
        }
        String llmPart = "llm.tag('llm1')";
        for (String item : main) {
            java.util.regex.Matcher llmM = LLM_TAG.matcher(item);
            if (llmM.matches()) {
                llmPart = "llm.tag('" + llmM.group(1) + "')";
            }
        }
        java.util.List<String> parts = new java.util.ArrayList<>(main);
        parts.add("CATCH(THEN(" + llmPart + "," + endPart + ")).DO(end.tag('" + END_FB_TAG + "'))");
        return "THEN(\n    " + String.join(", ", parts) + "\n)" + tagSuffix;
    }

    /** 找到 s[open]='(' 对应的右括号下标，跳过引号内的字符；不成对返回 -1 */
    private static int matchBracket(String s, int open) {
        if (s == null || open < 0 || open >= s.length() || s.charAt(open) != '(') {
            return -1;
        }
        boolean inQuote = false;
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                inQuote = !inQuote;
                continue;
            }
            if (inQuote) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 按顶层逗号切分（括号/引号内的逗号不算） */
    private static java.util.List<String> splitTopLevel(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null) {
            return out;
        }
        boolean inQuote = false;
        int depth = 0;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                inQuote = !inQuote;
                buf.append(c);
                continue;
            }
            if (inQuote) {
                buf.append(c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                out.add(buf.toString().trim());
                buf.setLength(0);
                continue;
            }
            buf.append(c);
        }
        String last = buf.toString().trim();
        if (!last.isEmpty()) {
            out.add(last);
        }
        return out;
    }

    /**
     * 章节 LLM 读取超时下限（秒）。流程设计器里常见 120 秒，
     * 外业成果等章节要把整表阻水对象写成公文，火山方舟经常超过 120 秒，
     * 客户端超时会被翻译成「当前用户较多，排队中」。
     */
    public static final int MIN_LLM_TIMEOUT_SECONDS = 600;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${shreport.agent.model-id:}")
    private String defaultModelId;

    @Value("${shreport.graph.purge-flow-variants:true}")
    private boolean purgeFlowVariants;

    /**
     * 解析章节流程 ID：始终返回基础流程。modelId 仅用于兼容旧调用，不再创建变体。
     */
    public String resolveChapterFlowId(int chapterNo, String modelId) {
        Map<String, Object> row = loadEnabledChapterFlow(chapterNo);
        return row == null ? null : stringVal(row, "id");
    }

    /** 基础章节流程（enable），含 chain / design，供运行时内存覆盖模型。 */
    public Map<String, Object> loadEnabledChapterFlow(int chapterNo) {
        String baseName = ShReportConstants.flowName(chapterNo);
        if (baseName == null || baseName.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, chain, design, IFNULL(update_time, create_time) AS design_time FROM airag_flow WHERE name=? AND status='enable' "
                        + "ORDER BY create_time DESC LIMIT 1",
                baseName);
        if (rows.isEmpty()) {
            rows = jdbcTemplate.queryForList(
                    "SELECT id, name, chain, design, IFNULL(update_time, create_time) AS design_time FROM airag_flow WHERE name=? "
                            + "ORDER BY create_time DESC LIMIT 1",
                    baseName);
        }
        return rows.isEmpty() ? null : restoreChain(rows.get(0), baseName);
    }

    /**
     * 编排自愈：设计器把降级节点拖成并行分支后，并行默认只等 15 秒（LiteflowConfig.whenMaxWaitTime），
     * 主链路（SQL + LLM 写正文）必超时，整条 chain 被取消 → 章节生成失败。
     * 这里在每次读取章节流程时识别该形态并还原成标准 CATCH 兜底，同时回写 airag_flow.chain，
     * 免得每次都要手工改设计器；认不出的形态只报错、不猜测式改写。
     */
    private Map<String, Object> restoreChain(Map<String, Object> sourceRow, String flowName) {
        Map<String, Object> row = new java.util.HashMap<>(sourceRow);
        String chain = stringVal(row, "chain");
        if (!isBrokenChapterChain(chain)) {
            return row;
        }
        String fixed = repairBrokenWhenChain(chain);
        if (fixed == null) {
            log.error("章节流程「{}」编排存在无兜底的并行分支且无法自动还原，本章生成会失败，请在设计器中修正：{}",
                    flowName, chain);
            return row;
        }
        log.warn("章节流程「{}」的降级节点被拖成并行分支（WHEN 且无 CATCH），已还原为 CATCH 兜底。原编排：{}", flowName, chain);
        row.put("chain", fixed);
        String id = stringVal(row, "id");
        try {
            jdbcTemplate.update("UPDATE airag_flow SET chain=? WHERE id=?", fixed, id);
        } catch (Exception e) {
            log.warn("回写修复后的编排失败 id={}：{}", id, e.getMessage());
        }
        return row;
    }

    /** 启动时把 7 个章节流程统一体检一遍，坏编排当场还原并回写 */
    @EventListener(ApplicationReadyEvent.class)
    public void checkChapterChainsOnReady() {
        for (int no = 1; no <= ShReportConstants.CHAPTER_COUNT; no++) {
            try {
                loadEnabledChapterFlow(no);
            } catch (Exception e) {
                log.warn("启动检查第{}章流程编排失败：{}", no, e.getMessage());
            }
        }
    }

    public String resolveRuntimeModelId(String modelId) {
        if (modelId != null && !modelId.isBlank()) {
            return modelId.trim();
        }
        if (defaultModelId != null && !defaultModelId.isBlank()) {
            return defaultModelId.trim();
        }
        return null;
    }

    /** 已激活的聊天模型列表（不含密钥、不含向量/图片模型），供页面模型选择下拉 */
    public List<Map<String, Object>> listActiveModels() {
        return jdbcTemplate.queryForList(
                "SELECT id, name, provider, model_name AS modelName, model_type AS modelType " +
                        "FROM airag_model WHERE activate_flag=1 AND model_type='LLM' ORDER BY provider, name");
    }

    /** 校验模型存在且已激活 */
    public boolean isModelActive(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT COUNT(1) FROM airag_model WHERE id=? AND activate_flag=1", Integer.class, modelId.trim());
        return !rows.isEmpty() && rows.get(0) > 0;
    }

    public static String variantName(String baseFlowName, String modelId) {
        String suffix = modelId == null ? "" : modelId.replaceAll("[^A-Za-z0-9]", "");
        if (suffix.length() > 8) {
            suffix = suffix.substring(suffix.length() - 8);
        }
        return baseFlowName + VARIANT_SUFFIX + suffix;
    }

    public static boolean isModelVariantName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        int i = name.indexOf(VARIANT_SUFFIX);
        return i > 0 && name.startsWith("山洪报告_") && i + VARIANT_SUFFIX.length() < name.length();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void purgeVariantsOnReady() {
        try {
            int patched = jdbcTemplate.update(
                    "UPDATE airag_flow SET design=REPLACE(design, ?, ?) WHERE design LIKE ?",
                    PACKET_LINE_OLD, PACKET_LINE_NEW, "%" + PACKET_LINE_OLD + "%");
            if (patched > 0) {
                log.info("已将 {} 条章节流程的实测数据包「唯一来源」措辞改为双源", patched);
            }
        } catch (Exception e) {
            log.warn("补丁流程 PACKET_LINE 失败：{}", e.getMessage());
        }
        if (!purgeFlowVariants) {
            return;
        }
        try {
            int n = purgeModelVariants();
            if (n > 0) {
                log.warn("已删除 {} 条山洪报告模型变体流程（#M），生成改走基础流程内存覆盖模型", n);
            }
        } catch (Exception e) {
            log.warn("清理山洪报告模型变体流程失败：{}", e.getMessage());
        }
    }

    /** 删除按模型复制的章节流程，保留 01–07 基础流程与总调度。 */
    public int purgeModelVariants() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, name, descr FROM airag_flow WHERE name LIKE '山洪报告_%#M%' "
                        + "OR descr LIKE '%由山洪报告助手按需创建%'");
        int n = 0;
        for (Map<String, Object> row : rows) {
            String name = stringVal(row, "name");
            String descr = stringVal(row, "descr");
            boolean variant = isModelVariantName(name)
                    || (descr != null && descr.contains("由山洪报告助手按需创建"));
            if (!variant) {
                continue;
            }
            String id = stringVal(row, "id");
            if (id == null) {
                continue;
            }
            n += jdbcTemplate.update("DELETE FROM airag_flow WHERE id=?", id);
        }
        return n;
    }

    public static String replaceLlmModelId(String designJson, String modelId) {
        return replaceLlmModelId(designJson, modelId, null);
    }

    /**
     * 替换流程 design 中全部 LLM 节点的模型 ID。兼容两种存放位置：
     * options.model.modelId（现行设计器）与 options.modelId。
     * modelName 非空时同步写入 options.model.params.model，避免残留 deepseek-chat 等旧名。
     *
     * @return 替换后的 design JSON；没有 llm 节点时返回 null
     */
    public static String replaceLlmModelId(String designJson, String modelId, String modelName) {
        try {
            JSONObject design = JSON.parseObject(designJson);
            JSONArray nodes = design.getJSONArray("nodes");
            if (nodes == null) {
                return null;
            }
            boolean hit = false;
            for (int i = 0; i < nodes.size(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                if (node == null || !"llm".equals(node.getString("type"))) {
                    continue;
                }
                JSONObject props = node.getJSONObject("properties");
                if (props == null) {
                    continue;
                }
                JSONObject options = props.getJSONObject("options");
                if (options == null) {
                    options = new JSONObject();
                    props.put("options", options);
                }
                JSONObject model = options.getJSONObject("model");
                if (model == null) {
                    model = new JSONObject();
                    options.put("model", model);
                }
                model.put("modelId", modelId);
                model.put("modeId", modelId);
                options.put("modelId", modelId);
                options.put("modeId", modelId);
                JSONObject params = model.getJSONObject("params");
                if (params == null) {
                    params = new JSONObject();
                    model.put("params", params);
                }
                if (modelName != null && !modelName.isBlank()) {
                    params.put("model", modelName);
                }
                ensureMinLlmTimeout(params);
                hit = true;
            }
            return hit ? design.toJSONString() : null;
        } catch (Exception e) {
            log.warn("解析流程 design 替换模型失败：{}", e.getMessage());
            return null;
        }
    }

    /** 流程节点 timeout 低于下限时抬高，已配置更长超时则保持原值。 */
    static void ensureMinLlmTimeout(JSONObject params) {
        if (params == null) {
            return;
        }
        Integer timeout = params.getInteger("timeout");
        if (timeout == null || timeout < MIN_LLM_TIMEOUT_SECONDS) {
            params.put("timeout", MIN_LLM_TIMEOUT_SECONDS);
        }
    }

    private static String stringVal(Map<String, Object> row, String key) {
        if (row == null || key == null) {
            return null;
        }
        Object v = row.get(key);
        if (v == null) {
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && key.equalsIgnoreCase(e.getKey())) {
                    v = e.getValue();
                    break;
                }
            }
        }
        return v == null ? null : String.valueOf(v);
    }
}
