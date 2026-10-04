package org.jeecg.modules.airag.shreport;

import com.alibaba.fastjson.JSONObject;
import org.jeecg.modules.airag.shreport.service.ShReportAgentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图 JSON Schema 校验器测试。
 */
class ShReportIntentValidatorTest {

    @Test
    void validGenerateAllPassesAndFieldsNormalized() {
        JSONObject raw = JSON("{'action':'generate_all','chapterNo':null,"
                + "'wsmc':'  甲流域  ','requirement':'  语气正式  ','reply':' 好的 '}");
        JSONObject out = ShReportIntentValidator.validate(raw);
        assertEquals("GENERATE_ALL", out.getString("action"));
        assertEquals("甲流域", out.getString("wsmc"));
        assertEquals("语气正式", out.getString("requirement"));
        assertNull(out.get("chapterNo"));
    }

    @Test
    void generateChapterRequiresChapterNoInRange() {
        JSONObject noChapter = JSON("{'action':'GENERATE_CHAPTER'}");
        assertThrows(IllegalArgumentException.class, () -> ShReportIntentValidator.validate(noChapter));

        JSONObject outOfRange = JSON("{'action':'GENERATE_CHAPTER','chapterNo':8}");
        assertThrows(IllegalArgumentException.class, () -> ShReportIntentValidator.validate(outOfRange));

        JSONObject zero = JSON("{'action':'GENERATE_CHAPTER','chapterNo':0}");
        assertThrows(IllegalArgumentException.class, () -> ShReportIntentValidator.validate(zero));
    }

    @Test
    void validChapterNoAccepted() {
        JSONObject out = ShReportIntentValidator.validate(JSON("{'action':'GENERATE_CHAPTER','chapterNo':3}"));
        assertEquals(3, out.getIntValue("chapterNo"));
    }

    @Test
    void unknownActionAndEmptyJsonRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ShReportIntentValidator.validate(JSON("{'action':'DELETE_ALL'}")));
        assertThrows(IllegalArgumentException.class, () -> ShReportIntentValidator.validate(new JSONObject()));
        assertThrows(IllegalArgumentException.class, () -> ShReportIntentValidator.validate(null));
    }

    @Test
    void prepareOutlineAndOtherActionsAccepted() {
        assertTrue(ShReportIntentValidator.validate(JSON("{'action':'PREPARE_OUTLINE'}"))
                .getString("action").equals("PREPARE_OUTLINE"));
        assertTrue(ShReportIntentValidator.validate(JSON("{'action':'QUERY_STATUS'}"))
                .getString("action").equals("QUERY_STATUS"));
        // 非章节动作上带非法 chapterNo 时直接忽略，不报错
        JSONObject out = ShReportIntentValidator.validate(JSON("{'action':'CHAT','chapterNo':99}"));
        assertNull(out.get("chapterNo"));
    }

    @Test
    void confirmLybmRetained() {
        JSONObject out = ShReportIntentValidator.validate(
                JSON("{'action':'CHAT','confirmLybm':'W1','confirmLymc':'甲流域'}"));
        assertEquals("W1", out.getString("confirmLybm"));
        assertEquals("甲流域", out.getString("confirmLymc"));
    }

    @Test
    void queryDataAcceptedWithFields() {
        JSONObject out = ShReportIntentValidator.validate(
                JSON("{'action':'QUERY_DATA','dataQuery':' by_type ','scope':'bound',"
                        + "'county':' 甲县 ','town':null,'ftp':'居民点','keyword':'河边'}"));
        assertEquals("QUERY_DATA", out.getString("action"));
        assertEquals("by_type", out.getString("dataQuery"));
        assertEquals("bound", out.getString("scope"));
        assertEquals("甲县", out.getString("county"));
        assertNull(out.get("town"));
        assertEquals("居民点", out.getString("ftp"));
        assertEquals("河边", out.getString("keyword"));
    }

    @Test
    void queryDataDefaultsToOverviewWhenMissing() {
        JSONObject out = ShReportIntentValidator.validate(JSON("{'action':'QUERY_DATA'}"));
        assertEquals("overview", out.getString("dataQuery"));
    }

    @Test
    void queryDataRejectsUnknownQueryAndScope() {
        assertThrows(IllegalArgumentException.class,
                () -> ShReportIntentValidator.validate(JSON("{'action':'QUERY_DATA','dataQuery':'DROP TABLE'}")));
        assertThrows(IllegalArgumentException.class,
                () -> ShReportIntentValidator.validate(JSON("{'action':'QUERY_DATA','dataQuery':'risk','scope':'mine'}")));
    }

    @Test
    void queryKbAccepted() {
        JSONObject out = ShReportIntentValidator.validate(JSON("{'action':'QUERY_KB','wsmc':'大崖沟流域'}"));
        assertEquals("QUERY_KB", out.getString("action"));
        assertEquals("大崖沟流域", out.getString("wsmc"));
    }

    @Test
    void statsRuleFallbackClassifiesQuestions() {
        ShReportAgentService service = new ShReportAgentService();
        assertEquals("QUERY_DATA", service.ruleBasedIntent("当前有多少小流域").getString("action"));
        assertEquals("overview", service.ruleBasedIntent("当前有多少小流域").getString("dataQuery"));
        JSONObject bound = service.ruleBasedIntent("当前流域有哪些风险隐患");
        assertEquals("QUERY_DATA", bound.getString("action"));
        assertEquals("risk", bound.getString("dataQuery"));
        assertEquals("bound", bound.getString("scope"));
        // 含章节号的消息不能被统计兜底劫持
        assertEquals("GENERATE_CHAPTER", service.ruleBasedIntent("第三章修改一下，多统计一些数据").getString("action"));
        // 普通闲聊不误判
        assertEquals("CHAT", service.ruleBasedIntent("你好呀").getString("action"));
        assertEquals("QUERY_KB", service.ruleBasedIntent("大崖沟成果报告摘要写了什么").getString("action"));
        assertEquals("QUERY_DATA", service.ruleBasedIntent("当前流域有哪些风险隐患").getString("action"));
    }

    private static JSONObject JSON(String s) {
        return JSONObject.parseObject(s.replace('\'', '"'));
    }
}
