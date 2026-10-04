package org.jeecg.modules.airag.shreport;

import org.jeecg.modules.airag.shreport.graph.ShReportGraphNodes;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphRunner;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphState;
import org.jeecg.modules.airag.shreport.service.ShReportGenerateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告图：条件边与假写作节点回边。
 */
class ShReportGraphRunnerTest {

    private ShReportQualityChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ShReportQualityChecker();
        ReflectionTestUtils.setField(checker, "minLength", 200);
    }

    @Test
    void routeAfterQc_blockGoesRewrite() {
        ShReportGraphState s = state(List.of(1), false, 0, 2);
        Map<Integer, List<ShReportQcIssue>> qc = Map.of(1,
                List.of(ShReportQcIssue.block(ShReportQcIssue.TYPE_SHORT, "过短")));
        s = new ShReportGraphState(with(s, Map.of(ShReportGraphState.QC_ISSUES, qc)));
        assertEquals(ShReportGraphState.ROUTE_REWRITE, s.routeAfterQc());
    }

    @Test
    void routeAfterQc_maxRetryGoesPersist() {
        ShReportGraphState s = state(List.of(1), false, 2, 2);
        Map<Integer, List<ShReportQcIssue>> qc = Map.of(1,
                List.of(ShReportQcIssue.block(ShReportQcIssue.TYPE_SHORT, "过短")));
        s = new ShReportGraphState(with(s, Map.of(
                ShReportGraphState.QC_ISSUES, qc,
                ShReportGraphState.RETRY_COUNT, 2)));
        assertEquals(ShReportGraphState.ROUTE_PERSIST, s.routeAfterQc());
    }

    @Test
    void routeAfterQc_fullReportGoesCh7() {
        ShReportGraphState s = state(List.of(1, 2, 3, 4, 5, 6), true, 0, 2);
        assertEquals(ShReportGraphState.ROUTE_WRITE_CH7, s.routeAfterQc());
    }

    @Test
    void routeAfterQc_cancelGoesPersist() {
        ShReportGraphState s = new ShReportGraphState(with(state(List.of(1), false, 0, 2),
                Map.of(ShReportGraphState.CANCELLED, true)));
        assertEquals(ShReportGraphState.ROUTE_PERSIST, s.routeAfterQc());
    }

    @Test
    void routeAfterConsistency_conflictGoesRewrite() {
        List<ShReportQcIssue> cons = List.of(ShReportQcIssue.hint(ShReportQcIssue.TYPE_CONSISTENCY,
                "流域面积前后不一致：基准值 10km²，第3章为12km²").withChapters(List.of(3)));
        ShReportGraphState s = new ShReportGraphState(with(state(List.of(7), true, 0, 2),
                Map.of(ShReportGraphState.CONSISTENCY_ISSUES, cons, ShReportGraphState.CH7_WRITTEN, true)));
        assertEquals(List.of(3), s.consistencyConflictChapters());
        assertEquals(ShReportGraphState.ROUTE_REWRITE, s.routeAfterConsistency());
    }

    @Test
    void routeAfterConsistency_multiChapterConflictGoesRewrite() {
        // 多章并列文案（（第1、3章））无法用正则反解，必须依赖结构化 chapterNos
        List<ShReportQcIssue> cons = List.of(ShReportQcIssue.hint(ShReportQcIssue.TYPE_CONSISTENCY,
                "流域面积在各章取值不一致：10km²（第1、3章）、12km²（第2、4章）").withChapters(List.of(2, 4)));
        ShReportGraphState s = new ShReportGraphState(with(state(List.of(7), true, 0, 2),
                Map.of(ShReportGraphState.CONSISTENCY_ISSUES, cons, ShReportGraphState.CH7_WRITTEN, true)));
        assertEquals(List.of(2, 4), s.consistencyConflictChapters());
        assertEquals(ShReportGraphState.ROUTE_REWRITE, s.routeAfterConsistency());
    }

    @Test
    void consistencyIssueWithoutChapterNosDoesNotRewrite() {
        List<ShReportQcIssue> cons = List.of(ShReportQcIssue.hint(ShReportQcIssue.TYPE_CONSISTENCY,
                "流域面积前后不一致：基准值 10km²，第3章为12km²"));
        ShReportGraphState s = new ShReportGraphState(with(state(List.of(7), true, 0, 2),
                Map.of(ShReportGraphState.CONSISTENCY_ISSUES, cons, ShReportGraphState.CH7_WRITTEN, true)));
        assertEquals(List.of(), s.consistencyConflictChapters());
        assertEquals(ShReportGraphState.ROUTE_PERSIST, s.routeAfterConsistency());
    }

    @Test
    void graph_rewritesThenPasses() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ShReportGraphNodes nodes = new ShReportGraphNodes((st, ch, req) -> {
            writes.incrementAndGet();
            if (writes.get() == 1) {
                return "过短";
            }
            return goodChapter("甲流域第" + ch + "章。");
        }, checker, new ShReportGraphNodes.SideEffects() {
        });
        ShReportGraphRunner runner = new ShReportGraphRunner(nodes, 2, false);
        boolean ok = runner.runChapter("B1", "L1", "甲流域", 2, null, null, "m", null);
        assertTrue(ok);
        assertTrue(writes.get() >= 2);
    }

    @Test
    void graph_maxRetryAdvancesWithoutSuccess() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ShReportGraphNodes nodes = new ShReportGraphNodes((st, ch, req) -> {
            writes.incrementAndGet();
            return "过短";
        }, checker, new ShReportGraphNodes.SideEffects() {
        });
        ShReportGraphRunner runner = new ShReportGraphRunner(nodes, 2,false);
        boolean ok = runner.runChapter("B2", "L1", "甲流域", 2, null, null, "m", null);
        assertFalse(ok);
        assertTrue(writes.get() >= 3);
    }

    @Test
    void graph_cancelShortCircuit() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ShReportGraphNodes nodes = new ShReportGraphNodes((st, ch, req) -> {
            writes.incrementAndGet();
            return goodChapter("甲流域");
        }, checker, new ShReportGraphNodes.SideEffects() {
        });
        ShReportGraphRunner runner = new ShReportGraphRunner(nodes, 2, false);
        ShReportGenerateService.ProgressListener cancelled = new ShReportGenerateService.ProgressListener() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        };
        boolean ok = runner.runChapter("B3", "L1", "甲流域", 2, null, null, "m", cancelled);
        assertFalse(ok);
        assertEquals(0, writes.get());
    }

    private ShReportGraphState state(List<Integer> targets, boolean full, int retry, int max) {
        return new ShReportGraphState(ShReportGraphState.initial(
                "B", "L", "甲流域", "", null, "m", new ArrayList<>(targets), full, max));
    }

    private static Map<String, Object> with(ShReportGraphState s, Map<String, Object> patch) {
        Map<String, Object> m = new java.util.HashMap<>(s.data());
        m.putAll(patch);
        return m;
    }

    private static String goodChapter(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < 260) {
            sb.append("本章节内容经过现场调查与数据核对，结论可靠。");
        }
        return sb.toString();
    }
}
