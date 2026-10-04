package org.jeecg.modules.airag.shreport.service;

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
public class ShReportModelVariantService {

    private static final String VARIANT_SUFFIX = "#M";

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
        return rows.isEmpty() ? null : rows.get(0);
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
