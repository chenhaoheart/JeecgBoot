package org.jeecg.modules.airag.shreport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节规则质检器测试。
 */
class ShReportQualityCheckerTest {

    private ShReportQualityChecker checker;

    @BeforeEach
    void setUp() {
        checker = new ShReportQualityChecker();
        ReflectionTestUtils.setField(checker, "minLength", 200);
    }

    private String longText(String prefix) {
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < 260) {
            sb.append("本章节内容经过现场调查与数据核对，结论可靠。");
        }
        return sb.toString();
    }

    @Test
    void goodContentWithTableHasNoBlockingIssues() {
        String content = longText("甲流域防治对象调查成果。") + "\n\n"
                + "| 类型 | 数量 | 人口 |\n| --- | --- | --- |\n| 村落 | 10 | 320 |\n";
        List<ShReportQcIssue> issues = checker.check(content, 3, "甲流域");
        assertFalse(ShReportQualityChecker.hasBlocking(issues));
    }

    @Test
    void placeholderAndShortContentAreBlocking() {
        List<ShReportQcIssue> issues = checker.check("【系统默认内容】本章生成异常：xxx", 1, "甲流域");
        assertTrue(ShReportQualityChecker.hasBlocking(issues));
        assertTrue(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_PLACEHOLDER.equals(i.getType())));
        assertTrue(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_SHORT.equals(i.getType())));
    }

    @Test
    void placeholderMarkersAreBlocked() {
        String content = longText("甲流域概况。") + "具体人口数据（待补充），XX流域面积TBD。";
        List<ShReportQcIssue> issues = checker.check(content, 1, "甲流域");
        assertTrue(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_PLACEHOLDER.equals(i.getType())));
    }

    @Test
    void advisoryProseWithDaiBuChongIsNotPlaceholder() {
        // 第五章历史灾害常见表述：调查库无历史灾害记录，正式行文说明「待补充资料后再统计」，
        // 不判占位符，否则模型按「不得虚构数据」的提示词永远写不出能过质检的章节
        String content = longText("历史山洪灾害情况。")
                + "本次未归集到有效历史灾害记录，相关量化统计、等级评定及规律分析需待补充合规调查资料后完成。";
        List<ShReportQcIssue> issues = checker.check(content, 5, "甲流域");
        assertFalse(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_PLACEHOLDER.equals(i.getType())));
    }

    @Test
    void standaloneDaiBuChongInTableCellIsHintNotBlocking() {
        // 数据包里没有该章数据时（如第五章历史灾害台账未提供），模型如实写「| 待补充 | — |」是正确行为，
        // 判 BLOCK 会让 rewrite 反复重写同一份内容直到耗尽重试，整章直接失败
        String content = longText("历史山洪灾害情况。") + "\n\n"
                + "| 统计年代 | 灾害起数 |\n| --- | --- |\n| 待补充 | — |\n";
        List<ShReportQcIssue> issues = checker.check(content, 5, "甲流域");
        assertFalse(ShReportQualityChecker.hasBlocking(issues));
        assertTrue(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_PLACEHOLDER.equals(i.getType())));
    }

    @Test
    void standaloneDaiBuChongLineIsHintNotBlocking() {
        String content = longText("甲流域概况。") + "\n待填写\n后续内容见下节。";
        List<ShReportQcIssue> issues = checker.check(content, 1, "甲流域");
        assertFalse(ShReportQualityChecker.hasBlocking(issues));
        assertTrue(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_PLACEHOLDER.equals(i.getType())));
    }

    @Test
    void bracketedOrXxxPrefixedDaiBuChongIsStillBlocking() {
        // 括号包裹 / XX 前缀属于明确的偷懒占位，仍需阻断
        assertTrue(ShReportQualityChecker.hasBlocking(
                checker.check(longText("甲流域概况。") + "人口数据（待补充）。", 1, "甲流域")));
        assertTrue(ShReportQualityChecker.hasBlocking(
                checker.check(longText("甲流域概况。") + "XX待补充面积。", 1, "甲流域")));
    }

    @Test
    void malformedTableIsBlocking() {
        // 缺少分隔行
        String bad1 = longText("甲流域") + "\n| 类型 | 数量 |\n| 村落 | 10 |\n";
        assertTrue(ShReportQualityChecker.hasBlocking(checker.check(bad1, 3, "甲流域")));

        // 列数不一致
        String bad2 = longText("甲流域") + "\n| 类型 | 数量 |\n| --- | --- |\n| 村落 | 10 | 20 |\n";
        assertTrue(ShReportQualityChecker.hasBlocking(checker.check(bad2, 3, "甲流域")));
    }

    @Test
    void percentageOver100IsHintNotBlocking() {
        String content = longText("甲流域调查成果。") + "占比约120%。\n"
                + "| 类型 | 数量 |\n| --- | --- |\n| 村落 | 1 |\n";
        List<ShReportQcIssue> issues = checker.check(content, 3, "甲流域");
        assertFalse(ShReportQualityChecker.hasBlocking(issues));
        assertTrue(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_NUMBER.equals(i.getType())));
    }

    @Test
    void missingRequiredTableIsHint() {
        // 第 4 章要求表格，给足字数但不给表格
        List<ShReportQcIssue> issues = checker.check(longText("甲流域影响分析。"), 4, "甲流域");
        assertTrue(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_REQUIRED_TABLE.equals(i.getType())));
    }

    @Test
    void missingWatershedMentionIsHint() {
        List<ShReportQcIssue> issues = checker.check(longText("某区域调查报告内容。"), 2, "甲流域");
        assertTrue(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_WATERSHED.equals(i.getType())));
    }

    @Test
    void promptLabelLeakIsBlocking() {
        String content = longText("甲流域概况。同类流域一期调查成果范文如下。");
        List<ShReportQcIssue> issues = checker.check(content, 1, "甲流域");
        assertTrue(ShReportQualityChecker.hasBlocking(issues));
    }

    @Test
    void missingChapterKeywordsGivesStyleHint() {
        // 第四章不含 壅水/漫流/溃决 任一关键词 -> STYLE HINT
        String content = longText("甲流域影响分析。") + "\n\n"
                + "| 对象 | 水深 |\n| --- | --- |\n| 村落 | 1.2 |\n";
        List<ShReportQcIssue> issues = checker.check(content, 4, "甲流域");
        assertTrue(issues.stream().anyMatch(i -> ShReportQcIssue.TYPE_STYLE.equals(i.getType())));
        // 含关键词时无 STYLE 提示
        String ok = longText("甲流域影响分析，壅水计算如下。") + "\n\n"
                + "| 对象 | 水深 |\n| --- | --- |\n| 村落 | 1.2 |\n";
        List<ShReportQcIssue> okIssues = checker.check(ok, 4, "甲流域");
        assertFalse(okIssues.stream().anyMatch(i -> ShReportQcIssue.TYPE_STYLE.equals(i.getType())));
    }

    @Test
    void missingChapterKeywordsNullForUnknownChapter() {
        assertNull(ShReportQualityChecker.missingChapterKeywords("任意内容", 0));
        assertNull(ShReportQualityChecker.missingChapterKeywords("任意内容", 9));
        assertNull(ShReportQualityChecker.missingChapterKeywords("流域面积概况", 1));
    }
}