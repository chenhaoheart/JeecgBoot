package org.jeecg.modules.airag.shreport;

import org.jeecg.modules.airag.shreport.service.ShReportModelVariantService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型变体流程：LLM 节点 modelId 替换与变体命名测试。
 */
class ShReportModelVariantServiceTest {

    private String design(String modelId) {
        return "{\"nodes\":["
                + "{\"id\":\"start-node\",\"type\":\"start\",\"properties\":{}},"
                + "{\"id\":\"sql1\",\"type\":\"sql\",\"properties\":{}},"
                + "{\"id\":\"llm1\",\"type\":\"llm\",\"properties\":{\"options\":{"
                + "\"model\":{\"modelId\":\"" + modelId + "\",\"model\":\"deepseek-chat\"}}}},"
                + "{\"id\":\"end-node\",\"type\":\"end\",\"properties\":{}}]}";
    }

    @Test
    void replacesLlmModelIdAndKeepsOtherNodes() {
        String out = ShReportModelVariantService.replaceLlmModelId(design("OLD-MODEL"), "NEW-MODEL");
        assertTrue(out.contains("\"modelId\":\"NEW-MODEL\""));
        assertTrue(!out.contains("OLD-MODEL"));
        // 非模型配置保持不变
        assertTrue(out.contains("\"model\":\"deepseek-chat\""));
        assertTrue(out.contains("\"type\":\"sql\""));
    }

    @Test
    void supportsFlatModelIdOption() {
        String d = "{\"nodes\":[{\"id\":\"llm1\",\"type\":\"llm\",\"properties\":{\"options\":{\"modelId\":\"OLD\"}}}]}";
        String out = ShReportModelVariantService.replaceLlmModelId(d, "NEW");
        assertTrue(out.contains("\"modelId\":\"NEW\""));
    }

    @Test
    void raisesShortLlmTimeoutAndKeepsLongerOne() {
        String shortTimeout = "{\"nodes\":[{\"id\":\"llm1\",\"type\":\"llm\",\"properties\":{\"options\":{"
                + "\"model\":{\"modelId\":\"M\",\"params\":{\"timeout\":120,\"temperature\":0.3}}}}}]}" ;
        String raised = ShReportModelVariantService.replaceLlmModelId(shortTimeout, "M");
        assertTrue(raised.contains("\"timeout\":" + ShReportModelVariantService.MIN_LLM_TIMEOUT_SECONDS));
        assertTrue(raised.contains("\"temperature\":0.3"));

        String longTimeout = "{\"nodes\":[{\"id\":\"llm1\",\"type\":\"llm\",\"properties\":{\"options\":{"
                + "\"model\":{\"modelId\":\"M\",\"params\":{\"timeout\":900}}}}}]}" ;
        String kept = ShReportModelVariantService.replaceLlmModelId(longTimeout, "M");
        assertTrue(kept.contains("\"timeout\":900"));
    }

    @Test
    void returnsNullWhenNoLlmNode() {
        String d = "{\"nodes\":[{\"id\":\"start-node\",\"type\":\"start\",\"properties\":{}}]}";
        assertNull(ShReportModelVariantService.replaceLlmModelId(d, "NEW"));
    }

    @Test
    void returnsNullOnBrokenJson() {
        assertNull(ShReportModelVariantService.replaceLlmModelId("not-json", "NEW"));
    }

    @Test
    void variantNameIsStableAndShort() {
        String base = "山洪报告_01流域概况";
        String modelId = "1897481367743143938";
        String n1 = ShReportModelVariantService.variantName(base, modelId);
        String n2 = ShReportModelVariantService.variantName(base, modelId);
        assertEquals(n1, n2);
        assertTrue(n1.startsWith(base + "#M"));
        // 仅保留 modelId 后 8 位字母数字
        assertEquals(base + "#M" + "43143938", n1);
    }

    @Test
    void detectsVariantNamesOnly() {
        assertTrue(ShReportModelVariantService.isModelVariantName("山洪报告_01流域概况#M95006466"));
        assertTrue(!ShReportModelVariantService.isModelVariantName("山洪报告_01流域概况"));
        assertTrue(!ShReportModelVariantService.isModelVariantName("山洪报告_09合并导出"));
        assertTrue(!ShReportModelVariantService.isModelVariantName("山洪灾害风险隐患调查报告自动生成"));
        assertTrue(!ShReportModelVariantService.isModelVariantName(null));
    }
}
