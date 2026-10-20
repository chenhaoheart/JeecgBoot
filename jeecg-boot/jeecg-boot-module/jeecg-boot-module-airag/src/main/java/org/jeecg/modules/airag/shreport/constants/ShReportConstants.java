package org.jeecg.modules.airag.shreport.constants;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.mapper.ShReportPgMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 山洪报告：章节流程名、标题与状态常量。
 */
public final class ShReportConstants {

    private ShReportConstants() {
    }

    public static final String[] CHAPTER_FLOWS = {
            "山洪报告_01流域概况", "山洪报告_02前期准备", "山洪报告_03外业调查成果", "山洪报告_04影响分析",
            "山洪报告_05历史灾害", "山洪报告_06防治区分析", "山洪报告_07成果与建议"};

    public static final String[] CHAPTER_NAMES = {
            "一、流域概况", "二、前期准备", "三、外业调查成果", "四、影响分析",
            "五、历史山洪灾害情况", "六、防治区分析", "七、成果与建议"};

    public static final String STATUS_PENDING = "待审核";
    public static final String STATUS_PASS = "已通过";
    public static final String STATUS_REJECT = "已驳回";
    public static final String STATUS_GENERATING = "生成中";
    public static final String STATUS_FAILED = "失败";

    public static final int CHAPTER_COUNT = 7;

    /** 数据问答白名单类别（固定 SQL 执行见 ShReportDataQueryService） */
    public static final String Q_OVERVIEW = "overview";
    public static final String Q_BY_TYPE = "by_type";
    public static final String Q_BY_COUNTY = "by_county";
    public static final String Q_BY_TOWN = "by_town";
    public static final String Q_RISK = "risk";
    public static final String Q_CULVERT = "culvert";
    public static final String Q_GULLY = "gully";
    public static final String Q_LIST_OBJECTS = "list_objects";
    public static final String Q_LIST_WATERSHEDS = "list_watersheds";

    public static final Set<String> SUPPORTED_DATA_QUERIES = Set.of(
            Q_OVERVIEW, Q_BY_TYPE, Q_BY_COUNTY, Q_BY_TOWN, Q_RISK, Q_CULVERT, Q_GULLY, Q_LIST_OBJECTS, Q_LIST_WATERSHEDS);

    /** 关键指标键（sh_report_metric.metric_key，抽取与跨章校验见 ShReportMetricService） */
    public static final String KEY_OBJECT_COUNT = "OBJECT_COUNT";
    public static final String KEY_POPULATION = "POPULATION";
    public static final String KEY_AREA = "AREA";
    public static final String KEY_RIVER_LENGTH = "RIVER_LENGTH";
    public static final String KEY_SLOPE = "SLOPE";
    public static final String KEY_ELEVATION = "ELEVATION";

    public static String chapterName(int chapterNo) {
        if (chapterNo < 1 || chapterNo > CHAPTER_NAMES.length) {
            return "第" + chapterNo + "章";
        }
        return CHAPTER_NAMES[chapterNo - 1];
    }

    public static String flowName(int chapterNo) {
        if (chapterNo < 1 || chapterNo > CHAPTER_FLOWS.length) {
            return "";
        }
        return CHAPTER_FLOWS[chapterNo - 1];
    }

    /**
     * 「实测数据包」组装：章节生成前按流域（wscd）从一张图 PostgreSQL 调查表（sh_survey_* / sh_map_*）
     * 查询固定形状的统计 SQL（见 {@link ShReportPgMapper}），输出结构化 Markdown 注入流程提示词。
     * 原则：数字一律来自数据库；查不到的数据明确写「暂无调查数据」，由提示词约束模型不得编造。
     * 每个小节独立容错，单表异常不影响其他数据与整体生成。
     */
    @Slf4j
    @Component
    public static class ShReportDataPacketService {

        @Autowired
        private ShReportPgMapper pgMapper;

        /** 第 4 章洪水计算参数（原写死在流程 SQL：Q=30m³/s、n=0.035、S=0.01），改为可配置 */
        @Value("${shreport.ch4.design-peak-flow:30}")
        private double designPeakFlow;

