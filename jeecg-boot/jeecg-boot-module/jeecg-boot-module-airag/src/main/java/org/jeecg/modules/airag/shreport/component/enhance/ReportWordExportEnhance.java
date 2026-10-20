package org.jeecg.modules.airag.shreport.component.enhance;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.flow.component.enhance.IAiRagEnhanceJava;
import org.jeecg.modules.airag.onemap.service.ShFileObjectService;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.jeecg.modules.airag.shreport.util.ReportWordBuilder;
import org.jeecg.modules.airag.shreport.util.ShReportCellPhotos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 山洪报告合并导出增强节点：按已有正文章节生成 Word（不要求全部审核通过），
 * 返回静态文件下载地址。挂在"山洪报告_09合并导出"流程的 enhanceJava 节点。
 */
@Slf4j
@Component("reportWordExportEnhance")
public class ReportWordExportEnhance implements IAiRagEnhanceJava {

    @Autowired
    private ShReportPgQueryService pgQuery;

    @Autowired
    private ShFileObjectService fileObjectService;

    @Override
    public Map<String, Object> process(Map<String, Object> inputParams) {
        String batchNo = inputParams.get("batch_no") == null ? "" : inputParams.get("batch_no").toString();
        if (batchNo.isEmpty()) {
            batchNo = inputParams.get("批次号") == null ? "" : inputParams.get("批次号").toString();
        }
        String lybm = inputParams.get("lybm") == null ? "" : inputParams.get("lybm").toString();
        if (lybm.isEmpty() && inputParams.get("流域编码") != null) {
            lybm = inputParams.get("流域编码").toString();
        }
        if (batchNo.isEmpty()) {
            return Map.of("result", "缺少批次号参数，无法导出");
        }
        List<Map<String, Object>> rows = lybm.isEmpty()
                ? pgQuery.queryForList(
                "SELECT chapter_no, chapter_name, content, status, lymc, lybm FROM sh_report_chapter WHERE batch_no=? ORDER BY chapter_no", batchNo)
                : pgQuery.queryForList(
                "SELECT chapter_no, chapter_name, content, status, lymc, lybm FROM sh_report_chapter WHERE batch_no=? AND lybm=? ORDER BY chapter_no",
                batchNo, lybm);
        String chapterNos = inputParams.get("chapterNos") == null ? "" : inputParams.get("chapterNos").toString();
        if (chapterNos.isEmpty() && inputParams.get("章节号") != null) {
            chapterNos = inputParams.get("章节号").toString();
        }
        List<Map<String, Object>> exportRows = ReportWordBuilder.selectExportChapters(rows, chapterNos);
        if (exportRows.isEmpty()) {
            return Map.of("result", "没有可导出的章节内容（请先生成，生成中的章节会跳过）");
        }
        try {
            String uploadPath = System.getProperty("jeecg.path.upload", "./upFiles");
            String lymc = exportRows.get(0).get("lymc") == null ? batchNo : String.valueOf(exportRows.get(0).get("lymc"));
            File docx = ReportWordBuilder.build(exportRows, uploadPath, lymc.replaceAll("[\\\\/:*?\"<>|]", "_"),
                    ShReportCellPhotos.resolver(fileObjectService));
            String url = "/sys/common/static/" + docx.getName();
            log.info("山洪报告合并导出完成 batch={} file={}", batchNo, docx.getAbsolutePath());
            return Map.of("result", "导出成功，下载地址：" + url);
        } catch (Exception e) {
            log.error("山洪报告合并导出失败 batch={}", batchNo, e);
            return Map.of("result", "导出失败：" + e.getMessage());
        }
    }
}
