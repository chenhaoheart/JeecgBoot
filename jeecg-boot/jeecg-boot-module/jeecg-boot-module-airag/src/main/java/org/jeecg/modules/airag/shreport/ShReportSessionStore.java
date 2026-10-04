package org.jeecg.modules.airag.shreport;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山洪报告助手会话：Redis 持久化，进程内 ConcurrentHashMap 作降级。
 * 重启后仍能恢复流域、批次与待执行动作。
 */
@Slf4j
@Component
public class ShReportSessionStore {

    private static final String KEY_PREFIX = "shreport:conv:";
    /**
     * 删除墓碑前缀（键：用户维度 + 会话）。
     * 手工在库里删掉会话后，本机/其它窗口仍可能在跑这一轮对话，结束时saveTurn 会把它 upsert 回库复活；
     * 删除接口先打墓碑，落库前先查墓碑，从根上断掉复活路径。
     */
    private static final String DEL_KEY_PREFIX = "shreport:del:";
    private static final long TTL_SECONDS = 7 * 24 * 3600L;

    private final ConcurrentHashMap<String, JSONObject> local = new ConcurrentHashMap<>();
    /** 墓碑的进程内降级副本：Redis 不可用时同一进程内仍能拦住复活 */
    private final java.util.Set<String> deletedLocal = ConcurrentHashMap.newKeySet();

    @Autowired
    private RedisUtil redisUtil;

    public JSONObject getOrCreate(String conversationId, String batchNoParam) {
        JSONObject ctx = get(conversationId);
        if (ctx == null) {
            ctx = new JSONObject();
            ctx.put("batchNo", empty(batchNoParam) ? "B" + System.currentTimeMillis() : batchNoParam);
            ctx.put("history", new JSONArray());
            ctx.put("cancelled", false);
            save(conversationId, ctx);
            return ctx;
        }
        if (!empty(batchNoParam) && empty(ctx.getString("batchNo"))) {
            ctx.put("batchNo", batchNoParam);
            save(conversationId, ctx);
        }
        return ctx;
    }

    public JSONObject get(String conversationId) {
        if (empty(conversationId)) {
            return null;
        }
        try {
            Object v = redisUtil.get(KEY_PREFIX + conversationId);
            if (v != null) {
                JSONObject ctx = toJson(v);
                local.put(conversationId, ctx);
                return ctx;
            }
        } catch (Exception e) {
            log.warn("读取山洪报告会话失败，降级内存 conv={}", conversationId, e);
        }
        return local.get(conversationId);
    }

    public void save(String conversationId, JSONObject ctx) {
        if (empty(conversationId) || ctx == null) {
            return;
        }
        local.put(conversationId, ctx);
        try {
            redisUtil.set(KEY_PREFIX + conversationId, ctx.toJSONString(), TTL_SECONDS);
        } catch (Exception e) {
            log.warn("写入山洪报告会话失败，仅保存在内存 conv={}", conversationId, e);
        }
    }

    public void setCancelled(String conversationId, boolean cancelled) {
        JSONObject ctx = getOrCreate(conversationId, null);
        ctx.put("cancelled", cancelled);
        save(conversationId, ctx);
    }

    /** 新一轮对话开始：清掉上一轮的停止标记，避免把新请求直接判停 */
    public void beginRun(String conversationId) {
        JSONObject ctx = getOrCreate(conversationId, null);
        ctx.put("runId", ctx.getLongValue("runId") + 1);
        ctx.put("cancelled", false);
        save(conversationId, ctx);
    }

    public boolean isCancelled(String conversationId) {
        JSONObject ctx = get(conversationId);
        return ctx != null && Boolean.TRUE.equals(ctx.getBoolean("cancelled"));
    }

    private static String delKey(String userKey, String conversationId) {
        return DEL_KEY_PREFIX + (userKey == null ? "" : userKey) + ":" + conversationId;
    }

    /** 会话已被删除：打上墓碑，之后任何落库/补推都不再把它写回来 */
    public void markSessionDeleted(String userKey, String conversationId) {
        if (empty(conversationId)) {
            return;
        }
        String key = delKey(userKey, conversationId);
        deletedLocal.add(key);
        try {
            redisUtil.set(key, "1", TTL_SECONDS);
        } catch (Exception e) {
            log.warn("写入山洪报告会话删除墓碑失败，仅进程内生效 conv={}: {}", conversationId, e.getMessage());
        }
    }

    /** 是否已被删除；Redis 异常时退化为进程内墓碑判断 */
    public boolean isSessionDeleted(String userKey, String conversationId) {
        if (empty(conversationId)) {
            return false;
        }
        String key = delKey(userKey, conversationId);
        try {
            if (redisUtil.get(key) != null) {
                return true;
            }
        } catch (Exception e) {
            log.warn("读取山洪报告会话删除墓碑失败，降级内存判断 conv={}: {}", conversationId, e.getMessage());
        }
        return deletedLocal.contains(key);
    }

    @SuppressWarnings("unchecked")
    private static JSONObject toJson(Object v) {
        if (v instanceof JSONObject) {
            return (JSONObject) v;
        }
        if (v instanceof Map) {
            return new JSONObject((Map<String, Object>) v);
        }
        return JSON.parseObject(String.valueOf(v));
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }
}