        @Value("${shreport.ch4.roughness:0.035}")
        private double roughness;

        @Value("${shreport.ch4.slope:0.01}")
        private double slope;

        /**
         * 组装指定章节的实测数据包。
         *
         * @param chapterNo 章节号 1-7
         * @param lybm      流域编码（sh_map_watershed.wscd）
         * @param lymc      流域名称
         * @return Markdown 文本（永不为空）
         */
        public String buildPacket(int chapterNo, String lybm, String lymc) {
            return buildPacket(chapterNo, lybm, lymc, false);
        }

        /**
         * @param hasHistoryMaterial 第 5 章是否已有用户上传的历史灾害资料
         */
        public String buildPacket(int chapterNo, String lybm, String lymc, boolean hasHistoryMaterial) {
            String name = (lymc == null || lymc.isEmpty()) ? lybm : lymc;
            StringBuilder sb = new StringBuilder();
            sb.append("【").append(name).append(" 实测数据包】\n");
            sb.append("以下数据来自调查数据库，是结构化事实的主要来源；用户上传资料为补充/修正，使用规则见补充资料");
            if (chapterNo == 5) {
                sb.append("（第5章历史灾害见「历史灾害记录」入参）");
            }
            sb.append("。包中与资料中都没有的数据一律写「暂无调查数据」，严禁编造或引用其他流域数字。\n\n");

            boolean any = false;
            switch (chapterNo) {
                case 1:
                    any |= appendSection(sb, "一、流域基本信息（sh_map_watershed）", safe(() -> pgMapper.selectWatershedInfo(lybm)));
                    any |= appendSection(sb, "二、防治对象分类统计", safe(() -> pgMapper.selectObjectTypes(lybm)));
                    any |= appendSection(sb, "三、防治对象总量", safe(() -> pgMapper.selectObjectTotals(lybm)));
                    any |= appendSection(sb, "四、乡镇分布", safe(() -> pgMapper.selectTowns(lybm)));
                    break;
                case 2:
                    any |= appendSection(sb, "一、防治对象总量", safe(() -> pgMapper.selectObjectTotals(lybm)));
                    any |= appendSection(sb, "二、乡镇分布", safe(() -> pgMapper.selectTowns(lybm)));
                    break;
                case 3:
                    any |= appendSection(sb, "一、防治对象分类统计", safe(() -> pgMapper.selectObjectTypes(lybm)));
                    any |= appendSection(sb, "二、重点防治对象（按受威胁人口）", safe(() -> pgMapper.selectKeyObjects(lybm)));
                    any |= appendSection(sb, "三、防治对象总量", safe(() -> pgMapper.selectObjectTotals(lybm)));
                    any |= appendSection(sb, "四、防治对象风险隐患要素统计", safe(() -> pgMapper.selectRiskFlags(lybm)));
                    any |= appendSection(sb, "五、沟道及沟滩占地调查成果", safe(() -> pgMapper.selectGullyOccupy(lybm)));
                    any |= appendSection(sb, "六、隐患要素点位统计", safe(() -> pgMapper.selectHazardStats(lybm)));
                    any |= appendSection(sb, "七、隐患要素点位清单（最多30条）", safe(() -> pgMapper.selectHazardPoints(lybm)));
                    any |= appendSection(sb, "八、跨沟桥涵隐患点（现场照片按编码取一张，最多20条）", safe(() -> pgMapper.selectBridgeHazards(lybm)));
                    break;
                case 4:
                    sb.append("一、洪水计算参数（本章所有壅水/溃决计算必须采用）：\n");
                    sb.append(ch4ParamsText()).append("\n\n");
                    any = true;
                    any |= appendSection(sb, "二、重点防治对象（按受威胁人口）", safe(() -> pgMapper.selectKeyObjects(lybm)));
                    any |= appendSection(sb, "三、防治对象总量", safe(() -> pgMapper.selectObjectTotals(lybm)));
                    any |= appendSection(sb, "四、跨沟道路桥涵坝及其阻水影响", safe(() -> pgMapper.selectRoadObstruction(lybm)));
                    break;
                case 5:
                    any |= appendSection(sb, "一、本流域防治对象背景数据", safe(() -> pgMapper.selectObjectTotals(lybm)));
                    if (hasHistoryMaterial) {
                        sb.append("二、历史灾害记录：调查库无结构化台账；请依据用户上传资料（历史灾害记录入参）撰写，引用时标明来源文件名，禁止编造资料中没有的数字。\n\n");
                    } else {
                        sb.append("二、历史灾害记录：暂无调查数据，不得虚构具体年份、场次与损失数字。\n\n");
                    }
                    any = true;
                    break;
                case 6:
                    any |= appendSection(sb, "一、乡镇分布（分区统计基础）", safe(() -> pgMapper.selectTowns(lybm)));
                    any |= appendSection(sb, "二、重点防治对象（受威胁人口较多）", safe(() -> pgMapper.selectKeyObjects(lybm)));
                    any |= appendSection(sb, "三、防治对象总量", safe(() -> pgMapper.selectObjectTotals(lybm)));
                    any |= appendSection(sb, "四、防治区（防护对象）统计", safe(() -> pgMapper.selectPreventionAreas(lybm)));
                    break;
                case 7:
                    any |= appendSection(sb, "一、防治对象总量", safe(() -> pgMapper.selectObjectTotals(lybm)));
                    any |= appendSection(sb, "二、防治对象分类统计", safe(() -> pgMapper.selectObjectTypes(lybm)));
                    any |= appendSection(sb, "三、乡镇分布", safe(() -> pgMapper.selectTowns(lybm)));
                    break;
                default:
                    break;
            }
            if (!any) {
                sb.append("暂无该流域的调查数据。涉及数据的表述一律写「暂无调查数据」，严禁编造。\n");
            }
            return sb.toString();
        }

