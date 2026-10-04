package org.jeecg.modules.airag.shreport;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.flow.component.enhance.IAiRagEnhanceJava;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 山洪报告章节落库增强节点：LLM 生成章节内容后写入 sh_report_chapter，状态置「待审核」。
 * 重新生成时把旧内容写入 content_prev。
 */
@Slf4j
public abstract class ReportChapterSaveEnhance implements IAiRagEnhanceJava {

    @Autowired
    private ShReportPgQueryService pgQuery;

    protected abstract int chapterNo();

    protected abstract String chapterName();

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    @Override
    public Map<String, Object> process(Map<String, Object> inputParams) {
        String content = str(inputParams.get("text"));
        if (content.isEmpty()) {
            content = str(inputParams.get("章节内容"));
        }
        String batchNo = str(inputParams.get("batch_no"));
        if (batchNo.isEmpty()) {
            batchNo = str(inputParams.get("批次号"));
        }
        String lybm = str(inputParams.get("lybm"));
        if (lybm.isEmpty()) {
            lybm = str(inputParams.get("流域编码"));
        }
        String lymc = str(inputParams.get("lymc"));
        if (lymc.isEmpty()) {
            lymc = str(inputParams.get("流域名称"));
        }
        if (content.isEmpty() || batchNo.isEmpty()) {
            log.warn("章节落库缺少参数 batchNo={}, contentLen={}", batchNo, content.length());
            return Map.of("result", "缺少章节内容或批次号，未落库");
        }
        List<Map<String, Object>> exist = lybm.isEmpty()
                ? pgQuery.queryForList("SELECT id, content FROM sh_report_chapter WHERE batch_no=? AND chapter_no=?", batchNo, chapterNo())
                : pgQuery.queryForList("SELECT id, content FROM sh_report_chapter WHERE batch_no=? AND chapter_no=? AND (lybm=? OR lybm IS NULL)",
                batchNo, chapterNo(), lybm);
        java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
        if (!exist.isEmpty()) {
            pgQuery.update(
                    "UPDATE sh_report_chapter SET content_prev=CASE WHEN content IN ('正在生成…','') OR content LIKE '【系统默认内容】%' THEN content_prev ELSE content END, " +
                            "content=?, lymc=?, status=?, audit_note=NULL, update_time=? WHERE id=?",
                    content, lymc, ShReportConstants.STATUS_PENDING, now, exist.get(0).get("id"));
        } else {
            pgQuery.update(
                    "INSERT INTO sh_report_chapter (id, batch_no, lybm, lymc, chapter_no, chapter_name, content, content_prev, status, audit_note, create_by, create_time, update_time) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), batchNo, lybm, lymc, chapterNo(), chapterName(),
                    content, null, ShReportConstants.STATUS_PENDING, null, "airag", now, now);
        }
        log.info("章节落库完成 batch={} chapter={} contentLen={}", batchNo, chapterNo(), content.length());
        return Map.of("result", "章节已保存:" + chapterName());
    }

    @Component("reportChapterSave1")
    public static class Save1 extends ReportChapterSaveEnhance {
        protected int chapterNo() { return 1; }
        protected String chapterName() { return ShReportConstants.chapterName(1); }
    }

    @Component("reportChapterSave2")
    public static class Save2 extends ReportChapterSaveEnhance {
        protected int chapterNo() { return 2; }
        protected String chapterName() { return ShReportConstants.chapterName(2); }
    }

    @Component("reportChapterSave3")
    public static class Save3 extends ReportChapterSaveEnhance {
        protected int chapterNo() { return 3; }
        protected String chapterName() { return ShReportConstants.chapterName(3); }
    }

    @Component("reportChapterSave4")
    public static class Save4 extends ReportChapterSaveEnhance {
        protected int chapterNo() { return 4; }
        protected String chapterName() { return ShReportConstants.chapterName(4); }
    }

    @Component("reportChapterSave5")
    public static class Save5 extends ReportChapterSaveEnhance {
        protected int chapterNo() { return 5; }
        protected String chapterName() { return ShReportConstants.chapterName(5); }
    }

    @Component("reportChapterSave6")
    public static class Save6 extends ReportChapterSaveEnhance {
        protected int chapterNo() { return 6; }
        protected String chapterName() { return ShReportConstants.chapterName(6); }
    }

    @Component("reportChapterSave7")
    public static class Save7 extends ReportChapterSaveEnhance {
        protected int chapterNo() { return 7; }
        protected String chapterName() { return ShReportConstants.chapterName(7); }
    }
}
