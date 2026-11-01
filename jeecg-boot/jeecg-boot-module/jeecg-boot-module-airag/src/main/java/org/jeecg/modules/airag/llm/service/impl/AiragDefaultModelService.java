package org.jeecg.modules.airag.llm.service.impl;

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 默认模型解析器：从 airag_model 表按模型类型取默认模型。
 * <p>
 * 取值优先级：iz_default=1 且已激活 > 已激活的第一条；均无时返回 null（或按需抛业务提示）。
 * 使用原生 SQL 查询，默认模型为系统级配置，不受租户隔离影响。
 *
 * @author jeecg
 * @since 2026-11-02 AI模型配置统一迁移至数据库（原 jeecg.ai-chat yml 配置移除）
 */
@Slf4j
@Service
public class AiragDefaultModelService {

    private static final String MODEL_COLUMNS =
            "id, name, provider, model_name, credential, base_url, model_params, activate_flag, iz_default";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 取指定类型的默认模型（iz_default=1 优先，回退激活的第一条），无激活记录返回 null
     *
     * @param modelType 模型类型：LLM/EMBED/IMAGE/VOICE/VIDEO
     */
    public AiragModel getDefaultByType(String modelType) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + MODEL_COLUMNS + " FROM airag_model WHERE model_type=? AND activate_flag=1 AND iz_default=1 ORDER BY create_time ASC LIMIT 1",
                modelType);
        if (rows.isEmpty()) {
            rows = jdbcTemplate.queryForList(
                    "SELECT " + MODEL_COLUMNS + " FROM airag_model WHERE model_type=? AND activate_flag=1 ORDER BY create_time ASC LIMIT 1",
                    modelType);
        }
        if (rows.isEmpty()) {
            log.warn("airag_model 中不存在已激活的 [{}] 类型模型", modelType);
            return null;
        }
        return toModel(rows.get(0));
    }

    /**
     * 取指定类型的默认模型，取不到时抛业务提示
     *
     * @param modelType 模型类型
     * @param tipName   提示用名称，如：图像/语音/视频
     */
    public AiragModel getRequiredDefaultByType(String modelType, String tipName) {
        AiragModel model = getDefaultByType(modelType);
        if (model == null) {
            throw new JeecgBootBizTipException("当前系统未配置默认" + tipName + "模型，请前往 AI模型管理 中添加"
                    + modelType + "类型模型并设为默认");
        }
        return model;
    }

    private AiragModel toModel(Map<String, Object> row) {
        AiragModel model = new AiragModel();
        model.setId((String) row.get("id"));
        model.setName((String) row.get("name"));
        model.setProvider((String) row.get("provider"));
        model.setModelName((String) row.get("model_name"));
        model.setCredential((String) row.get("credential"));
        model.setBaseUrl((String) row.get("base_url"));
        model.setModelParams((String) row.get("model_params"));
        Object activateFlag = row.get("activate_flag");
        model.setActivateFlag(activateFlag == null ? null : ((Number) activateFlag).intValue());
        Object izDefault = row.get("iz_default");
        model.setIzDefault(izDefault == null ? null : ((Number) izDefault).intValue());
        return model;
    }

    /** 从模型 credential JSON 中提取 apiKey */
    public static String extractApiKey(AiragModel model) {
        if (model == null || model.getCredential() == null || model.getCredential().isBlank()) {
            return null;
        }
        try {
            return JSONObject.parseObject(model.getCredential()).getString("apiKey");
        } catch (Exception e) {
            log.warn("解析模型[{}]credential失败: {}", model.getId(), e.getMessage());
            return null;
        }
    }

    /** 从模型 model_params JSON 中提取参数对象，无配置返回空对象 */
    public static JSONObject extractParams(AiragModel model) {
        if (model == null || model.getModelParams() == null || model.getModelParams().isBlank()) {
            return new JSONObject();
        }
        try {
            return JSONObject.parseObject(model.getModelParams());
        } catch (Exception e) {
            log.warn("解析模型[{}]model_params失败: {}", model.getId(), e.getMessage());
            return new JSONObject();
        }
    }
}
