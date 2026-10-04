package org.jeecg.modules.airag.shreport.graph;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.jeecg.modules.airag.shreport.service.ShReportGenerateService;
import org.jeecg.modules.airag.shreport.ShReportQualityChecker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 不调大模型、不写库的图编排 Demo：用假写作节点把一次运行推到 SSE。
 */
@Slf4j
@Service
public class ShReportGraphDemoService {

    public static final String SCENARIO_PASS = "pass";
    public static final String SCENARIO_REWRITE = "rewrite";
    public static final String SCENARIO_CHAPTER = "chapter";

    @Autowired
    private ShReportGraphTraceStore traceStore;

    @Autowired
    private ShReportQualityChecker qualityChecker;

    private final ExecutorService demoPool = Executors.newCachedThreadPool();

    public void run(String scenario, SseEmitter emitter) {
        String batchNo = "DEMO" + System.currentTimeMillis();
        boolean full = !SCENARIO_CHAPTER.equals(scenario);
        demoPool.submit(() -> execute(scenario, batchNo, full, emitter));
    }

    private void execute(String scenario, String batchNo, boolean full, SseEmitter emitter) {
        Object lock = new Object();
        try {
            send(emitter, lock, "schema", ShReportGraphTopology.schema());
            traceStore.start(batchNo, scenario, full);
            send(emitter, lock, "trace", traceStore.get(batchNo));

            AtomicInteger writes = new AtomicInteger();
            boolean failFirst = SCENARIO_REWRITE.equals(scenario);
            ShReportGraphNodes.ChapterWriter writer = (st, ch, req) -> {
                sleepQuiet(280);
                int n = writes.incrementAndGet();
                if (failFirst && n <= 6) {
                    return "过短";
                }
                return goodChapter(st.lymc(), ch);
            };
            ShReportGraphNodes nodes = new ShReportGraphNodes(writer, qualityChecker, new ShReportGraphNodes.SideEffects() {
            });
            nodes.setTraceStore(traceStore);
            CompiledGraph<ShReportGraphState> compiled = ShReportGraphRunner.compile(nodes);

            List<Integer> targets = new ArrayList<>();
            if (full) {
                for (int i = 1; i <= 6; i++) {
                    targets.add(i);
                }
            } else {
                targets.add(3);
            }
            Map<String, Object> init = ShReportGraphState.initial(
                    batchNo, "DEMO", "演示流域", "", null, "demo", targets, full, 2);
            RunnableConfig config = RunnableConfig.builder()
                    .threadId(batchNo)
                    .addMetadata(ShReportGraphNodes.META_LISTENER, new ShReportGenerateService.ProgressListener() {
                        @Override
                        public void onGraphNode(String node, String phase, Map<String, Object> snapshot) {
                            Map<String, Object> payload = snapshot == null ? new HashMap<>() : new HashMap<>(snapshot);
                            payload.put("batchNo", batchNo);
                            send(emitter, lock, "node", Map.of(
                                    "node", ShReportGraphTraceStore.normalizeNode(node),
                                    "phase", phase == null ? "leave" : phase,
                                    "trace", traceStore.get(batchNo)));
                            if ("enter".equals(phase)) {
                                sleepQuiet(160);
                            }
                        }
                    })
                    .build();

            ShReportGraphState last = null;
            for (var item : compiled.stream(init, config)) {
                last = item.state();
            }
            boolean ok = last != null && last.success();
            traceStore.finish(batchNo, ok, last != null && last.cancelled());
            send(emitter, lock, "trace", traceStore.get(batchNo));
            send(emitter, lock, "done", Map.of("batchNo", batchNo, "success", ok));
        } catch (Exception e) {
            log.warn("图编排 Demo SSE 失败", e);
            send(emitter, lock, "error", e.getMessage() == null ? "demo failed" : e.getMessage());
            try {
                traceStore.finish(batchNo, false, false);
            } catch (Exception ignore) {
            }
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
        }
    }

    private static void send(SseEmitter emitter, Object lock, String event, Object data) {
        String payload = data instanceof String s ? s : JSON.toJSONString(data);
        synchronized (lock) {
            try {
                emitter.send(SseEmitter.event().name(event).data(payload));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String goodChapter(String lymc, int chapterNo) {
        StringBuilder sb = new StringBuilder();
        sb.append(lymc == null ? "演示流域" : lymc).append("第").append(chapterNo).append("章。");
        while (sb.length() < 260) {
            sb.append("本章节内容经过现场调查与数据核对，结论可靠。");
        }
        sb.append("\n\n| 项目 | 数值 |\n| --- | --- |\n| 示例 | 1 |\n");
        return sb.toString();
    }
}
