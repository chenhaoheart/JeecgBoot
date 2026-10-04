package org.jeecg.modules.airag.shreport;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.shreport.service.ShReportGenerateService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShReportGenerateServiceFlowOutputTest {

    @Test
    void readsNestedOutputTextFromResult() {
        Result<Map<String, Object>> ok = Result.OK(Map.of("outputs", Map.of("outputText", "第一章正文")));
        assertEquals("第一章正文", ShReportGenerateService.extractFlowOutputText(ok));
    }

    @Test
    void readsTextFieldFromResult() {
        assertEquals("章节正文", ShReportGenerateService.extractFlowOutputText(Result.OK(Map.of("text", "章节正文"))));
    }

    @Test
    void readsPlainStringResult() {
        assertEquals("纯文本", ShReportGenerateService.extractFlowOutputText(Result.OK("纯文本")));
    }

    @Test
    void returnsNullOnErrorResult() {
        assertNull(ShReportGenerateService.extractFlowOutputText(Result.error("失败")));
    }
}
