package org.jeecg.modules.airag.shreport.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphDemoService;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphTopology;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphTraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 报告图：拓扑、运行轨迹、无模型 Demo。
 */
@Slf4j
@Tag(name = "山洪报告-编排图")
@RestController
@RequestMapping("/sh/report/graph")
public class ShReportGraphController {

    @Autowired
    private ShReportGraphTraceStore traceStore;

    @Autowired
    private ShReportGraphDemoService demoService;

    @GetMapping("/schema")
    public Result<Map<String, Object>> schema() {
        return Result.OK(ShReportGraphTopology.schema());
    }

    @GetMapping("/trace")
    public Result<Map<String, Object>> trace(@RequestParam(required = false) String batchNo) {
        return Result.OK(traceStore.get(batchNo));
    }

    /**
     * 假写作节点跑一遍图（不调 LLM、不写库）。
     * scenario=pass|rewrite|chapter
     */
    @PostMapping("/demo")
    public SseEmitter demo(@RequestParam(defaultValue = "pass") String scenario) {
        SseEmitter emitter = new SseEmitter(180_000L);
        emitter.onTimeout(() -> log.warn("报告图 Demo SSE 超时"));
        demoService.run(scenario, emitter);
        return emitter;
    }
}
