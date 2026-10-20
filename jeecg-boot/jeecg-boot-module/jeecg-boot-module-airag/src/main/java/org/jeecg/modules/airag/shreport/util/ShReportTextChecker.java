package org.jeecg.modules.airag.shreport.util;

/**
 * 章节正文形态判断：占位/未生成内容的统一口径。
 * 生成服务、图节点、质检器共用，避免各处复制判断逻辑导致口径漂移。
 */
public final class ShReportTextChecker {

    private ShReportTextChecker() {
    }

    /** 空、系统占位或「正在生成」标记：视为尚未形成正式内容 */
    public static boolean isPlaceholder(String content) {
        return content == null || content.isBlank() || content.contains("【系统默认内容】") || "正在生成…".equals(content.trim());
    }
}
