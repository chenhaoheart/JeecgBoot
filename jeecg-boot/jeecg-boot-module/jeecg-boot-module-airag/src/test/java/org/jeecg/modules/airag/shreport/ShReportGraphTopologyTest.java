package org.jeecg.modules.airag.shreport;

import org.jeecg.modules.airag.shreport.graph.ShReportGraphNodes;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphRunner;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphState;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphTopology;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拓扑一致性：页面画的图必须等于实际执行的图。
 * 拓扑是单一真源，compile() 与 schema() 都从它派生，这里守住不变量。
 */
class ShReportGraphTopologyTest {

    private static final Set<String> RUNNER_NODES = Set.of(
            ShReportGraphRunner.NODE_PREPARE,
            ShReportGraphRunner.NODE_WRITE,
            ShReportGraphRunner.NODE_QC,
            ShReportGraphRunner.NODE_REWRITE,
            ShReportGraphRunner.NODE_WRITE_CH7,
            ShReportGraphRunner.NODE_CONSISTENCY,
            ShReportGraphRunner.NODE_PERSIST);

    @Test
    void realNodesMatchRunnerNodeConstants() {
        List<String> ids = new ArrayList<>();
        for (ShReportGraphTopology.GraphNode n : ShReportGraphTopology.realNodes()) {
            ids.add(n.id());
        }
        assertEquals(RUNNER_NODES, new LinkedHashSet<>(ids),
                "拓扑节点集合必须等于 ShReportGraphRunner.NODE_*，新增/删除节点要同步两处");
    }

    @Test
    void conditionalEdgeTargetsAreRealNodes() {
        for (Map.Entry<String, Map<String, String>> en : ShReportGraphTopology.conditionalRoutes().entrySet()) {
            for (Map.Entry<String, String> route : en.getValue().entrySet()) {
                assertTrue(RUNNER_NODES.contains(route.getValue()),
                        "条件边目标必须是真实节点，否则 langgraph4j 会在运行时报错：" + en.getKey() + " -> " + route.getValue());
                assertEquals(route.getKey(), route.getValue(),
                        "条件边的 to 同时是路由返回值，两者必须一致");
            }
        }
    }

    @Test
    void qcRoutesCoverAllPossibleRouteValues() {
        assertEquals(Set.of(ShReportGraphState.ROUTE_REWRITE,
                        ShReportGraphState.ROUTE_WRITE_CH7,
                        ShReportGraphState.ROUTE_PERSIST),
                ShReportGraphTopology.conditionalRoutes().get(ShReportGraphRunner.NODE_QC).keySet(),
                "routeAfterQc 可能返回的所有路由值都必须在拓扑里有对应边");
    }

    @Test
    void consistencyRoutesCoverAllPossibleRouteValues() {
        assertEquals(Set.of(ShReportGraphState.ROUTE_REWRITE,
                        ShReportGraphState.ROUTE_PERSIST),
                ShReportGraphTopology.conditionalRoutes().get(ShReportGraphRunner.NODE_CONSISTENCY).keySet(),
                "routeAfterConsistency 可能返回的所有路由值都必须在拓扑里有对应边");
    }

    @Test
    void everyRealNodeIsReachableFromStart() {
        Set<String> reachable = ShReportGraphTopology.reachable();
        for (String id : RUNNER_NODES) {
            assertTrue(reachable.contains(id), "拓扑存在孤立节点，永远走不到：" + id);
        }
        assertTrue(reachable.contains(ShReportGraphTopology.END));
    }

    @Test
    void realIdMapsSyntheticNodesToStateGraphIds() {
        assertEquals("__START__", ShReportGraphTopology.realId("start"));
        assertEquals("__END__", ShReportGraphTopology.realId("end"));
        assertEquals("qc", ShReportGraphTopology.realId("qc"));
    }

    @Test
    void schemaExposesNineNodesAndElevenEdges() {
        Map<String, Object> schema = ShReportGraphTopology.schema();
        @SuppressWarnings("unchecked")
        var nodes = (List<Object>) schema.get("nodes");
        @SuppressWarnings("unchecked")
        var edges = (List<Object>) schema.get("edges");
        assertEquals(9, nodes.size());
        assertEquals(11, edges.size());
    }

    /** 拓扑能真正编译成图：拓扑新增节点而 Runner 没实现时会直接抛异常 */
    @Test
    void compileBuildsGraphFromTopology() throws Exception {
        ShReportGraphNodes nodes = new ShReportGraphNodes((st, ch, req) -> "甲流域第" + ch + "章。",
                new ShReportQualityChecker(), new ShReportGraphNodes.SideEffects() {
        });
        new ShReportGraphRunner(nodes, 2, false);
    }

    /** 展示层节点不能混进真实图，否则 addNode 会收到 start/end 这种假 id */
    @Test
    void syntheticNodesAreNotRealNodes() {
        for (ShReportGraphTopology.GraphNode n : ShReportGraphTopology.realNodes()) {
            assertFalse(n.synthetic());
        }
        assertEquals(7, ShReportGraphTopology.realNodes().size());
        assertEquals(9, ShReportGraphTopology.nodes().size());
    }
}
