package org.jeecg.modules.airag.shreport.service;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.constants.ShReportConstants;
import org.jeecg.modules.airag.shreport.mapper.ShReportPgMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 山洪报告助手「数据问答」白名单查询：统计类问题（多少个/分布/清单）不靠模型自由发挥，
 * 只允许跑这里预定义的固定 SQL，用户输入一律作为参数绑定，杜绝拼接注入。
 * 固定形状的 SQL 抽到 {@link ShReportPgMapper}（一张图 PostgreSQL）；
 * 带可选过滤条件（县区/乡镇/类型/关键词）的防治对象查询为动态拼装，留在本类并经 ShReportPgQueryService 路由。
 */
@Slf4j
@Service
public class ShReportDataQueryService {

    @Autowired
    private ShReportPgMapper pgMapper;

    @Autowired
    private ShReportPgQueryService pgQueryService;

    /**
     * 执行白名单统计查询并组织为可直接推送的中文回答。
     *
     * @param dataQuery 查询类别（见 ShReportConstants.SUPPORTED_DATA_QUERIES）
     * @param county    县区过滤（可为空）
     * @param town      乡镇过滤（可为空）
     * @param ftp       防治对象类型过滤（可为空）
     * @param keyword   名称关键词过滤（可为空）
     * @param boundLybm 非空表示仅统计该流域（wscd）；空表示全域
     */
    public String query(String dataQuery, String county, String town, String ftp, String keyword, String boundLybm) {
        String q = ShReportConstants.SUPPORTED_DATA_QUERIES.contains(dataQuery) ? dataQuery : ShReportConstants.Q_OVERVIEW;
        switch (q) {
            case ShReportConstants.Q_BY_TYPE:
                return table("防治对象类型统计", objectRows(
                        "COALESCE(NULLIF(TRIM(ftp),''),'未分类') AS \"类型\", COUNT(*) AS \"数量\", COALESCE(SUM(pcount),0) AS \"涉及人口\"",
                        " GROUP BY 1 ORDER BY 2 DESC", county, town, ftp, keyword, boundLybm));
            case ShReportConstants.Q_BY_COUNTY:
                return table("县区防治对象分布", objectRows(
                        "COALESCE(NULLIF(TRIM(county_name),''),'未知') AS \"县区\", COUNT(*) AS \"防治对象数\", COALESCE(SUM(pcount),0) AS \"涉及人口\"",
                        " GROUP BY 1 ORDER BY 2 DESC", county, town, ftp, keyword, boundLybm));
            case ShReportConstants.Q_BY_TOWN:
                return table("乡镇防治对象分布", objectRows(
                        "COALESCE(NULLIF(TRIM(county_name),''),'未知') AS \"县区\", COALESCE(NULLIF(TRIM(town_name),''),'未知') AS \"乡镇\", COUNT(*) AS \"对象数\", COALESCE(SUM(pcount),0) AS \"涉及人口\"",
                        " GROUP BY 1,2 ORDER BY 3 DESC LIMIT 30", county, town, ftp, keyword, boundLybm));
            case ShReportConstants.Q_RISK:
                return riskReply(county, town, ftp, keyword, boundLybm);
            case ShReportConstants.Q_CULVERT:
                return culvertReply();
            case ShReportConstants.Q_GULLY:
                return table("沟滩占地统计", pgMapper.selectGullyStats());
            case ShReportConstants.Q_LIST_OBJECTS:
                return table("防治对象清单（最多50条）", objectRows(
                        "fnm AS \"对象名称\", COALESCE(NULLIF(TRIM(county_name),''),'未知') AS \"县区\", COALESCE(NULLIF(TRIM(town_name),''),'未知') AS \"乡镇\", COALESCE(NULLIF(TRIM(ftp),''),'未分类') AS \"类型\", pcount AS \"人口\", rvnm AS \"所在河流\", wsnm AS \"所在流域\"",
                        " ORDER BY pcount DESC LIMIT 50", county, town, ftp, keyword, boundLybm));
            case ShReportConstants.Q_LIST_WATERSHEDS:
                return table("小流域清单（最多50条）", pgMapper.selectWatershedList(keyword));
            case ShReportConstants.Q_OVERVIEW:
            default:
                return overviewReply(county, town, ftp, keyword, boundLybm);
        }
    }

    /** 总体概况：小流域数、防治对象数、涉及流域数、人口；绑定流域时给出该流域口径 */
    private String overviewReply(String county, String town, String ftp, String keyword, String boundLybm) {
        StringBuilder sb = new StringBuilder();
        if (boundLybm != null && !boundLybm.isEmpty()) {
            Map<String, Object> r = pgMapper.selectBoundOverview(boundLybm);
            sb.append("当前流域「").append(ShReportPgQueryService.displayName(r.get("流域全称"), r.get("流域别名")))
                    .append("」共有防治对象 ").append(r.get("防治对象数")).append(" 个，涉及人口 ").append(r.get("涉及人口"))
                    .append(" 人，其中有风险隐患的对象 ").append(r.get("有风险隐患对象数")).append(" 个。");
        } else {
            Map<String, Object> r = pgMapper.selectGlobalOverview();
            sb.append("当前全域共有小流域 ").append(r.get("小流域数")).append(" 个（按一张图流域面表），防治对象 ")
                    .append(r.get("防治对象数")).append(" 个，涉及流域 ").append(r.get("涉及流域数"))
                    .append(" 个，涉及人口 ").append(r.get("涉及人口")).append(" 人。");
        }
        sb.append("\n\n").append(table("按类型", objectRows(
                "COALESCE(NULLIF(TRIM(ftp),''),'未分类') AS \"类型\", COUNT(*) AS \"数量\", COALESCE(SUM(pcount),0) AS \"涉及人口\"",
                " GROUP BY 1 ORDER BY 2 DESC", county, town, ftp, keyword, boundLybm)));
        sb.append("\n\n").append(table("按县区", objectRows(
                "COALESCE(NULLIF(TRIM(county_name),''),'未知') AS \"县区\", COUNT(*) AS \"防治对象数\", COALESCE(SUM(pcount),0) AS \"涉及人口\"",
                " GROUP BY 1 ORDER BY 2 DESC", county, town, ftp, keyword, boundLybm)));
        return sb.toString();
    }

