package org.jeecg.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 聊天辅助配置（仅保留非模型类配置）。
 * <p>
 * AI 模型配置已统一迁移至数据库 airag_model 表（AI模型管理界面维护），
 * 各功能通过 {@code AiragDefaultModelService} 按类型读取默认模型，
 * 原 jeecg.ai-chat 下的 provider/model/apiKey/apiHost 及 ai-model-* 子配置已移除。
 *
 * @author jeecg
 */
@Data
@Component("jeecgAiChatConfig")
@ConfigurationProperties(prefix = "jeecg.ai-chat")
public class AiChatConfig {

    /**
     * skills配置文件路径
     */
    private String skillsDir;
    /**
     * shell命令行配置文件路径
     */
    private String skillsShellDir;

	/**
	 * AI 聊天助手访问限流配置
	 */
	private RateLimitConfig rateLimit = new RateLimitConfig();

	/**
	 * AI 聊天助手访问限流参数
	 *
	 * @author scott
	 * @since 2026-09-01 issues/9871 AI聊天助手访问限流配置优化
	 */
	@Data
	public static class RateLimitConfig {
		/** 每会话每分钟最多发送消息次数，为空或小于等于 0 时不限制 */
		private Integer sendPerSessionPerMinute;

		/** 每 IP 每分钟最多发送消息次数，为空或小于等于 0 时不限制 */
		private Integer sendPerIpPerMinute;

		/** 每会话每小时最多上传文件次数，为空或小于等于 0 时不限制 */
		private Integer uploadPerSessionPerHour;
	}

}
