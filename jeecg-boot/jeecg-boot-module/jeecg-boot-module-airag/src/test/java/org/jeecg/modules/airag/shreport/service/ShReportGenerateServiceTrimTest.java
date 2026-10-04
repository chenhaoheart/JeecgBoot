package org.jeecg.modules.airag.shreport.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShReportGenerateServiceTrimTest {

    @Test
    void promotesChapterOneTitleToLevelOne() {
        String raw = "## 一、流域概况\n### （一）防治对象总量与人口分布概述\n正文";
        String out = ShReportGenerateService.trimToChapter(raw, 1);
        assertTrue(out.startsWith("# 一、流域概况\n"));
        assertTrue(out.contains("### （一）防治对象总量与人口分布概述"));
    }

    @Test
    void leavesExistingLevelOneTitleUntouched() {
        String raw = "# 三、外业调查成果\n## 3.1 涉水工程调查成果";
        assertEquals(raw, ShReportGenerateService.trimToChapter(raw, 3));
    }
}
