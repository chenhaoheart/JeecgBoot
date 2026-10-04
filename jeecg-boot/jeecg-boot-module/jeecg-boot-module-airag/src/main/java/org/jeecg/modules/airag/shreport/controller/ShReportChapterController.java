package org.jeecg.modules.airag.shreport.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.airag.shreport.*;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.jeecg.modules.airag.shreport.file.ShFileObjectService;
import org.jeecg.modules.airag.shreport.mapper.ShReportPgMapper;
import org.jeecg.modules.airag.shreport.service.ShReportGenerateService;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 山洪报告：一流域一报告。查询、审核、重生成、合并导出。
 * 实际生成走 {@link ShReportGenerateService}。
 */
@Slf4j
@Tag(name = "山洪报告-一流域一报告")
@RestController
@RequestMapping("/sh/report/chapter")
public class ShReportChapterController {

    @Autowired
    private ShReportPgQueryService pgQuery;

    @Autowired
    private ShReportPgMapper pgMapper;

    @Autowired
    private ShReportGenerateService generateService;

    @Autowired
    private ShReportConcurrencyLimiter concurrencyLimiter;

    @Autowired
    private ShReportBatchLock batchLock;

    @Autowired
    private ShFileObjectService fileObjectService;

    /** 后台任务等待并发额度的最长时间 */
    private static final long TASK_WAIT_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @GetMapping("/watersheds")
    public Result<List<Map<String, Object>>> watersheds(@RequestParam(required = false) String batchNo,
                                                        @RequestParam(required = false) String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return Result.OK(generateService.searchWatersheds(keyword));
        }
        List<Map<String, Object>> rows = pgMapper.selectWatershedObjectCounts();
        List<Map<String, Object>> progress = batchNo == null || batchNo.isEmpty()
                ? new ArrayList<>()
                : pgQuery.queryForList(
                "SELECT lybm, COUNT(*) AS 章节数, SUM(CASE WHEN status='已通过' THEN 1 ELSE 0 END) AS 已通过 " +
                        "FROM sh_report_chapter WHERE batch_no=? GROUP BY lybm", batchNo);
        for (Map<String, Object> r : rows) {
            r.put("已生成章节数", 0);
            r.put("已通过章节数", 0);
            for (Map<String, Object> p : progress) {
                if (p.get("lybm") != null && p.get("lybm").equals(r.get("wscd"))) {
                    r.put("已生成章节数", ((Number) p.get("章节数")).intValue());
                    r.put("已通过章节数", ((Number) p.get("已通过")).intValue());
                }
            }
        }
        return Result.OK(rows);
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list(@RequestParam String batchNo,
                                                  @RequestParam(required = false) String lybm) {
        return Result.OK(generateService.listChapters(batchNo, lybm));
    }

    /** 章节自动质检问题：BLOCK 阻断 / HINT 提示（含跨章一致性） */
    @GetMapping("/qc/issues")
    public Result<List<Map<String, Object>>> qcIssues(@RequestParam String batchNo,
                                                      @RequestParam(required = false) String lybm) {
        return Result.OK(generateService.listQcIssues(batchNo, lybm));
    }

    @PostMapping("/save")
    public Result<String> save(@RequestBody Map<String, Object> body) {
        String batchNo = (String) body.get("batchNo");
        String lybm = (String) body.get("lybm");
        String lymc = (String) body.get("lymc");
        int chapterNo = Integer.parseInt(String.valueOf(body.get("chapterNo")));
        String content = (String) body.get("content");
        if (batchNo == null || batchNo.isEmpty() || lybm == null || lybm.isEmpty()) {
            return Result.error("batchNo / lybm 不能为空");
        }
        generateService.saveChapter(batchNo, lybm, lymc, chapterNo, content, ShReportConstants.STATUS_PENDING, null);
        return Result.OK("章节已保存，状态：待审核");
    }

    @PostMapping("/audit")
    public Result<String> audit(@RequestBody Map<String, Object> body) {
        String id = (String) body.get("id");
        String status = (String) body.get("status");
        String auditNote = (String) body.get("auditNote");
        if (!ShReportConstants.STATUS_PASS.equals(status) && !ShReportConstants.STATUS_REJECT.equals(status)) {
            return Result.error("status 仅允许：已通过 / 已驳回");
        }
        int n = pgQuery.update("UPDATE sh_report_chapter SET status=?, audit_note=?, update_time=NOW() WHERE id=?",
                status, auditNote, id);
        return n > 0 ? Result.OK("审核完成") : Result.error("章节不存在");
    }

