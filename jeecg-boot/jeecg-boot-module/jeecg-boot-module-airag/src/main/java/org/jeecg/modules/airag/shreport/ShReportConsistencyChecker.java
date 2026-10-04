package org.jeecg.modules.airag.shreport;

import org.jeecg.modules.airag.shreport.service.ShReportMetricService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨章关键指标一致性校验（纯规则可单测）：
 * 抽取各章正文中面积/河长/比降/高程/人口/对象数的取值，
 * 与锁定指标（数据包/首章）及各章之间互相矛盾时，输出 HINT 级 CONSISTENCY 问题。
 * 只做提示，不阻断导出（宁漏勿滥，避免误报干扰）。
 */
public final class ShReportConsistencyChecker {

    private ShReportConsistencyChecker() {
    }

    private static final double NUMERIC_TOLERANCE = 0.02;

    private static final MetricDef[] DEFS = {
            new MetricDef(ShReportMetricService.KEY_AREA, "流域面积",
                    Pattern.compile("(?:流域面积|集水面积|集雨面积)[^0-9\\n]{0,12}?(\\d+(?:\\.\\d+)?)\\s*(?:km²|km2|平方公里|平方千米)"),
                    "km²", true, false),
            new MetricDef(ShReportMetricService.KEY_RIVER_LENGTH, "主沟长度",
                    Pattern.compile("(?:主沟长度|主沟道长|沟道长度|河长)[^0-9\\n]{0,12}?(\\d+(?:\\.\\d+)?)\\s*(?:km|KM|公里|千米)"),
                    "km", true, false),
            new MetricDef(ShReportMetricService.KEY_SLOPE, "沟道比降",
                    Pattern.compile("(?:沟道)?比降[^0-9\\n]{0,12}?(\\d+(?:\\.\\d+)?)\\s*(?:‰|%|千分)"),
                    "‰", true, false),
            new MetricDef(ShReportMetricService.KEY_ELEVATION, "沟口高程",
                    Pattern.compile("沟口高程[^0-9\\n]{0,12}?(\\d+(?:\\.\\d+)?)\\s*(?:m|M|米)"),
                    "m", true, false),
            new MetricDef(ShReportMetricService.KEY_POPULATION, "受威胁人口",
                    Pattern.compile("(?:受威胁|威胁)?人口[^0-9\\n]{0,12}?(\\d{2,}(?:\\.\\d+)?)\\s*人"),
                    "人", false, true),
            new MetricDef(ShReportMetricService.KEY_OBJECT_COUNT, "防治对象数量",
                    Pattern.compile("防治对象[^0-9\\n]{0,12}?(\\d{1,})\\s*(?:处|个|户)"),
                    "处", false, true),
    };

