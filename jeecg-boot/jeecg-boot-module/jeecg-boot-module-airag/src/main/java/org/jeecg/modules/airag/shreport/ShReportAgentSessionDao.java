package org.jeecg.modules.airag.shreport;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.dynamic.datasource.annotation.DS;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.entity.ShReportAgentSession;
import org.jeecg.modules.airag.shreport.mapper.ShReportAgentSessionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 助手会话列表的跨浏览器持久化：会话元数据 + 消息记录按用户入库。
 * MyBatis 实现，SQL 见 mapper/xml/ShReportAgentSessionMapper.xml，
 * 数据源路由到一张图 PostgreSQL（shreport_map 库），与调查数据同库便于统一运维。
 * <p>
 * 本类必须保留 @DS：save() 带 @Transactional，事务在进入方法时就从路由数据源取连接，
 * 只在 mapper 接口上标注 @DS 的话切换发生在事务开始之后，会被主库（MySQL）连接抢先绑定，
 * 行锁和 upsert 都会打到旧表。@DS 拦截器优先级高于事务拦截器，先切库再开事务才能命中 PG。
 * 前端 localStorage 只作本机缓存与降级，登录同一账号的其他浏览器从库里恢复；
 * 表结构在应用就绪时幂等创建（无独立迁移脚本）。
 */
@Slf4j
@DS("shreport")
@Component
public class ShReportAgentSessionDao {

    @Autowired
    private ShReportAgentSessionMapper sessionMapper;

    @EventListener(ApplicationReadyEvent.class)
    public void initTable() {
        try {
            sessionMapper.createTableIfNotExists();
        } catch (Exception e) {
            log.error("创建山洪报告会话持久化表失败", e);
        }
    }

    /** 当前用户的会话列表（按更新时间倒序），messages 已解析为 JSON 数组 */
    public List<JSONObject> list(String userId) {
        List<JSONObject> out = new ArrayList<>();
        for (ShReportAgentSession row : sessionMapper.listByUser(userId)) {
            JSONObject s = new JSONObject(true);
            s.put("conversationId", row.getConversationId());
            s.put("batchNo", row.getBatchNo());
            s.put("lybm", row.getLybm());
            s.put("lymc", row.getLymc());
            s.put("modelId", row.getModelId());
            s.put("modelLabel", row.getModelLabel());
            s.put("title", row.getTitle());
            s.put("messages", parseMessages(row.getMessages()));
            s.put("updateTime", row.getUpdateTime() == null ? 0L : row.getUpdateTime());
            s.put("archived", row.getArchived() != null && row.getArchived() == 1);
            out.add(s);
        }
        return out;
    }

    /** 按（用户, 会话）保存；消息按时间并集写入，旧窗口不能用整份旧列表覆盖新对话 */
    @Transactional
    public void save(String userId, JSONObject s) {
        String conversationId = s.getString("conversationId");
        ShReportAgentSession existing = sessionMapper.selectForUpdate(userId, conversationId);
        JSONArray incomingMsgs = s.getJSONArray("messages");
        if (incomingMsgs == null) {
            incomingMsgs = new JSONArray();
        }
        long incomingTs = s.getLongValue("updateTime");
        if (existing != null) {
            long oldTs = existing.getUpdateTime() == null ? 0L : existing.getUpdateTime();
            s.put("messages", ShReportSessionMessageMerge.merge(parseMessages(existing.getMessages()), incomingMsgs));
            s.put("updateTime", Math.max(oldTs, incomingTs));
            if (incomingTs < oldTs) {
                // 陈旧写入（另一个浏览器/窗口拿着更旧的快照来补推）：消息走并集补齐，
                // 但元数据一律置空交给 upsert 的 COALESCE 保留库中原值。
                // 否则会把新标题回退成旧标题、把别处刚做的归档标记取消掉（会话"复活"到主列表）。
                for (String key : new String[] {"batchNo", "lybm", "lymc", "modelId", "modelLabel", "title", "archived"}) {
                    s.put(key, null);
                }
            }
        }
        sessionMapper.upsert(toEntity(userId, s));
    }

    public void delete(String userId, String conversationId) {
        sessionMapper.deleteByUserAndConversationId(userId, conversationId);
    }

    private static ShReportAgentSession toEntity(String userId, JSONObject s) {
        ShReportAgentSession row = new ShReportAgentSession();
        row.setUserId(userId);
        row.setConversationId(s.getString("conversationId"));
        row.setBatchNo(s.getString("batchNo"));
        row.setLybm(s.getString("lybm"));
        row.setLymc(s.getString("lymc"));
        row.setModelId(s.getString("modelId"));
        row.setModelLabel(s.getString("modelLabel"));
        row.setTitle(s.getString("title"));
        JSONArray msgs = s.getJSONArray("messages");
        row.setMessages(msgs == null ? "[]" : msgs.toJSONString());
        row.setUpdateTime(s.getLongValue("updateTime"));
        Object archived = s.get("archived");
        // 部分保存不携带 archived：置 null 交给 upsert 的 COALESCE 保留库中原值
        row.setArchived(archived == null ? null : (Boolean.TRUE.equals(archived) ? 1 : 0));
        return row;
    }

    private static JSONArray parseMessages(String json) {
        try {
            JSONArray arr = JSON.parseArray(json == null || json.isEmpty() ? "[]" : json);
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
