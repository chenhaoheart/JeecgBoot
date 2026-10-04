package org.jeecg.modules.airag.llm.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.airag.llm.entity.AiragModel;

/**
 * @Description: AiRag模型配置
 * @Author: jeecg-boot
 * @Date:   2025-02-14
 * @Version: V1.0
 */
public interface AiragModelMapper extends BaseMapper<AiragModel> {

    /**
     * 根据ID查询模型信息(忽略租户)
     * for [QQYUN-12113]分享之后的聊天，应用、模型、知识库不根据租户查询
     * @param id
     * @return
     * @author chenrui
     * @date 2025/4/21 15:24
     */
    @InterceptorIgnore(tenantLine = "true")
    AiragModel getByIdIgnoreTenant(String id);

    /**
     * 查询前端「AI模型」页设置的默认对话模型（已激活、iz_default=1 的 LLM，同类型互斥）。
     * 模型为全局资源，忽略租户；未设置返回 null，调用方回退 jeecg.ai-chat 平台底层配置。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM airag_model WHERE model_type = 'LLM' AND activate_flag = 1 AND iz_default = 1 "
            + "ORDER BY update_time DESC LIMIT 1")
    AiragModel getDefaultChatModel();
}
