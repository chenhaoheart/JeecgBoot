package org.jeecg.modules.airag.shreport;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨章关键指标一致性校验器测试。
 */
class ShReportConsistencyCheckerTest {

    private String longPad(String text) {
        return text + "\n（本章其余调查内容略，数据经现场核对。）".repeat(8);
    }

    @Test
    void noIssueWhenValuesConsistentAcrossChapters() {
        Map<Integer, String> chapters = new LinkedHashMap<>();
        chapters.put(1, longPad("甲流域流域面积 12.5km²，沟口高程 405.0m。"));
        chapters.put(4, longPad("按流域面积 12.5km² 计算洪水影响。"));
        List<ShReportQcIssue> issues = ShReportConsistencyChecker.check(chapters, Map.of());
        assertTrue(issues.stream().noneMatch(i -> ShReportQcIssue.TYPE_CONSISTENCY.equals(i.getType())
                && i.getMessage().contains("流域面积")));
    }

    @Test
    void contradictionAgainstCanonicalValueIsReported() {
        Map<Integer, String> chapters = new LinkedHashMap<>();
        chapters.put(1, longPad("甲流域流域面积 12.5km²。"));
        chapters.put(4, longPad("本流域流域面积 18.0km²，差异明显。"));
        List<ShReportQcIssue> issues = ShReportConsistencyChecker.check(chapters, Map.of("AREA", "12.5"));
        assertTrue(issues.stream().anyMatch(i -> i.getMessage().contains("流域面积") && i.getMessage().contains("第4章")));
        // 一致性问题只提示，不阻断
        assertFalse(ShReportQualityChecker.hasBlocking(issues));
    }

    @Test
    void contradictionAcrossChaptersWithoutCanonicalIsReported() {
        Map<Integer, String> chapters = new LinkedHashMap<>();
        chapters.put(1, longPad("甲流域流域面积 12.5km²。"));
        chapters.put(6, longPad("防治分区基于流域面积 20.0km²。"));
        List<ShReportQcIssue> issues = ShReportConsistencyChecker.check(chapters, Map.of());
        assertTrue(issues.stream().anyMatch(i -> i.getMessage().contains("流域面积")));
    }

    @Test
    void deviatingChaptersAreExposedAgainstCanonical() {
        Map<Integer, String> chapters = new LinkedHashMap<>();
        chapters.put(1, longPad("甲流域流域面积 12.5km²。"));
        chapters.put(4, longPad("本流域流域面积 18.0km²，差异明显。"));
        List<ShReportQcIssue> issues = ShReportConsistencyChecker.check(chapters, Map.of("AREA", "12.5"));
        ShReportQcIssue area = issues.stream()
                .filter(i -> i.getMessage().contains("流域面积"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(4), area.chapterNos());
    }

    @Test
    void multiChapterGroupsExposeStructuredChapterNos() {
        // 回归：多章并列（（第1、3章））曾导致图内反解章节号失败、矛盾被静默忽略
        Map<Integer, String> chapters = new LinkedHashMap<>();
        chapters.put(1, longPad("甲流域流域面积 12.5km²。"));
        chapters.put(3, longPad("甲流域流域面积 12.5km²。"));
        chapters.put(2, longPad("防治分区按流域面积 20.0km² 划分。"));
        chapters.put(4, longPad("防治分区按流域面积 20.0km² 划分。"));
        List<ShReportQcIssue> issues = ShReportConsistencyChecker.check(chapters, Map.of());
        ShReportQcIssue area = issues.stream()
                .filter(i -> i.getMessage().contains("流域面积"))
                .findFirst()
                .orElseThrow();
        // 以最早出现的第 1 章所在分组为基准，其余章需回写
        assertEquals(List.of(2, 4), area.chapterNos());
    }

    @Test
    void populationExactMismatchDetected() {
        Map<Integer, String> chapters = new LinkedHashMap<>();
        chapters.put(1, longPad("受威胁人口 1200 人。"));
        chapters.put(7, longPad("全流域受威胁人口 1500 人。"));
        List<ShReportQcIssue> issues = ShReportConsistencyChecker.check(chapters, Map.of("POPULATION", "1200"));
        assertTrue(issues.stream().anyMatch(i -> i.getMessage().contains("受威胁人口")));
    }

    @Test
    void subCountsDoNotTriggerConsistencyIssue() {
        // 回归：各章的分乡镇/分类型人口拆解值（≤ 基准总数）不是矛盾，
        // 曾被误判为不一致并触发整图反复回写重新生成
        Map<Integer, String> chapters = new LinkedHashMap<>();
        chapters.put(1, longPad("受威胁总人口 69208 人。"));
        chapters.put(2, longPad("东江镇受威胁人口 37615 人，城关镇受威胁人口 22761 人。"));
        chapters.put(3, longPad("村落受威胁人口 68462 人，企业受威胁人口 537 人。"));
        List<ShReportQcIssue> issues = ShReportConsistencyChecker.check(chapters, Map.of("POPULATION", "69208"));
        assertTrue(issues.stream().noneMatch(i -> i.getMessage().contains("受威胁人口")));
    }

    @Test
    void populationOverTotalStillDetected() {
        Map<Integer, String> chapters = new LinkedHashMap<>();
        chapters.put(1, longPad("受威胁总人口 69208 人。"));
        chapters.put(7, longPad("全流域受威胁人口 90000 人。"));
        List<ShReportQcIssue> issues = ShReportConsistencyChecker.check(chapters, Map.of("POPULATION", "69208"));
        assertTrue(issues.stream().anyMatch(i -> i.getMessage().contains("受威胁人口")));
    }

    @Test
    void emptyChaptersYieldNoIssues() {
        assertTrue(ShReportConsistencyChecker.check(new LinkedHashMap<>(), Map.of()).isEmpty());
    }
}
