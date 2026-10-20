package org.jeecg.modules.airag.shreport.util;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话消息按条合并：只把新消息并入，禁止用旧窗口的整份列表覆盖新对话。
 */
public final class ShReportSessionMessageMerge {

    private static final Pattern CHAPTER = Pattern.compile("第(\\d+)章");

    private ShReportSessionMessageMerge() {
    }

    public static JSONArray merge(JSONArray left, JSONArray right) {
        Map<String, JSONObject> byKey = new LinkedHashMap<>();
        Map<String, String> fpIndex = new HashMap<>();
        ingest(byKey, fpIndex, left);
        ingest(byKey, fpIndex, right);
        List<JSONObject> list = new ArrayList<>(byKey.values());
        list.sort((a, b) -> Long.compare(a.getLongValue("time"), b.getLongValue("time")));
        JSONArray out = new JSONArray();
        out.addAll(list);
        return out;
    }

    private static void ingest(Map<String, JSONObject> byKey, Map<String, String> fpIndex, JSONArray arr) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            Object raw = arr.get(i);
            if (!(raw instanceof JSONObject)) {
                continue;
            }
            JSONObject src = (JSONObject) raw;
            JSONObject m = new JSONObject(true);
            m.putAll(src);
            if (m.getString("role") == null) {
                continue;
            }
            if (m.getLongValue("time") <= 0) {
                m.put("time", i + 1L);
            }
            if (empty(m.getString("id")) && !Boolean.TRUE.equals(m.getBoolean("__progress"))
                    && !Boolean.TRUE.equals(m.getBoolean("__streaming"))) {
                // 旧版本落库的消息没有 id，合并键会退化成 role+前200字指纹，
                // 与前端带 id 的版本对不上导致同一条消息重复。补上与前端
                // sessionMerge.stableMsgId 一致的稳定 id（djb2 + base36），两端键才一致
                m.put("id", stableId(m));
            }
            String dedupeKey = keyOf(m);
            // 内容指纹兜底：同一条消息可能在服务端（内容哈希 id）和客户端（随机 id）各存一份，
            // 只要其中一方是内容哈希 id，就按指纹折叠成一条，避免重复气泡
            String fpKey = fingerprintKey(m);
            String target = null;
            if (byKey.containsKey(dedupeKey)) {
                target = dedupeKey;
            } else if (fpIndex.containsKey(fpKey)) {
                target = fpIndex.get(fpKey);
            }
            if (target == null) {
                byKey.put(dedupeKey, m);
                fpIndex.put(fpKey, dedupeKey);
            } else {
                JSONObject merged = pick(byKey.get(target), m);
                // 双方 id 都不是内容哈希时保留原 id；有一方是 m-h 时收敛到内容哈希，两端才能对齐
                if (target.startsWith("id:m-h") && !dedupeKey.startsWith("id:m-h")) {
                    merged.put("id", target.substring(3));
                }
                byKey.put(target, merged);
                fpIndex.put(fpKey, target);
            }
        }
    }

    /** 与 keyOf 相同但永远不含 id 的内容指纹键（进度行按章节，其余按 role+前200字） */
    private static String fingerprintKey(JSONObject m) {
        if (Boolean.TRUE.equals(m.getBoolean("__progress"))) {
            Matcher g = CHAPTER.matcher(m.getString("content") == null ? "" : m.getString("content"));
            return g.find() ? "pg:" + g.group(1) : "pg:" + safe(m.getString("content"), 40);
        }
        return "fp:" + m.getString("role") + ":" + safe(m.getString("content"), 200);
    }

    /**
     * 无 id 消息的稳定 id：同内容在任意端算出同一个值。
     * 必须与前端 sessionMerge.ts 的 stableMsgId 保持一致。
     */
    static String stableId(JSONObject m) {
        String role = m.getString("role") == null ? "" : m.getString("role");
        String content = m.getString("content") == null ? "" : m.getString("content");
        String s = role + "\n" + content;
        int h = 5381;
        for (int i = 0; i < s.length(); i++) {
            h = (h << 5) + h + s.charAt(i);
        }
        return "m-h" + Long.toString(Integer.toUnsignedLong(h), 36);
    }

    static String keyOf(JSONObject m) {
        String id = m.getString("id");
        if (id != null && !id.isEmpty()) {
            return "id:" + id;
        }
        if (Boolean.TRUE.equals(m.getBoolean("__progress"))) {
            Matcher g = CHAPTER.matcher(m.getString("content") == null ? "" : m.getString("content"));
            return g.find() ? "pg:" + g.group(1) : "pg:" + safe(m.getString("content"), 40);
        }
        return "fp:" + m.getString("role") + ":" + safe(m.getString("content"), 200);
    }

    private static JSONObject pick(JSONObject a, JSONObject b) {
        int aLen = len(a.getString("content"));
        int bLen = len(b.getString("content"));
        long aTime = a.getLongValue("time");
        long bTime = b.getLongValue("time");
        JSONObject newer = bTime != aTime ? (bTime > aTime ? b : a) : (bLen >= aLen ? b : a);
        JSONObject older = newer == b ? a : b;
        JSONObject out = new JSONObject(true);
        out.putAll(older);
        out.putAll(newer);
        String newerContent = newer.getString("content");
        String olderContent = older.getString("content");
        out.put("content", len(newerContent) >= len(olderContent) ? newerContent : olderContent);
        out.put("time", Math.max(aTime, bTime));
        if (empty(newer.getString("id")) && !empty(older.getString("id"))) {
            out.put("id", older.getString("id"));
        }
        // 流式占位标记只属于产生它的窗口：流结束后的合并不能把 __streaming 永久留在库里，
        // 否则所有窗口拉取后这条气泡会永远显示"生成中"的闪烁光标
        if (!Boolean.TRUE.equals(newer.getBoolean("__streaming"))) {
            out.remove("__streaming");
        }
        return out;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    private static String safe(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }
}