    /**
     * 执行校验。
     *
     * @param chapters  章节号 -> 正文
     * @param canonical 指标键 -> 锁定值
     * @return 矛盾问题清单（HINT）
     */
    public static List<ShReportQcIssue> check(Map<Integer, String> chapters, Map<String, String> canonical) {
        List<ShReportQcIssue> issues = new ArrayList<>();
        if (chapters == null || chapters.isEmpty()) {
            return issues;
        }
        for (MetricDef def : DEFS) {
            // 章节号 -> 该章出现的不同取值（保留出现顺序）
            Map<Integer, List<Double>> perChapter = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : chapters.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                List<Double> hits = new ArrayList<>();
                Matcher m = def.pattern.matcher(e.getValue());
                while (m.find()) {
                    try {
                        double v = Double.parseDouble(m.group(1).replace(",", ""));
                        if (hits.stream().noneMatch(x -> approximately(x, v, def.numericTolerance))) {
                            hits.add(v);
                        }
                    } catch (NumberFormatException ignore) {
                        // 数字解析失败跳过
                    }
                }
                if (!hits.isEmpty()) {
                    perChapter.put(e.getKey(), hits);
                }
            }
            if (perChapter.isEmpty()) {
                continue;
            }

            // 与锁定值比对
            String locked = canonical == null ? null : canonical.get(def.key);
            if (locked != null && !locked.isBlank()) {
                Double lockedNum = parseQuiet(locked);
                if (lockedNum != null) {
                    List<String> conflicts = new ArrayList<>();
                    // 与锁定值不符的章，图内据此回写
                    List<Integer> deviated = new ArrayList<>();
                    for (Map.Entry<Integer, List<Double>> e : perChapter.entrySet()) {
                        for (Double v : e.getValue()) {
                            boolean deviate;
                            if (def.sumType) {
                                // 汇总型指标（人口/对象数）在各章会出现分项、分乡镇等拆解值（≤ 总数），
                                // 只有超过基准总数才算矛盾，避免把表格拆解数据误判为不一致而反复回写
                                deviate = !approximately(lockedNum, v, true) && v > lockedNum;
                            } else {
                                deviate = !approximately(lockedNum, v, def.numericTolerance);
                            }
                            if (deviate) {
                                conflicts.add("第" + e.getKey() + "章为" + format(v) + def.unit);
                                deviated.add(e.getKey());
                            }
                        }
                    }
                    if (!conflicts.isEmpty()) {
                        issues.add(ShReportQcIssue.hint(ShReportQcIssue.TYPE_CONSISTENCY,
                                def.name + "前后不一致：基准值 " + locked + def.unit + "，" + String.join("、", conflicts))
                                .withChapters(deviated));
                        continue;
                    }
                }
            }

            // 无锁定值时：不同章节出现两个及以上互不近似的取值。
            // 汇总型指标没有锁定总数时无法区分"分项值"与"矛盾值"（宁漏勿滥），跳过校验
            if (def.sumType) {
                continue;
            }
            Map<Double, List<Integer>> valueChapters = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<Double>> e : perChapter.entrySet()) {
                for (Double v : e.getValue()) {
                    Double key = valueChapters.keySet().stream()
                            .filter(x -> approximately(x, v, def.numericTolerance)).findFirst().orElse(v);
                    valueChapters.computeIfAbsent(key, k -> new ArrayList<>()).add(e.getKey());
                }
            }
            if (valueChapters.size() > 1) {
                // 无锁定值时以出现最早的章所在分组为基准（第 1 章是指标抽取来源），其余章判为需回写
                int reference = valueChapters.values().stream()
                        .flatMap(List::stream)
                        .min(Integer::compareTo)
                        .orElse(Integer.MAX_VALUE);
                List<String> parts = new ArrayList<>();
                List<Integer> deviated = new ArrayList<>();
                for (Map.Entry<Double, List<Integer>> e : valueChapters.entrySet()) {
                    parts.add(format(e.getKey()) + def.unit + "（第" + joinChapters(e.getValue()) + "章）");
                    if (!e.getValue().contains(reference)) {
                        deviated.addAll(e.getValue());
                    }
                }
                Collections.sort(deviated);
                issues.add(ShReportQcIssue.hint(ShReportQcIssue.TYPE_CONSISTENCY,
                        def.name + "在各章取值不一致：" + String.join("、", parts))
                        .withChapters(deviated));
            }
        }
        return issues;
    }

    private static String joinChapters(List<Integer> chapters) {
        List<Integer> sorted = new ArrayList<>(chapters);
        sorted.sort(Integer::compareTo);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(sorted.get(i));
        }
        return sb.toString();
    }

    private static boolean approximately(double a, double b, boolean numericTolerance) {
        if (!numericTolerance) {
            return a == b;
        }
        double tol = Math.max(NUMERIC_TOLERANCE * Math.max(Math.abs(a), Math.abs(b)), 0.01);
        return Math.abs(a - b) <= tol;
    }

    private static Double parseQuiet(String s) {
        try {
            return Double.parseDouble(s.replace(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String format(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private static final class MetricDef {
        final String key;
        final String name;
        final Pattern pattern;
        final String unit;
        final boolean numericTolerance;
        /** 汇总型指标：各章会出现 ≤ 总数的分项拆解值（如分乡镇人口），只把超过总数的取值视为矛盾 */
        final boolean sumType;

        MetricDef(String key, String name, Pattern pattern, String unit, boolean numericTolerance, boolean sumType) {
            this.key = key;
            this.name = name;
            this.pattern = pattern;
            this.unit = unit;
            this.numericTolerance = numericTolerance;
            this.sumType = sumType;
        }
    }
}
