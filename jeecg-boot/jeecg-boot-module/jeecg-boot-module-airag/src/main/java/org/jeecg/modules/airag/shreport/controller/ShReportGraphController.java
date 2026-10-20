package org.jeecg.modules.airag.shreport.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.shreport.graph.ShReportGraphTopology;
import org.jeecg.modules.airag.shreport.component.ShReportGraphTraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/schema")
    public Result<Map<String, Object>> schema() {
        return Result.OK(ShReportGraphTopology.schema());
    }

    @GetMapping("/trace")
    public Result<Map<String, Object>> trace(@RequestParam(required = false) String batchNo) {
        return Result.OK(traceStore.get(batchNo));
    }
}
