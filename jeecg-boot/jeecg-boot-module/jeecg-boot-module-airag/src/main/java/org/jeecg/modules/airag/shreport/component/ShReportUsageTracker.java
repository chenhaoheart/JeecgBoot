package org.jeecg.modules.airag.shreport.component;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 山洪报告助手会话级 token 用量统计：
 * 每次模型调用（意图解析/大纲润色/章节写作）累加进会话上下文的 usage 节点，
 * 并通过 SSE usage 事件把累计快照推给前端面板；历史会话切换时经 /agent/context 恢复。
 */
@Slf4j
@Component
public class ShReportUsageTracker {

    @Autowired
    private ShReportSessionStore sessionStore;

    /** 会话 -> 当前对话轮的 SSE 通道；每轮对话开始时覆盖注册 */
    private final ConcurrentHashMap<String, SseChannel> channels = new ConcurrentHashMap<>();

    /** 会话 -> 合并锁：章节并发生成时避免 usage 互相覆盖 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private record SseChannel(SseEmitter emitter, Object lock) {
    }

    /** 对话轮开始时注册 SSE 通道，供生成过程中的用量事件推送 */
    public void register(String conversationId, SseEmitter emitter, Object sseLock) {
        if (empty(conversationId)) {
            return;
        }
        channels.put(conversationId, new SseChannel(emitter, sseLock));
    }

    /** 对话轮结束（emitter 已完成）时解除引用，避免 SseEmitter 长期滞留内存 */
    public void unregister(String conversationId) {
        if (empty(conversationId)) {
            return;
        }
        channels.remove(conversationId);
    }

    /**
     * 记录一次模型调用。payload 可含：scene（intent/outline/chapter）、chapterNo、
     * inputTokens、outputTokens、estimated、model。
     */
    public void record(String conversationId, JSONObject payload) {
        if (empty(conversationId) || payload == null) {
            return;
        }
        Object lock = locks.computeIfAbsent(conversationId, k -> new Object());
        JSONObject usage;
        synchronized (lock) {
            JSONObject ctx = sessionStore.getOrCreate(conversationId, null);
            usage = ctx.getJSONObject("usage");
            if (usage == null) {
                usage = new JSONObject();
            }
            long in = payload.getLongValue("inputTokens");
            long out = payload.getLongValue("outputTokens");
            usage.put("calls", usage.getIntValue("calls") + 1);
            usage.put("inputTokens", usage.getLongValue("inputTokens") + in);
            usage.put("outputTokens", usage.getLongValue("outputTokens") + out);
            usage.put("totalTokens", usage.getLongValue("inputTokens") + usage.getLongValue("outputTokens"));
            if (payload.getBooleanValue("estimated")) {
                usage.put("estimated", true);
            }
            String scene = payload.getString("scene");
            if (!empty(scene)) {
                JSONObject scenes = usage.getJSONObject("scenes");
                if (scenes == null) {
                    scenes = new JSONObject();
                }
                scenes.put(scene, accumulate(scenes.getJSONObject(scene), in, out));
                usage.put("scenes", scenes);
            }
            Integer chapterNo = payload.getInteger("chapterNo");
            if (chapterNo != null) {
                JSONObject chapters = usage.getJSONObject("chapters");
                if (chapters == null) {
                    chapters = new JSONObject();
                }
                String key = String.valueOf(chapterNo);
                chapters.put(key, accumulate(chapters.getJSONObject(key), in, out));
                usage.put("chapters", chapters);
            }
            String model = payload.getString("model");
            if (!empty(model)) {
                usage.put("lastModel", model);
            }
            usage.put("updateTime", System.currentTimeMillis());
            ctx.put("usage", usage);
            sessionStore.save(conversationId, ctx);
        }
        push(conversationId, usage);
    }

    /** 会话累计用量快照（无记录返回 null） */
    public JSONObject snapshot(String conversationId) {
        if (empty(conversationId)) {
            return null;
        }
        JSONObject ctx = sessionStore.get(conversationId);
        return ctx == null ? null : ctx.getJSONObject("usage");
    }

    private static JSONObject accumulate(JSONObject one, long in, long out) {
        if (one == null) {
            one = new JSONObject();
            one.put("calls", 0);
            one.put("inputTokens", 0L);
            one.put("outputTokens", 0L);
        }
        one.put("calls", one.getIntValue("calls") + 1);
        one.put("inputTokens", one.getLongValue("inputTokens") + in);
        one.put("outputTokens", one.getLongValue("outputTokens") + out);
        return one;
    }

    /** 用量快照推给当前对话轮的 SSE；通道失效（客户端断开）时移除，下一轮对话重新注册 */
    private void push(String conversationId, JSONObject usage) {
        SseChannel channel = channels.get(conversationId);
        if (channel == null) {
            return;
        }
        synchronized (channel.lock()) {
            try {
                channel.emitter().send(SseEmitter.event().name("usage").data(usage.toJSONString()));
            } catch (Exception e) {
                channels.remove(conversationId);
            }
        }
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }
}
