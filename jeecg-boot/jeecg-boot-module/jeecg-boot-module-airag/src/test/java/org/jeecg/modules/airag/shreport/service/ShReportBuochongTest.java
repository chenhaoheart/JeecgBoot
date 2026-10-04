package org.jeecg.modules.airag.shreport.service;

import org.jeecg.modules.airag.shreport.service.ShReportKnowledgeService.KbResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流程入参「补充资料」组装与失败原因提取的纯函数单测。
 */
class ShReportBuochongTest {

    @Test
    void emptyPartsYieldNull() {
        assertNull(ShReportGenerateService.buildBuochong(1, null, null, null, null, null));
    }

    @Test
    void requirementAndCh4ParamsAssembled() {
        String s = ShReportGenerateService.buildBuochong(4, "重点分析壅水", "设计洪峰流量 Q=30m³/s", null, null, null);
        assertTrue(s.contains("用户要求（请重点落实）：重点分析壅水"));
        assertTrue(s.contains("Q=30m³/s"));
    }

    @Test
    void knowledgeExamplesAndSourcesCollected() {
        KbResult kb = new KbResult("一期成果范文正文", List.of(Map.of("docName", "一期范文A")));
        List<String> sources = new ArrayList<>();
        String s = ShReportGenerateService.buildBuochong(1, null, null, kb, sources, null);
        assertTrue(s.contains("一期成果范文正文"));
        assertEquals(List.of("一期范文A"), sources);
    }

    @Test
    void prevDraftAppendedWithRevisionHint() {
        String s = ShReportGenerateService.buildBuochong(2, null, null, null, null, "上一稿正文");
        assertTrue(s.contains("【上一稿】"));
        assertTrue(s.contains("上一稿正文"));
    }

    @Test
    void blankPrevDraftIgnored() {
        assertNull(ShReportGenerateService.buildBuochong(2, null, null, null, null, "  "));
    }

    @Test
    void placeholderReasonExtraction() {
        assertEquals("本章生成异常：boom",
                ShReportGenerateService.placeholderReason("【系统默认内容】本章生成异常：boom"));
        assertEquals("生成失败", ShReportGenerateService.placeholderReason("【系统默认内容】"));
        assertNull(ShReportGenerateService.placeholderReason("正式章节内容"));
        assertNull(ShReportGenerateService.placeholderReason(null));
    }
}
