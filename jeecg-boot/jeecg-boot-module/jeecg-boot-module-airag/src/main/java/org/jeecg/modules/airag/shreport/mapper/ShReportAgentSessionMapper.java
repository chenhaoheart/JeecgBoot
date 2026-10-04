package org.jeecg.modules.airag.shreport.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.airag.shreport.entity.ShReportAgentSession;

import java.util.List;

/**
 * 山洪报告助手会话持久化（sh_report_agent_session，一张图 PostgreSQL shreport_map 库）。
 * 数据源在 spring.datasource.dynamic.datasource.shreport 中配置，@DS 路由；
 * SQL 统一放在同目录 xml/ShReportAgentSessionMapper.xml。
 */
@DS("shreport")
public interface ShReportAgentSessionMapper {

    /** 幂等建表（PG 方言，应用就绪时调用） */
    void createTableIfNotExists();

    /** 用户的会话列表（按更新时间倒序） */
    List<ShReportAgentSession> listByUser(@Param("userId") String userId);

    /** 行锁读取：并集合并期间阻塞同一会话的其他写入方 */
    ShReportAgentSession selectForUpdate(@Param("userId") String userId, @Param("conversationId") String conversationId);

    /**
     * 幂等 upsert：消息整列写入（由调用方先做并集合并）；
     * 未携带的元数据经 COALESCE 保留库中原值，防止部分保存清掉标题/流域/归档标记。
     */
    int upsert(ShReportAgentSession session);

    int deleteByUserAndConversationId(@Param("userId") String userId, @Param("conversationId") String conversationId);
}
