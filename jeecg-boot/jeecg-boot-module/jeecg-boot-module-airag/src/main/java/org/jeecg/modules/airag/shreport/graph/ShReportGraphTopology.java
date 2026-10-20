package org.jeecg.modules.airag.shreport.graph;

import org.bsc.langgraph4j.StateGraph;
import org.jeecg.modules.airag.shreport.component.ShReportGraphNodes;
import org.jeecg.modules.airag.shreport.component.ShReportGraphRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报告图拓扑：单一真源。
 * <p>
 * {@link ShReportGraphRunner#compile(ShReportGraphNodes)} 用它构建真实 {@link StateGraph}，
 * {@link #schema()} 用它产出前端编排看板的 nodes/edges。
 * 两边同源，因此改图必然改图，不会出现「页面画的图」与「实际执行的图」漂移。
 * <p>
 * 约定：条件边的 {@code to} 同时就是路由函数的返回值（例如 {@code qc -> rewrite} 的路由值就是
 * {@code ShReportGraphState.ROUTE_REWRITE} = "rewrite"）。因此路由映射无需额外配置，
 * 由 {@link #conditionalRoutes()} 直接推导。
 */
public final class ShReportGraphTopology {

    /** 展示用节点（含坐标与文案） */
    public record GraphNode(String id, String label, String desc, int x, int y, boolean synthetic) {
    }

    /** 展示用边；conditional=true 的边由路由函数决定走不走 */
    public record GraphEdge(String from, String to, String label, boolean conditional) {
    }

    /** 展示层专用节点，不是真实 StateGraph 节点 */
    public static final String START = "start";
    public static final String END = "end";

    /**
     * 展示坐标（与 GraphBoard 节点 148×54、NODE_LAYOUT 对齐）。
     * 第一行从左到右写到质检，右列向下写第 7 章并核对，底行向左保存并结束；
     * 打回重写在「撰写章节」正下方。水平净距约 120、垂直净距约 100。
     */
    private static final List<GraphNode> NODES = List.of(
            new GraphNode(START, "开始", "准备开始这一轮生成", 28, 32, true),
            new GraphNode("prepare", "锁住数字", "先锁住本流域的关键数字（人口、对象数等），后面各章必须用同一套数", 300, 32, false),
            new GraphNode("write", "撰写章节", "让 AI 撰写章节正文（整份时 1～6 章一起写，单章时只写指定章）", 572, 32, false),
            new GraphNode("qc", "章节质检", "检查这一章：是不是太短、有没有「待补充」、表格坏没坏", 844, 32, false),
            new GraphNode("write_ch7", "写第7章", "前六章过关后，根据它们汇总写第七章「成果与建议」", 844, 188, false),
            new GraphNode("rewrite", "打回重写", "质检没过，把问题写进要求里，只重写不合格的章", 572, 188, false),
            new GraphNode("consistency", "核对数字", "抽各章里的面积、人口等数字，看有没有前后打架", 844, 344, false),
            new GraphNode("persist", "保存报告", "把章节存进数据库，右侧章节面板会刷新出来", 572, 344, false),
            new GraphNode(END, "结束", "这一轮结束", 300, 344, true));

    private static final List<GraphEdge> EDGES = List.of(
            new GraphEdge(START, "prepare", null, false),
            new GraphEdge("prepare", "write", null, false),
            new GraphEdge("write", "qc", null, false),
            new GraphEdge("qc", "rewrite", "质检未过", true),
            new GraphEdge("qc", "write_ch7", "通过 / 达上限", true),
            new GraphEdge("qc", "persist", "单章完成", true),
            new GraphEdge("rewrite", "write", "只重跑失败章", false),
            new GraphEdge("write_ch7", "consistency", null, false),
            new GraphEdge("consistency", "rewrite", "数字矛盾", true),
            new GraphEdge("consistency", "persist", "通过 / 仅提示", true),
            new GraphEdge("persist", END, null, false));

    private ShReportGraphTopology() {
    }

    public static List<GraphNode> nodes() {
        return NODES;
    }

    /** 真实 StateGraph 节点（去掉 start / end 两个展示层节点） */
    public static List<GraphNode> realNodes() {
        List<GraphNode> out = new ArrayList<>();
        for (GraphNode n : NODES) {
            if (!n.synthetic()) {
                out.add(n);
            }
        }
        return out;
    }

    public static List<GraphEdge> edges() {
        return EDGES;
    }

    /** 无条件跳转的边 */
    public static List<GraphEdge> plainEdges() {
        List<GraphEdge> out = new ArrayList<>();
        for (GraphEdge e : EDGES) {
            if (!e.conditional()) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 条件跳转映射：节点 id ->（路由返回值 -> 真实目标节点 id）。
     * 按 from 聚合，供 addConditionalEdges 一次注册。
     */
    public static Map<String, Map<String, String>> conditionalRoutes() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (GraphEdge e : EDGES) {
            if (!e.conditional()) {
                continue;
            }
            out.computeIfAbsent(e.from(), k -> new LinkedHashMap<>())
                    .put(e.to(), realId(e.to()));
        }
        return out;
    }

    /** 展示层 id 转 StateGraph 真实 id */
    public static String realId(String id) {
        if (START.equals(id)) {
            return StateGraph.START;
        }
        if (END.equals(id)) {
            return StateGraph.END;
        }
        return id;
    }

    /** 前端编排看板拓扑：nodes（含坐标文案）+ edges（含条件标签） */
    public static Map<String, Object> schema() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (GraphNode n : NODES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.id());
            m.put("label", n.label());
            m.put("desc", n.desc());
            m.put("x", n.x());
            m.put("y", n.y());
            nodes.add(m);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (GraphEdge e : EDGES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", e.from());
            m.put("to", e.to());
            if (e.label() != null) {
                m.put("label", e.label());
            }
            m.put("kind", e.conditional() ? "conditional" : "always");
            edges.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        return out;
    }

    /** 从 start 出发可达的节点集合（含 start / end） */
    public static java.util.Set<String> reachable() {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        seen.add(START);
        queue.add(START);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            for (GraphEdge e : EDGES) {
                if (e.from().equals(cur) && seen.add(e.to())) {
                    queue.add(e.to());
                }
            }
        }
        return Collections.unmodifiableSet(seen);
    }
}
