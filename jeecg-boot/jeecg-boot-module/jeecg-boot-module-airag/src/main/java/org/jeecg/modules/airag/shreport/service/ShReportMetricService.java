package org.jeecg.modules.airag.shreport.service;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.mapper.ShReportPgMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全报告关键指标：数据包内确定性指标直接锁定（source=DATA），
 * 第 1 章成文后补充抽取面积/河长/比降/高程（source=CH1，仅在数据包未提供时填充）。
 * 后续章节与跨章一致性校验以该表为基准，避免同一数字各章互相矛盾。
 */
@Slf4j
@Service
public class ShReportMetricService {

    public static final String KEY_OBJECT_COUNT = "OBJECT_COUNT";
    public static final String KEY_POPULATION = "POPULATION";
    public static final String KEY_AREA = "AREA";
    public static final String KEY_RIVER_LENGTH = "RIVER_LENGTH";
    public static final String KEY_SLOPE = "SLOPE";
    public static final String KEY_ELEVATION = "ELEVATION";

    private static final Pattern AREA = Pattern.compile("(?:流域面积|集水面积|集雨面积)[^0-9\\n]{0,12}?(\\d+(?:\\.\\d+)?)\\s*(?:km²|km2|平方公里|平方千米)");
    private static final Pattern RIVER_LENGTH = Pattern.compile("(?:主沟|沟道|河沟)?(?:长度|河长)[^0-9\\n]{0,12}?(\\d+(?:\\.\\d+)?)\\s*(?:km|KM|公里|千米)");
    private static final Pattern SLOPE = Pattern.compile("(?:沟道)?比降[^0-9\\n]{0,12}?(\\d+(?:\\.\\d+)?)\\s*(?:‰|%|千分)");
    private static final Pattern ELEVATION = Pattern.compile("沟口高程[^0-9\\n]{0,12}?(\\d+(?:\\.\\d+)?)\\s*(?:m|M|米)");

    @Autowired
    private ShReportPgQueryService pgQuery;

    @Autowired
    private ShReportPgMapper pgMapper;

    /** 生成前锁定数据包确定性指标（防治对象数、受威胁人口），同批次重复调用幂等更新 */
    public void lockDataMetrics(String batchNo, String lybm) {
        if (empty(batchNo) || empty(lybm)) {
            return;
        }
        try {
            Map<String, Object> row = pgMapper.selectObjectCountSum(lybm);
            if (row != null && !row.isEmpty()) {
                String count = String.valueOf(((Number) row.get("c")).longValue());
                String population = String.valueOf(((Number) row.get("p")).longValue());
                upsert(batchNo, lybm, KEY_OBJECT_COUNT, "防治对象数量", count, "处", "DATA", null, true);
                upsert(batchNo, lybm, KEY_POPULATION, "受威胁人口", population, "人", "DATA", null, true);
            }
        } catch (Exception e) {
            log.debug("锁定数据包指标失败：{}", e.getMessage());
        }
    }

    /** 第 1 章成文后抽取面积/河长/比降/高程，仅在指标不存在时填充（宁漏勿滥） */
    public void lockFromChapterOne(String batchNo, String lybm, String content) {
        if (empty(batchNo) || empty(lybm) || content == null) {
            return;
        }
        lockIfAbsent(batchNo, lybm, KEY_AREA, "流域面积", firstGroup(AREA, content), "km²");
        lockIfAbsent(batchNo, lybm, KEY_RIVER_LENGTH, "主沟长度", firstGroup(RIVER_LENGTH, content), "km");
        lockIfAbsent(batchNo, lybm, KEY_SLOPE, "沟道比降", firstGroup(SLOPE, content), "‰");
        lockIfAbsent(batchNo, lybm, KEY_ELEVATION, "沟口高程", firstGroup(ELEVATION, content), "m");
    }

    /** 查询指标键->值，供一致性校验使用 */
    public Map<String, String> metricMap(String batchNo, String lybm) {
        Map<String, String> map = new HashMap<>();
        if (empty(batchNo)) {
            return map;
        }
        try {
            List<Map<String, Object>> rows = empty(lybm)
                    ? pgQuery.queryForList("SELECT metric_key, value FROM sh_report_metric WHERE batch_no=?", batchNo)
                    : pgQuery.queryForList(
                            "SELECT metric_key, value FROM sh_report_metric WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'')",
                            batchNo, lybm);
            for (Map<String, Object> r : rows) {
                map.put(String.valueOf(r.get("metric_key")), String.valueOf(r.get("value")));
            }
        } catch (Exception e) {
            log.debug("查询关键指标失败：{}", e.getMessage());
        }
        return map;
    }

    private static String firstGroup(Pattern p, String content) {
        Matcher m = p.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private void lockIfAbsent(String batchNo, String lybm, String key, String name, String value, String unit) {
        if (empty(value)) {
            return;
        }
        upsert(batchNo, lybm, key, name, value, unit, "CH1", 1, false);
    }

    private void upsert(String batchNo, String lybm, String key, String name, String value, String unit,
                        String source, Integer chapterNo, boolean overwrite) {
        try {
            List<String> exist = pgQuery.queryForList(
                    "SELECT id FROM sh_report_metric WHERE batch_no=? AND COALESCE(lybm,'')=COALESCE(?,'') AND metric_key=?",
                    String.class, batchNo, lybm, key);
            java.sql.Timestamp now = new java.sql.Timestamp(System.currentTimeMillis());
            if (exist.isEmpty()) {
                pgQuery.update(
                        "INSERT INTO sh_report_metric (id,batch_no,lybm,metric_key,metric_name,value,unit,source,chapter_no,update_time) " +
                                "VALUES (?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(), batchNo, lybm, key, name, value, unit, source, chapterNo, now);
            } else if (overwrite) {
                pgQuery.update(
                        "UPDATE sh_report_metric SET value=?, source=?, update_time=? WHERE id=?",
                        value, source, now, exist.get(0));
            }
        } catch (Exception e) {
            log.debug("写入关键指标失败 key={}：{}", key, e.getMessage());
        }
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }
}
