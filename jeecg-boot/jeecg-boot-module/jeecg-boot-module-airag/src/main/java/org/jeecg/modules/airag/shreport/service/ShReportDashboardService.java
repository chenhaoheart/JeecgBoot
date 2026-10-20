package org.jeecg.modules.airag.shreport.service;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.jeecg.modules.airag.onemap.service.ShReportMapService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页 / 工作台概览：报告生成、知识库、一张图要素、隐患识别。
 */
@Slf4j
@Service
public class ShReportDashboardService {

    @Autowired
    private ShReportPgQueryService pgQueryService;

    @Autowired
    private ShReportMapService mapService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report", reportStats());
        out.put("knowledge", knowledgeStats());
        out.put("map", mapService.stats());
        out.put("aiRisk", aiRiskStats());
        return out;
    }

    private Map<String, Object> reportStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportCount", 0);
        out.put("watershedCount", 0);
        out.put("chapterCount", 0);
        out.put("byStatus", List.of());
        out.put("recent", List.of());
        try {
            int full = pgQueryService.queryInt(
                    "SELECT COUNT(*) FROM ("
                            + " SELECT lybm FROM sh_report_chapter"
                            + " WHERE status IN ('" + ShReportConstants.STATUS_PENDING + "','"
                            + ShReportConstants.STATUS_PASS + "','" + ShReportConstants.STATUS_REJECT + "')"
                            + " GROUP BY lybm HAVING COUNT(DISTINCT chapter_no) >= " + ShReportConstants.CHAPTER_COUNT
                            + ") t");
            int watersheds = pgQueryService.queryInt(
                    "SELECT COUNT(DISTINCT lybm) FROM sh_report_chapter"
                            + " WHERE content IS NOT NULL AND btrim(content) <> ''");
            int chapters = pgQueryService.queryInt("SELECT COUNT(*) FROM sh_report_chapter");
            List<Map<String, Object>> byStatus = new ArrayList<>();
            for (Map<String, Object> row : pgQueryService.queryForList(
                    "SELECT status, COUNT(*) AS n FROM sh_report_chapter GROUP BY status ORDER BY n DESC")) {
                Map<String, Object> item = new LinkedHashMap<>();
                String status = row.get("status") == null ? "未知" : String.valueOf(row.get("status"));
                item.put("name", status.isBlank() ? "未知" : status);
                item.put("value", num(row.get("n")));
                byStatus.add(item);
            }
            List<Map<String, Object>> recent = new ArrayList<>();
            for (Map<String, Object> row : pgQueryService.queryForList(
                    "SELECT batch_no, lybm, MAX(lymc) AS lymc, COUNT(DISTINCT chapter_no) AS chapters,"
                            + " SUM(CASE WHEN status='" + ShReportConstants.STATUS_PASS + "' THEN 1 ELSE 0 END) AS passed,"
                            + " SUM(CASE WHEN status='" + ShReportConstants.STATUS_PENDING + "' THEN 1 ELSE 0 END) AS pending,"
                            + " SUM(CASE WHEN status='" + ShReportConstants.STATUS_FAILED + "' THEN 1 ELSE 0 END) AS failed,"
                            + " MAX(update_time) AS update_time"
                            + " FROM sh_report_chapter GROUP BY batch_no, lybm"
                            + " ORDER BY MAX(update_time) DESC NULLS LAST")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("batchNo", str(row.get("batch_no")));
                item.put("lybm", str(row.get("lybm")));
                item.put("lymc", str(row.get("lymc")));
                item.put("chapters", num(row.get("chapters")));
                item.put("passed", num(row.get("passed")));
                item.put("pending", num(row.get("pending")));
                item.put("failed", num(row.get("failed")));
                item.put("updateTime", fmtTime(row.get("update_time")));
                recent.add(item);
            }
            out.put("reportCount", full);
            out.put("watershedCount", watersheds);
            out.put("chapterCount", chapters);
            out.put("byStatus", byStatus);
            out.put("recent", recent);
        } catch (Exception e) {
            log.warn("概览报告统计失败: {}", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> knowledgeStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseCount", 0);
        out.put("docCount", 0);
        out.put("bases", List.of());
        try {
            Integer bases = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM airag_knowledge WHERE type IS NULL OR type = 'knowledge'", Integer.class);
            Integer docs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM airag_knowledge_doc d"
                            + " JOIN airag_knowledge k ON k.id = d.knowledge_id"
                            + " WHERE k.type IS NULL OR k.type = 'knowledge'",
                    Integer.class);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT k.name, k.status, COUNT(d.id) AS doc_count"
                            + " FROM airag_knowledge k"
                            + " LEFT JOIN airag_knowledge_doc d ON d.knowledge_id = k.id"
                            + " WHERE k.type IS NULL OR k.type = 'knowledge'"
                            + " GROUP BY k.id, k.name, k.status"
                            + " ORDER BY doc_count DESC");
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", str(row.get("name")));
                item.put("status", "enable".equals(str(row.get("status"))) ? "启用" : str(row.get("status")));
                item.put("docCount", num(row.get("doc_count")));
                list.add(item);
            }
            out.put("baseCount", bases == null ? 0 : bases);
            out.put("docCount", docs == null ? 0 : docs);
            out.put("bases", list);
        } catch (Exception e) {
            log.warn("概览知识库统计失败: {}", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> aiRiskStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", 0);
        out.put("success", 0);
        out.put("fail", 0);
        try {
            int success = 0;
            int fail = 0;
            for (Map<String, Object> row : pgQueryService.queryForList(
                    "SELECT status, COUNT(*) AS n FROM sh_hazard_ai_risk_log GROUP BY status")) {
                int n = num(row.get("n"));
                if ("success".equals(str(row.get("status")))) {
                    success += n;
                } else {
                    fail += n;
                }
            }
            out.put("success", success);
            out.put("fail", fail);
            out.put("total", success + fail);
        } catch (Exception e) {
            log.warn("概览隐患识别统计失败: {}", e.getMessage());
        }
        return out;
    }

    private static int num(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String fmtTime(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Date d) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(d);
        }
        String s = String.valueOf(v).replace('T', ' ');
        return s.length() >= 16 ? s.substring(0, 16) : s;
    }
}
