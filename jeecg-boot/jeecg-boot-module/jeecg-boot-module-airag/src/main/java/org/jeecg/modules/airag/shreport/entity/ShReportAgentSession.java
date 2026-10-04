package org.jeecg.modules.airag.shreport.entity;

import lombok.Data;

import java.util.Date;

/**
 * 山洪报告助手会话行（sh_report_agent_session，一张图 PostgreSQL shreport_map 库）。
 * messages 为 JSON 数组字符串（角色+正文+时间+id），由 ShReportSessionMessageMerge 负责并集合并。
 */
@Data
public class ShReportAgentSession {

    private Long id;
    private String userId;
    private String conversationId;
    private String batchNo;
    private String lybm;
    private String lymc;
    private String modelId;
    private String modelLabel;
    private String title;
    private String messages;
    private Long updateTime;
    private Integer archived;
    private Date createTime;
}
