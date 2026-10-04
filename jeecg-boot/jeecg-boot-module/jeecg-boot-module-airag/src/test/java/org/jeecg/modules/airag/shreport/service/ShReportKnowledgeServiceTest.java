package org.jeecg.modules.airag.shreport.service;

import org.jeecg.modules.airag.shreport.service.ShReportKnowledgeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShReportKnowledgeServiceTest {

    @Test
    void chapterQueryIncludesWatershedAndTopic() {
        String q = ShReportKnowledgeService.buildChapterQuery(3, "大崖沟流域");
        assertTrue(q.contains("大崖沟流域"));
        assertTrue(q.contains("风险隐患调查") || q.contains("外业"));
    }

    @Test
    void kbQuestionPattern() {
        assertTrue(ShReportKnowledgeService.looksLikeKbQuestion("大崖沟成果报告摘要写了什么"));
        assertTrue(ShReportKnowledgeService.looksLikeKbQuestion("塔子沟一期报告的建议"));
        assertFalse(ShReportKnowledgeService.looksLikeKbQuestion("当前流域有哪些风险隐患"));
        assertFalse(ShReportKnowledgeService.looksLikeKbQuestion("帮我生成整份报告"));
    }

    @Test
    void extractWatershedFromQuery() {
        assertEquals("大崖沟流域", ShReportKnowledgeService.extractWatershed("大崖沟流域 风险隐患调查"));
        assertNull(ShReportKnowledgeService.extractWatershed("你好"));
    }

    @Test
    void missHintListsWatershedsAndExamples() {
        String hint = ShReportKnowledgeService.buildMissHint(
                List.of("大崖沟流域", "塔子沟流域"), false);
        assertTrue(hint.contains("【检索未命中引导】"));
        assertTrue(hint.contains("大崖沟流域"));
        assertTrue(hint.contains("塔子沟流域"));
        assertTrue(hint.contains("大崖沟流域报告摘要写了什么？"));
        assertTrue(hint.contains("禁止只回复「知识库未收录」"));
        assertFalse(hint.contains("知识库当前不可用"));
    }

    @Test
    void watershedsDedupedFromChapterTitles() {
        List<String> names = ShReportKnowledgeService.watershedsFromTitles(List.of(
                "大崖沟流域 · 成果报告摘要",
                "大崖沟流域 · 第一章 流域概况",
                "塔子沟流域 · 第五章 成果与建议",
                "无关标题"));
        assertEquals(List.of("大崖沟流域", "塔子沟流域"), names);
    }

    @Test
    void chatQueryPrefersWatershedAndRecentTurns() {
        String q = ShReportKnowledgeService.buildChatQuery(
                "那第一章呢", "塔子沟流域", List.of("大崖沟摘要", "塔子沟呢"));
        assertTrue(q.contains("塔子沟流域"));
        assertTrue(q.contains("那第一章呢"));
        assertTrue(q.contains("塔子沟呢"));
    }

    @Test
    void metaQuestionsSkipRetrievalAndAskForWatershed() {
        assertTrue(ShReportKnowledgeService.needsMissHint("你不联网访问数据吗"));
        assertTrue(ShReportKnowledgeService.needsMissHint("介绍一下小流域"));
        assertTrue(ShReportKnowledgeService.needsMissHint("你是谁"));
        assertFalse(ShReportKnowledgeService.needsMissHint("大崖沟流域报告摘要写了什么"));
        assertFalse(ShReportKnowledgeService.needsMissHint("塔子沟有哪些风险隐患"));
    }

    @Test
    void formatHitsTruncatesAndSkipsEmpty() {
        List<Map<String, Object>> hits = List.of(
                Map.of("docName", "大崖沟 · 摘要", "content", "甲" + "文".repeat(50)),
                Map.of("docName", "空", "content", " "),
                Map.of("docName", "塔子沟 · 建议", "content", "乙建议"));
        String out = ShReportKnowledgeService.formatHits(hits, 20, 200);
        assertTrue(out.contains("大崖沟"));
        assertTrue(out.contains("塔子沟"));
        assertTrue(out.contains("…"));
        assertFalse(out.contains("来源：空"));
        assertEquals(2, out.split("---", -1).length);
    }
}
