package org.jeecg.modules.airag.shreport;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话消息合并的稳定 id 规则：id 必须与前端 sessionMerge.ts 的 stableMsgId 完全一致，
 * 两端合并键对不齐会导致同一条消息在服务端/前端各算一条（重复）或互相覆盖。
 * 期望值用 Node 按同一算法生成，作为跨语言契约固定下来。
 */
public class ShReportSessionMessageMergeTest {

    private static JSONObject msg(String role, String content) {
        JSONObject m = new JSONObject(true);
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Test
    public void stableIdMatchesFrontendHash() {
        assertEquals("m-hjdeq82", ShReportSessionMessageMerge.stableId(msg("user", "继续")));
        assertEquals("m-hp1ez5t", ShReportSessionMessageMerge.stableId(msg("assistant", "收到，我先分析一下你的需求…")));
        assertEquals("m-h4l0bgu", ShReportSessionMessageMerge.stableId(msg("user", "")));
    }

    @Test
    public void mergeFillsStableIdForLegacyMessages() {
        JSONArray existing = new JSONArray();
        existing.add(msg("user", "继续"));
        JSONArray incoming = new JSONArray();
        JSONObject stamped = msg("user", "继续");
        stamped.put("id", "m-hjdeq82");
        stamped.put("time", 1000L);
        incoming.add(stamped);

        JSONArray out = ShReportSessionMessageMerge.merge(existing, incoming);
        assertEquals(1, out.size());
        assertEquals("m-hjdeq82", out.getJSONObject(0).getString("id"));
    }

    @Test
    public void mergeCollapsesServerAndClientCopiesOfSameMessage() {
        // 同一条回复：服务端落的是内容哈希 id 副本，客户端推送的是随机 id 副本，必须折叠成一条
        JSONArray existing = new JSONArray();
        JSONObject serverCopy = msg("assistant", "好的，开始生成全部 7 个章节。");
        String stable = ShReportSessionMessageMerge.stableId(serverCopy);
        serverCopy.put("id", stable);
        serverCopy.put("time", 1000L);
        existing.add(serverCopy);

        JSONArray incoming = new JSONArray();
        JSONObject clientCopy = msg("assistant", "好的，开始生成全部 7 个章节。");
        clientCopy.put("id", "m-1700000000-abcd");
        clientCopy.put("time", 1000L);
        incoming.add(clientCopy);

        JSONArray out = ShReportSessionMessageMerge.merge(existing, incoming);
        assertEquals(1, out.size());
        assertEquals(stable, out.getJSONObject(0).getString("id"));
    }

    @Test
    public void mergeKeepsProgressLineKeyedByChapter() {
        JSONArray arr = new JSONArray();
        JSONObject progress = msg("assistant", "⏳ 第1章 流域概况：生成中");
        progress.put("__progress", true);
        arr.add(progress);

        JSONArray out = ShReportSessionMessageMerge.merge(arr, new JSONArray());
        assertEquals(1, out.size());
        assertNull(out.getJSONObject(0).getString("id"));
        assertTrue(out.getJSONObject(0).getBoolean("__progress"));
    }
}