        /** 第 4 章计算参数说明（供数据包与提示词统一引用） */
        public String ch4ParamsText() {
            return "设计洪峰流量 Q=" + trimDouble(designPeakFlow) + "m³/s；沟道糙率 n=" + trimDouble(roughness)
                    + "；沟道比降 S=" + trimDouble(slope) + "。";
        }

        /** 单个小节查询容错：异常降级为空并记日志，不拖垮整体数据包 */
        private List<Map<String, Object>> safe(Supplier<List<Map<String, Object>>> query) {
            try {
                List<Map<String, Object>> rows = query.get();
                return rows == null ? List.of() : rows;
            } catch (Exception e) {
                log.warn("实测数据包查询失败（降级为空）：{}", e.getMessage());
                return List.of();
            }
        }

        /** 追加一个数据小节：无数据时返回 false 且不输出标题 */
        private boolean appendSection(StringBuilder sb, String title, List<Map<String, Object>> rows) {
            if (rows == null || rows.isEmpty()) {
                return false;
            }
            sb.append(title).append("：\n").append(toMarkdownTable(rows)).append("\n");
            return true;
        }

        /** 通用结果集转 Markdown 表格（保留列顺序，Mapper 结果为 LinkedHashMap） */
        static String toMarkdownTable(List<Map<String, Object>> rows) {
            if (rows == null || rows.isEmpty()) {
                return "";
            }
            Map<String, Object> first = rows.get(0);
            List<String> headers = new ArrayList<>(first.keySet());
            StringBuilder sb = new StringBuilder();
            sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
            sb.append("|").append(" --- |".repeat(headers.size())).append("\n");
            for (Map<String, Object> row : rows) {
                List<String> cells = new ArrayList<>();
                for (String h : headers) {
                    Object v = row.get(h);
                    cells.add(v == null ? "" : String.valueOf(v).replace("|", "/").replace("\n", " "));
                }
                sb.append("| ").append(String.join(" | ", cells)).append(" |\n");
            }
            return sb.toString();
        }

        private static String trimDouble(double v) {
            if (v == Math.rint(v)) {
                return String.valueOf((long) v);
            }
            return String.valueOf(v);
        }
    }
}
