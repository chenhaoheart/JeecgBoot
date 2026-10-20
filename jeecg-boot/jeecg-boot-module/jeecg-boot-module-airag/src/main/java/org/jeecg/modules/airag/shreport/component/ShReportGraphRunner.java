package org.jeecg.modules.airag.shreport.component;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphState;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphTopology;
import org.jeecg.modules.airag.shreport.service.ShReportGenerateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/**
 * 编译并运行山洪报告生成图。
 */
@Slf4j
@Component
public class ShReportGraphRunner {

    public static final String NODE_PREPARE = "prepare";
    public static final String NODE_WRITE = "write";
    public static final String NODE_QC = "qc";
    public static final String NODE_REWRITE = "rewrite";
    public static final String NODE_WRITE_CH7 = "write_ch7";
    public static final String NODE_CONSISTENCY = "consistency";
    public static final String NODE_PERSIST = "persist";

    private final ShReportGraphNodes nodes;
    private final int maxRetry;
    private final boolean autoRewrite;
    private final CompiledGraph<ShReportGraphState> compiled;

    @Autowired(required = false)
    private ShReportGraphTraceStore traceStore;

    @Autowired
    public ShReportGraphRunner(ShReportGraphNodes nodes,
                               @Value("${shreport.graph.max-retry:2}") int maxRetry,
                               @Value("${shreport.graph.auto-rewrite:false}") boolean autoRewrite) {
        this.nodes = nodes;
        this.maxRetry = maxRetry <= 0 ? 2 : maxRetry;
        // 默认关闭自动回写：首次生成的质检结果只作参考，由用户逐章决定编辑或重新生成，
        // 避免质检不过触发整章重写（token 翻倍且可能来回拉锯）
        this.autoRewrite = autoRewrite;
        try {
            this.compiled = compile(nodes);
        } catch (GraphStateException e) {
            throw new IllegalStateException("编译山洪报告图失败", e);
        }
    }

    /**
     * 按 {@link ShReportGraphTopology} 构建真实 StateGraph。
     * 拓扑是单一真源：新增/删除节点或改边只改拓扑，页面 schema 会同步变化。
     */
    static CompiledGraph<ShReportGraphState> compile(ShReportGraphNodes nodes) throws GraphStateException {
        Map<String, AsyncNodeActionWithConfig<ShReportGraphState>> actions = Map.of(
                NODE_PREPARE, node_async(nodes::prepare),
                NODE_WRITE, node_async(nodes::write),
                NODE_QC, node_async(nodes::qc),
                NODE_REWRITE, node_async(nodes::rewrite),
                NODE_WRITE_CH7, node_async(nodes::writeCh7),
                NODE_CONSISTENCY, node_async(nodes::consistency),
                NODE_PERSIST, node_async(nodes::persist));
        Map<String, AsyncEdgeAction<ShReportGraphState>> routes = Map.of(
                NODE_QC, edge_async(ShReportGraphState::routeAfterQc),
                NODE_CONSISTENCY, edge_async(ShReportGraphState::routeAfterConsistency));

        var graph = new StateGraph<>(ShReportGraphState::new);
        for (ShReportGraphTopology.GraphNode n : ShReportGraphTopology.realNodes()) {
            var action = actions.get(n.id());
            if (action == null) {
                throw new IllegalStateException("拓扑节点缺少实现，请检查 ShReportGraphRunner：node=" + n.id());
            }
            graph = graph.addNode(n.id(), action);
        }
        for (ShReportGraphTopology.GraphEdge e : ShReportGraphTopology.plainEdges()) {
            graph = graph.addEdge(ShReportGraphTopology.realId(e.from()), ShReportGraphTopology.realId(e.to()));
        }
        for (Map.Entry<String, Map<String, String>> en : ShReportGraphTopology.conditionalRoutes().entrySet()) {
            var route = routes.get(en.getKey());
            if (route == null) {
                throw new IllegalStateException("拓扑条件边缺少路由函数，请检查 ShReportGraphRunner：node=" + en.getKey());
            }
            graph = graph.addConditionalEdges(ShReportGraphTopology.realId(en.getKey()), route, en.getValue());
        }
        return graph.compile(CompileConfig.builder().recursionLimit(40).build());
    }

    public boolean runAll(String batchNo, String lybm, String lymc, String requirement,
                          String outline, String modelId, ShReportGenerateService.ProgressListener listener) {
        List<Integer> targets = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            targets.add(i);
        }
        return run(batchNo, lybm, lymc, requirement, outline, modelId, targets, true, listener);
    }

    public boolean runChapter(String batchNo, String lybm, String lymc, int chapterNo,
                              String requirement, String outline, String modelId,
                              ShReportGenerateService.ProgressListener listener) {
        return run(batchNo, lybm, lymc, requirement, outline, modelId, List.of(chapterNo), false, listener);
    }

    boolean run(String batchNo, String lybm, String lymc, String requirement, String outline, String modelId,
                List<Integer> targets, boolean fullReport, ShReportGenerateService.ProgressListener listener) {
        Map<String, Object> init = ShReportGraphState.initial(
                batchNo, lybm, lymc, requirement, outline, modelId, targets, fullReport, maxRetry, autoRewrite);
        RunnableConfig config = RunnableConfig.builder()
                .threadId(batchNo + "-" + (fullReport ? "all" : targets.toString()))
                .addMetadata(ShReportGraphNodes.META_LISTENER, listener == null
                        ? new ShReportGenerateService.ProgressListener() {
                } : listener)
                .build();
        if (traceStore != null) {
            traceStore.start(batchNo, fullReport ? "full" : "chapter", fullReport);
        }
        try {
            ShReportGraphState last = invoke(init, config);
            boolean ok = last != null && last.success();
            if (traceStore != null) {
                traceStore.finish(batchNo, ok, last != null && last.cancelled());
            }
            publishTraceEnd(batchNo, listener);
            return ok;
        } catch (Exception e) {
            log.error("报告图执行失败 batch={} chapters={}", batchNo, targets, e);
            if (traceStore != null) {
                traceStore.finish(batchNo, false, false);
            }
            publishTraceEnd(batchNo, listener);
            if (listener != null) {
                listener.onText("生成失败：" + e.getMessage());
                listener.onRefresh(batchNo);
            }
            return false;
        }
    }

    /** 结束快照走已有的 graph 事件，页面不必靠轮询才知道这一轮结束 */
    private void publishTraceEnd(String batchNo, ShReportGenerateService.ProgressListener listener) {
        if (listener == null || traceStore == null || batchNo == null || batchNo.isBlank()) {
            return;
        }
        listener.onGraphNode("end", "leave", traceStore.get(batchNo));
    }

    ShReportGraphState invoke(Map<String, Object> init, RunnableConfig config) {
        ShReportGraphState last = null;
        for (var item : compiled.stream(init, config)) {
            last = item.state();
            log.debug("报告图节点={} batch={}", item.node(), last == null ? "" : last.batchNo());
        }
        return last;
    }
}
