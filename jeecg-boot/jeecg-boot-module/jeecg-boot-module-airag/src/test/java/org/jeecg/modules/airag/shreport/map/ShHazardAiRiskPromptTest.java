package org.jeecg.modules.airag.shreport.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隐患识别系统提示词：库里有正文就用库，空则退回内置文案。
 */
class ShHazardAiRiskPromptTest {

    @Test
    void blankStoredPromptFallsBackToBuiltin() {
        assertEquals(ShHazardAiRiskService.DEFAULT_SYSTEM_PROMPT, ShHazardAiRiskService.resolvePrompt(null));
        assertEquals(ShHazardAiRiskService.DEFAULT_SYSTEM_PROMPT, ShHazardAiRiskService.resolvePrompt("  \n"));
    }

    @Test
    void storedPromptIsTrimmedAndUsedAsIs() {
        String stored = "  只写主要风险。  ";
        assertEquals("只写主要风险。", ShHazardAiRiskService.resolvePrompt(stored));
        assertTrue(ShHazardAiRiskService.DEFAULT_SYSTEM_PROMPT.contains("阻水面积比"));
    }

    @Test
    void ratioRetryOnlyWhenAnswerHasNoPhotoEstimate() {
        assertTrue(ShHazardAiRiskService.needsRatioRetry("填报阻水面积比 27.27%，照片无法估算。"));
        assertTrue(ShHazardAiRiskService.needsRatioRetry("主要风险是壅水。"));
        assertFalse(ShHazardAiRiskService.needsRatioRetry("按照片估算阻水面积比约为 35%，高于填报的 27.27%。"));
        assertFalse(ShHazardAiRiskService.needsRatioRetry("照片粗估 25%–40%。"));
    }
}