    private String riskReply(String county, String town, String ftp, String keyword, String boundLybm) {
        StringBuilder sb = new StringBuilder();
        // risk_flags 为 text[]：壅水/漫流/泥石流/临河滑坡/低洼地/急弯/沟滩占地/束窄/改道
        sb.append(table("风险隐患统计", objectRows(
                "COUNT(*) AS \"防治对象总数\", "
                        + "SUM(CASE WHEN EXISTS (SELECT 1 FROM unnest(risk_flags) f WHERE f LIKE '%壅水%') THEN 1 ELSE 0 END) AS \"壅水隐患\", "
                        + "SUM(CASE WHEN EXISTS (SELECT 1 FROM unnest(risk_flags) f WHERE f LIKE '%漫流%') THEN 1 ELSE 0 END) AS \"漫流隐患\", "
                        + "SUM(CASE WHEN EXISTS (SELECT 1 FROM unnest(risk_flags) f WHERE f LIKE '%泥石流%') THEN 1 ELSE 0 END) AS \"泥石流隐患\", "
                        + "SUM(CASE WHEN EXISTS (SELECT 1 FROM unnest(risk_flags) f WHERE f LIKE '%滑坡%') THEN 1 ELSE 0 END) AS \"滑坡隐患\"",
                "", county, town, ftp, keyword, boundLybm)));
        sb.append("\n\n").append(table("存在隐患对象的县区分布", objectRows(
                "COALESCE(NULLIF(TRIM(county_name),''),'未知') AS \"县区\", COUNT(*) AS \"隐患对象数\"",
                " AND risk_flags IS NOT NULL AND array_length(risk_flags,1) > 0"
                        + " GROUP BY 1 ORDER BY 2 DESC LIMIT 10",
                county, town, ftp, keyword, boundLybm)));
        return sb.toString();
    }

    /** 跨沟道路桥涵坝阻水比 R1 分级统计（低≤10%、中10-30%、高>30%、极端>80%） */
    private String culvertReply() {
        StringBuilder sb = new StringBuilder();
        sb.append(table("桥涵坝阻水比R1分级统计", pgMapper.selectRoadObstructionGrade()));
        sb.append("\n\n").append(table("高阻水桥涵坝（R1>30，最多20条）", pgMapper.selectRoadObstructionHigh()));
        return sb.toString();
    }

    /** 防治对象通用查询：固定列与条件模板，过滤器全部参数化；tail 为以 AND/GROUP 开头的固定尾巴 */
    private List<Map<String, Object>> objectRows(String select, String tail,
                                                 String county, String town, String ftp, String keyword, String boundLybm) {
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (boundLybm != null && !boundLybm.isEmpty()) {
            where.append(" wscd=? AND");
            args.add(boundLybm);
        }
        if (notBlank(county)) {
            where.append(" county_name=? AND");
            args.add(county.trim());
        }
        if (notBlank(town)) {
            where.append(" town_name=? AND");
            args.add(town.trim());
        }
        if (notBlank(ftp)) {
            where.append(" ftp=? AND");
            args.add(ftp.trim());
        }
        if (notBlank(keyword)) {
            where.append(" (fnm LIKE ? OR wsnm LIKE ?) AND");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        String cond = where.length() == 0 ? "" : " WHERE" + where.substring(0, where.length() - 4);
        return pgQueryService.queryForList("SELECT " + select + " FROM sh_survey_object" + cond + tail, args.toArray());
    }

    /**
     * 行集转 Markdown 表格；没有数据时明确说无，避免模型对空结果编数字。
     * 输出 GFM 表格（| a | b |），前端聊天气泡按 Markdown 渲染为真正的表格，
     * 与系统 AI 助手的多格式回答保持一致。
     */
    private static String table(String title, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "**" + title + "**：暂无匹配数据。";
        }
        List<String> cols = new ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder("**").append(title).append("**\n\n|");
        for (int i = 0; i < cols.size(); i++) {
            sb.append(' ').append(cell(cols.get(i))).append(" |");
        }
        sb.append("\n|");
        for (int i = 0; i < cols.size(); i++) {
            sb.append(" --- |");
        }
        for (Map<String, Object> row : rows) {
            sb.append("\n|");
            for (int i = 0; i < cols.size(); i++) {
                Object v = row.get(cols.get(i));
                sb.append(' ').append(cell(v == null ? "无" : String.valueOf(v))).append(" |");
            }
        }
        return sb.toString();
    }

    /** 单元格转义：竖线与换行会破坏 Markdown 表格结构 */
    private static String cell(String v) {
        return v.replace("|", "\\|").replace("\r", "").replace("\n", " ");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