    @PostMapping("/regenerate")
    public Result<String> regenerate(@RequestBody Map<String, Object> body) {
        String batchNo = (String) body.get("batchNo");
        String lybm = (String) body.get("lybm");
        String lymc = (String) body.get("lymc");
        int chapterNo = Integer.parseInt(String.valueOf(body.get("chapterNo")));
        if (batchNo == null || batchNo.isEmpty() || lybm == null || lybm.isEmpty()) {
            return Result.error("batchNo / lybm 不能为空");
        }
        if (chapterNo < 1 || chapterNo > ShReportConstants.CHAPTER_COUNT) {
            return Result.error("章节号必须在 1-7 之间");
        }
        String auditNote = (String) body.get("auditNote");
        if (auditNote == null || auditNote.isEmpty()) {
            List<String> notes = pgQuery.queryForList(
                    "SELECT audit_note FROM sh_report_chapter WHERE batch_no=? AND lybm=? AND chapter_no=? ORDER BY update_time DESC LIMIT 1",
                    String.class, batchNo, lybm, chapterNo);
            auditNote = notes.isEmpty() ? null : notes.get(0);
        }
        if (lymc == null || lymc.isEmpty()) {
            lymc = generateService.queryWatershedName(lybm);
        }
        // 页面模型选择：透传选中的 modelId，未选择时为 null（走基础流程模型）
        String modelId = (String) body.get("modelId");
        // 同批次+流域已有生成任务（对话整份生成 / 另一个页面的重生成）时直接拒绝，避免章节互相覆盖
        if (batchLock.isLocked(batchNo, lybm)) {
            return Result.error("该批次该流域已有生成任务正在进行，请等待完成后再重新生成。");
        }
        final String bNo = batchNo;
        final String fLybm = lybm;
        final String fLymc = lymc == null ? lybm : lymc;
        final int chNo = chapterNo;
        final String note = auditNote;
        final String fModelId = modelId;
        worker.submit(() -> {
            // 并发闸门：排队等待额度，避免与对话入口的任务叠加把模型打满
            if (!concurrencyLimiter.tryStartTask(TASK_WAIT_MILLIS)) {
                generateService.saveChapter(bNo, fLybm, fLymc, chNo, "",
                        ShReportConstants.STATUS_FAILED, concurrencyLimiter.busyMessage(),
                        concurrencyLimiter.busyMessage(), null);
                return;
            }
            try {
                generateService.generateChapter(bNo, fLybm, fLymc, chNo, note, null, fModelId, null);
            } catch (ShReportBatchBusyException e) {
                // 预检后被并发抢锁属正常竞争：只记日志，不能用失败占位覆盖已有章节内容
                log.warn("重新生成被拒（批次生成中）batch={} lybm={} chapter={}: {}", bNo, fLybm, chNo, e.getMessage());
            } catch (Exception e) {
                // 线程池会吞掉未捕获异常，章节将永远停在「生成中」，这里兜底标记失败并落日志
                log.error("重新生成章节失败 batch={} lybm={} chapter={}", bNo, fLybm, chNo, e);
                generateService.saveChapter(bNo, fLybm, fLymc, chNo, "",
                        ShReportConstants.STATUS_FAILED, "重新生成失败：" + e.getMessage(),
                        "重新生成失败：" + e.getMessage(), null);
            } finally {
                concurrencyLimiter.finishTask();
            }
        });
        return Result.OK("重新生成已触发，稍后刷新查看（批次：" + bNo + "，流域：" + fLymc + "，第" + chNo + "章）");
    }

    @PostMapping("/generateAll")
    public Result<Map<String, Object>> generateAll(@RequestBody Map<String, Object> body) {
        String batchNo = (String) body.get("batchNo");
        if (batchNo == null || batchNo.isEmpty()) {
            return Result.error("batchNo 不能为空");
        }
        List<String> wscds = (List<String>) body.get("wscds");
        List<Map<String, Object>> ws = pgMapper.selectWatershedsByCodes(wscds);
        String bNo = batchNo;
        for (Map<String, Object> w : ws) {
            final String lybm = String.valueOf(w.get("wscd"));
            final String lymc = ShReportPgQueryService.displayName(w.get("wsnm"), w.get("alias_name"));
            worker.submit(() -> {
                if (!concurrencyLimiter.tryStartTask(TASK_WAIT_MILLIS)) {
                    log.error("批量生成失败（并发已满）batch={} lybm={}", bNo, lybm);
                    return;
                }
                try {
                    generateService.generateAll(bNo, lybm, lymc, null, null);
                } catch (Exception e) {
                    log.error("批量生成失败 batch={} lybm={}", bNo, lybm, e);
                } finally {
                    concurrencyLimiter.finishTask();
                }
            });
        }
        Map<String, Object> out = new HashMap<>();
        out.put("流域数", ws.size());
        out.put("任务总数", ws.size() * ShReportConstants.CHAPTER_COUNT);
        out.put("批次", bNo);
        out.put("提示", "任务已入队（每流域 1-6 章并行，第 7 章汇总），可用 /watersheds?batchNo= 查看进度");
        return Result.OK(out);
    }

    @GetMapping("/download")
    public void download(@RequestParam String batchNo, @RequestParam String lybm,
                         @RequestParam(required = false) String chapterNos,
                         HttpServletResponse response) throws Exception {
        List<Map<String, Object>> rows = pgQuery.queryForList(
                "SELECT chapter_no, chapter_name, content, status, lymc FROM sh_report_chapter WHERE batch_no=? AND lybm=? ORDER BY chapter_no",
                batchNo, lybm);
        List<Map<String, Object>> exportRows = ReportWordBuilder.selectExportChapters(rows, chapterNos);
        if (exportRows.isEmpty()) {
            response.sendError(400, "没有可导出的章节内容（请先生成，生成中的章节会跳过）");
            return;
        }
        String lymc = exportRows.get(0).get("lymc") == null
                ? lybm : String.valueOf(exportRows.get(0).get("lymc"));
        String suffix = sanitize(lymc);
        if (exportRows.size() < rows.size() || !ReportWordBuilder.parseChapterNos(chapterNos).isEmpty()) {
            StringBuilder nos = new StringBuilder();
            for (Map<String, Object> r : exportRows) {
                if (nos.length() > 0) {
                    nos.append('-');
                }
                nos.append(((Number) r.get("chapter_no")).intValue());
            }
            suffix = suffix + "_第" + nos + "章";
        }
        String uploadPath = System.getProperty("jeecg.path.upload", "./upFiles");
        File docx = ReportWordBuilder.build(exportRows, uploadPath, suffix,
                ShReportCellPhotos.resolver(fileObjectService));
        response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                java.net.URLEncoder.encode(docx.getName(), "UTF-8").replace("+", "%20"));
        Files.copy(docx.toPath(), response.getOutputStream());
        response.getOutputStream().flush();
    }

    private static String sanitize(String name) {
        return name == null ? "report" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
