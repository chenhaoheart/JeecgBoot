package org.jeecg.modules.airag.shreport.graph;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 报告图运行轨迹（内存，Demo / 页面轮询用）。按 batchNo 保留最近一次。
 */
@Component
public class ShReportGraphTraceStore {

    private static final int MAX_EVENTS = 200;
    private static final int MAX_RUNS = 30;

    private final ConcurrentHashMap<String, Map<String, Object>> byBatch = new ConcurrentHashMap<>();

    public synchronized void start(String batchNo, String scenario, boolean fullReport) {
        prune();
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("batchNo", batchNo);
        snap.put("scenario", scenario == null ? "" : scenario);
        snap.put("fullReport", fullReport);
        snap.put("status", "running");
        snap.put("currentNode", "start");
        snap.put("retryCount", 0);
        snap.put("maxRetry", 2);
        snap.put("targetChapters", List.of());
        snap.put("success", false);
        snap.put("cancelled", false);
        snap.put("events", new ArrayList<Map<String, Object>>());
        snap.put("startedAt", System.currentTimeMillis());
        byBatch.put(batchNo, snap);
    }

    public synchronized void accept(String node, String phase, Map<String, Object> payload) {
        String batchNo = payload == null ? null : String.valueOf(payload.getOrDefault("batchNo", ""));
        if (batchNo == null || batchNo.isBlank()) {
            return;
        }
        Map<String, Object> snap = byBatch.get(batchNo);
        if (snap == null) {
            start(batchNo, "live", Boolean.TRUE.equals(payload.get("fullReport")));
            snap = byBatch.get(batchNo);
        }
        String normalized = normalizeNode(node);
        snap.put("currentNode", normalized);
        snap.put("status", "running");
        if (payload != null) {
            copyIfPresent(payload, snap, "retryCount");
            copyIfPresent(payload, snap, "maxRetry");
            copyIfPresent(payload, snap, "targetChapters");
            copyIfPresent(payload, snap, "fullReport");
            copyIfPresent(payload, snap, "cancelled");
            copyIfPresent(payload, snap, "success");
            copyIfPresent(payload, snap, "ch7Written");
            copyIfPresent(payload, snap, "hasBlocking");
            copyIfPresent(payload, snap, "routeAfterQc");
            copyIfPresent(payload, snap, "routeAfterConsistency");
        }
        List<Map<String, Object>> events = eventsOf(snap);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("node", normalized);
        ev.put("phase", phase == null ? "leave" : phase);
        ev.put("ts", System.currentTimeMillis());
        ev.put("retryCount", snap.get("retryCount"));
        ev.put("targetChapters", snap.get("targetChapters"));
        events.add(ev);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    public synchronized void finish(String batchNo, boolean success, boolean cancelled) {
        Map<String, Object> snap = byBatch.get(batchNo);
        if (snap == null) {
            return;
        }
        snap.put("currentNode", "end");
        snap.put("success", success);
        snap.put("cancelled", cancelled);
        snap.put("status", cancelled ? "cancelled" : (success ? "success" : "failed"));
        snap.put("finishedAt", System.currentTimeMillis());
        accept("end", "leave", Map.of("batchNo", batchNo, "success", success, "cancelled", cancelled));
        snap.put("status", cancelled ? "cancelled" : (success ? "success" : "failed"));
        snap.put("currentNode", "end");
    }

    public Map<String, Object> get(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            return Map.of("status", "idle", "events", List.of());
        }
        Map<String, Object> snap = byBatch.get(batchNo);
        return snap == null ? Map.of("status", "idle", "batchNo", batchNo, "events", List.of()) : snap;
    }

    public static String normalizeNode(String node) {
        if (node == null || node.isBlank()) {
            return "unknown";
        }
        String n = node.trim();
        if ("__START__".equals(n) || "START".equalsIgnoreCase(n)) {
            return "start";
        }
        if ("__END__".equals(n) || "END".equalsIgnoreCase(n)) {
            return "end";
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> eventsOf(Map<String, Object> snap) {
        Object v = snap.get("events");
        if (v instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> events = new ArrayList<>();
        snap.put("events", events);
        return events;
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key) && from.get(key) != null) {
            to.put(key, from.get(key));
        }
    }

    private void prune() {
        if (byBatch.size() < MAX_RUNS) {
            return;
        }
        String oldest = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<String, Map<String, Object>> e : byBatch.entrySet()) {
            Object ts = e.getValue().get("startedAt");
            long t = ts instanceof Number n ? n.longValue() : 0L;
            if (t < oldestTs) {
                oldestTs = t;
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            byBatch.remove(oldest);
        }
    }
}
