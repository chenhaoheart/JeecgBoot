package org.jeecg.modules.airag.onemap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.onemap.util.ShReportMapGeometry;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 山洪一张图数据后台维护：流域 / 断面 / 防治对象 / 隐患要素 / 调查成果表的
 * 分页查询、行编辑、行删除与按调查年份（survey_year）管理。
 *
 * 表结构与列来自静态白名单配置（SQL 标识符不拼接用户输入，值全部参数化）。
 * survey_year 列在应用就绪时幂等补充；存量数据年份为 NULL，页面以「未标注」呈现。
 */
@Slf4j
@Service
public class ShReportMapAdminService {

    /** 列配置：col 数据库列名（白名单），label 中文名（表头/Excel 导入表头），numeric 数值列 */
    public record ColConf(String col, String label, boolean numeric) {
    }

    /** 数据集配置：key 前端标识，table PG 表名，idCol 主键，geomCol 可由经纬度重建的几何列 */
    public record DsConf(String key, String table, String label, String idCol, List<ColConf> cols,
                         String geomCol, String lngCol, String latCol) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, DsConf> DATASETS = new LinkedHashMap<>();

    private static void ds(String key, String table, String label, String geomCol, String lngCol, String latCol,
                           Object... cols) {
        List<ColConf> list = new ArrayList<>();
        for (int i = 0; i + 1 < cols.length; i += 2) {
            String col = (String) cols[i];
            // "标签|N" 形式：带 N 后缀表示数值列
            String s = String.valueOf(cols[i + 1]);
            boolean numeric = s.endsWith("|N");
            list.add(new ColConf(col, numeric ? s.substring(0, s.length() - 2) : s, numeric));
        }
        DATASETS.put(key, new DsConf(key, table, label, "id", list, geomCol, lngCol, latCol));
    }

    static {
        ds("watershed", "sh_map_watershed", "小流域（流域面）", "geom", "outlet_lng", "outlet_lat",
                "wscd", "小流域编码", "wsnm", "小流域名称", "alias_name", "沟名", "river_name", "河流名称",
                "wsarea", "面积(km²)|N", "wsperi", "周长(km)|N", "wsslp", "坡度|N",
                "outlet_lng", "出口经度|N", "outlet_lat", "出口纬度|N", "outlet_elv", "出口高程(m)|N",
                "max_elv", "最高点高程(m)|N", "jizhen_count", "集镇个数|N", "village_count", "防治村个数|N",
                "gully_count", "山洪沟道条数|N", "reservoirs", "涉及水库");
        ds("section", "sh_map_section", "断面（平面位置）", "geom", null, null,
                "pid", "断面编码", "sec_type", "断面类型(A横/B纵)", "sec_name", "断面名称",
                "rvname", "河流名称", "rvcd", "河流代码", "wscd", "小流域编码");
        ds("prevention", "sh_map_prevention_area", "防治对象（面）", "geom", null, null,
                "obj_pid", "防护对象代码", "obj_name", "防护对象名称", "obj_type", "防护对象类型",
                "pcount", "人口|N", "rvname", "河流名称", "rvcd", "河流代码", "wscd", "小流域编码");
        ds("hazard", "sh_map_hazard_point", "隐患要素（点）", "geom", "lgtd", "lttd",
                "pid", "隐患要素编码", "name", "名称", "hz_type", "隐患类型",
                "rvname", "河流名称", "rvcd", "河流代码", "wscd", "小流域编码",
                "fcd", "防护对象代码", "fnm", "防护对象名称", "lgtd", "经度|N", "lttd", "纬度|N",
                "photos", "照片", "videos", "视频");
        ds("survey_object", "sh_survey_object", "防治对象名录（调查成果）", null, "lgtd", "lttd",
                "county_name", "县名称", "county_adcd", "县代码", "town_name", "乡镇名称", "town_adcd", "乡镇代码",
                "fnm", "防护对象名称", "fcd", "防护对象代码", "ftp", "防护对象类型", "pcount", "人口|N",
                "rvnm", "河流名称", "rvcd", "河流代码", "wsnm", "小流域名称", "wscd", "小流域编码",
                "lgtd", "经度|N", "lttd", "纬度|N", "river_section_name", "河段名称", "comments", "备注");
        ds("survey_road", "sh_survey_road", "跨沟道路/桥涵/塘坝（调查成果）", null, "lgtd", "lttd",
                "county_name", "县名称", "county_adcd", "县代码", "town_name", "乡镇名称", "town_adcd", "乡镇代码",
                "name", "名称", "code", "编码", "stype", "类型(A/B/C)", "lgtd", "经度|N", "lttd", "纬度|N",
                "channelw", "沟道宽度(m)", "channeld", "沟道深度(m)", "csshpt", "坝顶高程(m)",
                "obstruction_area_ratio", "阻水面积比", "obstruction_storage_capacity", "阻水库容(万m³)|N",
                "rvnm", "河流名称", "rv_code", "河流代码",
                "backwater_objname", "壅水影响对象", "burst_objname", "溃决影响对象", "overflow_objname", "漫溢影响对象",
                "comments", "备注", "wsnm", "小流域名称", "wscd", "小流域编码");
        ds("survey_gully", "sh_survey_gully", "沟滩占地（调查成果）", null, "lgtd", "lttd",
                "county_name", "县名称", "county_adcd", "县代码", "town_name", "乡镇名称", "town_adcd", "乡镇代码",
                "name", "名称", "code", "编码", "stype", "类型(A/B/C)", "lgtd", "经度|N", "lttd", "纬度|N",
                "width", "宽度(m)", "deep", "深度(m)", "dmform", "占地形态", "zsmj", "占地面积",
                "rvnm", "河流名称", "rvcd", "河流代码", "comments", "备注", "wsnm", "小流域名称", "wscd", "小流域编码");
    }

    @Autowired
    private ShReportPgQueryService pgQueryService;

    /** 应用就绪后为 9 张表补调查年份列（已存在时 PG 报错忽略）；隐患点补照片/视频列 */
    @EventListener(ApplicationReadyEvent.class)
    public void initSurveyYear() {
        for (DsConf d : DATASETS.values()) {
            try {
                pgQueryService.execute("ALTER TABLE " + d.table() + " ADD COLUMN IF NOT EXISTS survey_year INT");
            } catch (Exception e) {
                log.warn("补充 survey_year 列失败 {}: {}", d.table(), e.getMessage());
            }
        }
        for (String col : new String[]{"photos", "videos"}) {
            try {
                pgQueryService.execute("ALTER TABLE sh_map_hazard_point ADD COLUMN IF NOT EXISTS " + col + " TEXT");
            } catch (Exception e) {
                log.warn("补充隐患点 {} 列失败: {}", col, e.getMessage());
            }
        }
    }

    /** 数据集元数据（含列配置），供前端动态表格/表单 */
    public List<Map<String, Object>> datasets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DsConf d : DATASETS.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.key());
            m.put("label", d.label());
            m.put("hasGeom", d.geomCol() != null);
            List<Map<String, Object>> cols = new ArrayList<>();
            for (ColConf c : d.cols()) {
                cols.add(Map.of("col", c.col(), "label", c.label(), "numeric", c.numeric()));
            }
            m.put("columns", cols);
            out.add(m);
        }
        return out;
    }

    private DsConf requireDs(String key) {
        DsConf d = DATASETS.get(key == null ? "" : key.trim().toLowerCase(Locale.ROOT));
        if (d == null) {
            throw new IllegalArgumentException("未知数据集: " + key + "，可用: " + DATASETS.keySet());
        }
        return d;
    }

    /** 供导入服务取数据集配置 */
    public DsConf dataset(String key) {
        return requireDs(key);
    }

    /** 数据集的调查年份清单（含数量），null 年份记为 0（未标注） */
    public List<Map<String, Object>> years(String ds) {
        DsConf d = requireDs(ds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : pgQueryService.queryForList(
                "SELECT survey_year AS y, COUNT(*) AS n FROM " + d.table()
                        + " GROUP BY survey_year ORDER BY survey_year DESC NULLS FIRST")) {
            Object y = r.get("y");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year", y == null ? 0 : ((Number) y).intValue());
            m.put("count", ((Number) r.get("n")).longValue());
            out.add(m);
        }
        return out;
    }

    /** 数据集是否配置了某列（过滤条件只对拥有该列的数据集生效） */
    private boolean hasCol(DsConf d, String col) {
        return d.cols().stream().anyMatch(c -> c.col().equals(col));
    }

    /** 数据集筛选项：政区（县/乡镇）与小流域去重清单（无对应列的数据集返回空清单） */
    public Map<String, Object> facets(String ds) {
        DsConf d = requireDs(ds);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counties", hasCol(d, "county_adcd") ? pgQueryService.queryForList(
                "SELECT DISTINCT county_adcd AS adcd, county_name AS name FROM " + d.table()
                        + " WHERE county_adcd IS NOT NULL ORDER BY county_adcd") : List.of());
        out.put("towns", hasCol(d, "town_adcd") ? pgQueryService.queryForList(
                "SELECT DISTINCT town_adcd AS adcd, town_name AS name, county_adcd FROM " + d.table()
                        + " WHERE town_adcd IS NOT NULL ORDER BY town_adcd") : List.of());
        out.put("watersheds", watershedFacets(d));
        return out;
    }

    /**
     * 小流域筛选项。调查成果表自带 wsnm；断面、防治对象、隐患只有 wscd，
     * 名称改从流域表关联，避免引用不存在的列。
     */
    private List<Map<String, Object>> watershedFacets(DsConf d) {
        if (!hasCol(d, "wscd")) {
            return List.of();
        }
        if (hasCol(d, "wsnm")) {
            return pgQueryService.queryForList(
                    "SELECT DISTINCT wscd AS cd, wsnm AS nm FROM " + d.table()
                            + " WHERE wscd IS NOT NULL AND TRIM(wscd) <> '' ORDER BY wscd");
        }
        return pgQueryService.queryForList(
                "SELECT DISTINCT s.wscd AS cd, "
                        + "COALESCE(NULLIF(TRIM(w.alias_name), ''), NULLIF(TRIM(w.wsnm), ''), s.wscd) AS nm "
                        + "FROM " + d.table() + " s LEFT JOIN sh_map_watershed w ON w.wscd = s.wscd "
                        + "WHERE s.wscd IS NOT NULL AND TRIM(s.wscd) <> '' ORDER BY s.wscd");
    }

    /** 分页列表：year 传 0 表示未标注年份，不传表示全部；countyAdcd/townAdcd/wscd 为政区与流域过滤 */
    public Map<String, Object> list(String ds, Integer year, String search,
                                    String countyAdcd, String townAdcd, String wscd, int pageNo, int pageSize) {
        DsConf d = requireDs(ds);
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (countyAdcd != null && !countyAdcd.isBlank() && hasCol(d, "county_adcd")) {
            where.append(" AND county_adcd = ? ");
            args.add(countyAdcd.trim());
        }
        if (townAdcd != null && !townAdcd.isBlank() && hasCol(d, "town_adcd")) {
            where.append(" AND town_adcd = ? ");
            args.add(townAdcd.trim());
        }
        if (wscd != null && !wscd.isBlank() && hasCol(d, "wscd")) {
            where.append(" AND wscd = ? ");
            args.add(wscd.trim());
        }
        if (year != null) {
            if (year == 0) {
                where.append(" AND survey_year IS NULL ");
            } else {
                where.append(" AND survey_year = ? ");
                args.add(year);
            }
        }
        if (search != null && !search.isBlank()) {
            List<String> textCols = d.cols().stream().filter(c -> !c.numeric()).map(ColConf::col).toList();
            if (!textCols.isEmpty()) {
                String q = "%" + search.trim() + "%";
                where.append(" AND (");
                for (int i = 0; i < textCols.size(); i++) {
                    if (i > 0) {
                        where.append(" OR ");
                    }
                    where.append("COALESCE(CAST(").append(textCols.get(i)).append(" AS TEXT),'') ILIKE ? ");
                    args.add(q);
                }
                where.append(") ");
            }
        }
        int pn = Math.max(pageNo, 1);
        int ps = Math.min(Math.max(pageSize, 1), 200);
        String cols = d.cols().stream().map(ColConf::col).reduce((a, b) -> a + ", " + b)
                .map(s -> d.idCol() + ", " + s + ", survey_year").orElse(d.idCol() + ", survey_year");
        String base = "SELECT " + cols + " FROM " + d.table() + where;
        Integer total = pgQueryService.queryForObject("SELECT COUNT(*) FROM (" + base + ") t", Integer.class, args.toArray());
        List<Map<String, Object>> data = pgQueryService.queryForList(
                base + " ORDER BY " + d.idCol() + " LIMIT " + ps + " OFFSET " + ((pn - 1) * ps), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("pageNo", pn);
        out.put("pageSize", ps);
        out.put("data", data);
        return out;
    }

    /** 按主键读一行（列与分页列表一致），供知识图谱点进编辑 */
    public Map<String, Object> getRow(String ds, long id) {
        DsConf d = requireDs(ds);
        String cols = d.cols().stream().map(ColConf::col).reduce((a, b) -> a + ", " + b)
                .map(s -> d.idCol() + ", " + s + ", survey_year").orElse(d.idCol() + ", survey_year");
        List<Map<String, Object>> rows = pgQueryService.queryForList(
                "SELECT " + cols + " FROM " + d.table() + " WHERE " + d.idCol() + " = ?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("记录不存在");
        }
        return rows.get(0);
    }

    /** 行编辑：只允许白名单列 + survey_year；隐患点经纬度变化时同步重建几何 */
    public void updateRow(String ds, long id, Map<String, Object> data) {
        DsConf d = requireDs(ds);
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("没有可更新的字段");
        }
        Map<String, Object> sets = new LinkedHashMap<>();
        for (ColConf c : d.cols()) {
            if (!data.containsKey(c.col())) {
                continue;
            }
            Object v = data.get(c.col());
            if (v instanceof String s && s.isBlank()) {
                v = null;
            }
            sets.put(c.col(), c.numeric() ? toNum(v) : v);
        }
        if (data.containsKey("survey_year")) {
            Object y = data.get("survey_year");
            sets.put("survey_year", y == null || (y instanceof String s && s.isBlank()) ? null : toNum(y));
        }
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("没有可更新的字段");
        }
        // 隐患要素：经纬度被编辑时同步重建 geom，保证地图展示一致
        boolean rebuildGeom = "hazard".equals(d.key()) && (sets.containsKey("lgtd") || sets.containsKey("lttd"));
        Double lng = rebuildGeom ? toNum(sets.get("lgtd")) : null;
        Double lat = rebuildGeom ? toNum(sets.get("lttd")) : null;
        StringBuilder sql = new StringBuilder("UPDATE ").append(d.table()).append(" SET ");
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : sets.entrySet()) {
            sql.append(e.getKey()).append(" = ?, ");
            args.add(e.getValue());
        }
        if (rebuildGeom) {
            if (lng == null || lat == null) {
                sql.append("geom = NULL, ");
            } else {
                sql.append("geom = ST_SetSRID(ST_MakePoint(?, ?), 4326), ");
                args.add(lng);
                args.add(lat);
            }
        }
        sql.setLength(sql.length() - 2);
        sql.append(" WHERE ").append(d.idCol()).append(" = ?");
        args.add(id);
        pgQueryService.update(sql.toString(), args.toArray());
    }

    /** 行删除（支持批量） */
    public int deleteRows(String ds, List<Object> ids) {
        DsConf d = requireDs(ds);
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<Long> longIds = ids.stream().map(v -> {
            Number n = toNum(v);
            return n == null ? null : n.longValue();
        }).filter(v -> v != null).toList();
        if (longIds.isEmpty()) {
            return 0;
        }
        return pgQueryService.update("DELETE FROM " + d.table() + " WHERE " + d.idCol() + " = ANY(?)",
                longIds.stream().mapToLong(Long::longValue).toArray());
    }

    // ---------------------------------------------------------------- 空间几何编辑（GeoJSON <-> PostGIS）

    /** 各数据集允许写入的 GeoJSON 几何类型 */
    private static final Map<String, List<String>> GEOM_TYPES = Map.of(
            "watershed", List.of("Polygon", "MultiPolygon"),
            "section", List.of("LineString", "MultiLineString"),
            "prevention", List.of("Polygon", "MultiPolygon"),
            "hazard", List.of("Point"));

    /** 读取一行的空间几何（GeoJSON geometry），geom 为空时 geometry 为 null */
    public Map<String, Object> getGeometry(String ds, long id) {
        DsConf d = requireDs(ds);
        if (d.geomCol() == null) {
            throw new IllegalArgumentException("该数据集无空间几何列: " + ds);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ds", ds);
        out.put("id", id);
        out.put("geometry", null);
        List<Map<String, Object>> rows = pgQueryService.queryForList(
                "SELECT ST_AsGeoJSON(" + d.geomCol() + ") AS geo FROM " + d.table()
                        + " WHERE " + d.idCol() + " = ?", id);
        if (!rows.isEmpty()) {
            String geo = ShReportMapGeometry.str(rows.get(0).get("geo"));
            if (geo != null && !geo.isEmpty()) {
                try {
                    out.put("geometry", JSON.readValue(geo, Map.class));
                } catch (Exception e) {
                    log.warn("GeoJSON 解析失败 ds={} id={}: {}", ds, id, e.getMessage());
                }
            }
        }
        return out;
    }

    /** 保存一行的空间几何（GeoJSON geometry）；隐患点同步回写 lgtd/lttd */
    public void updateGeometry(String ds, long id, Map<String, Object> geometry) {
        DsConf d = requireDs(ds);
        if (d.geomCol() == null) {
            throw new IllegalArgumentException("该数据集无空间几何列: " + ds);
        }
        if (geometry == null || geometry.get("type") == null) {
            throw new IllegalArgumentException("缺少 GeoJSON geometry");
        }
        String type = String.valueOf(geometry.get("type"));
        List<String> allowed = GEOM_TYPES.get(d.key());
        if (allowed == null || !allowed.contains(type)) {
            throw new IllegalArgumentException("该图层几何类型仅允许 " + allowed + "，收到 " + type);
        }
        validateCoords(geometry.get("coordinates"));
        String json;
        try {
            json = JSON.writeValueAsString(geometry);
        } catch (Exception e) {
            throw new IllegalArgumentException("GeoJSON 序列化失败: " + e.getMessage());
        }
        pgQueryService.update("UPDATE " + d.table() + " SET " + d.geomCol()
                + " = ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) WHERE " + d.idCol() + " = ?", json, id);
        if ("hazard".equals(d.key())) {
            pgQueryService.update("UPDATE " + d.table()
                    + " SET lgtd = ST_X(geom), lttd = ST_Y(geom) WHERE " + d.idCol() + " = ?", id);
        }
    }

    /** 递归校验坐标结构：数组的数组…最内层为至少两位的有限数值（经纬度） */
    private void validateCoords(Object coords) {
        if (!(coords instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("GeoJSON 坐标格式不正确");
        }
        if (list.get(0) instanceof List) {
            for (Object c : list) {
                validateCoords(c);
            }
        } else {
            if (list.size() < 2) {
                throw new IllegalArgumentException("坐标点至少需要经纬度两个值");
            }
            for (Object v : list) {
                if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                    throw new IllegalArgumentException("坐标值必须是数字");
                }
            }
        }
    }

    /** 各数据集用于外部关联的编码列：流域 wscd / 断面、隐患点 pid / 防治对象 obj_pid */
    private String pidColOf(DsConf d) {
        return switch (d.key()) {
            case "watershed" -> "wscd";
            case "section", "hazard" -> "pid";
            case "prevention" -> "obj_pid";
            default -> null;
        };
    }

    /**
     * 按编码查空间图层几何（GeoJSON geometry），无匹配时 geometry 为 null。
     * 例如：调查成果的防治对象名录用 15 位政区编码（fcd）关联防治对象面（obj_pid）。
     */
    public Map<String, Object> findGeometryByPid(String ds, String pid) {
        DsConf d = requireDs(ds);
        if (d.geomCol() == null) {
            throw new IllegalArgumentException("该数据集无空间几何列: " + ds);
        }
        String pidCol = pidColOf(d);
        if (pidCol == null) {
            throw new IllegalArgumentException("该数据集不支持按编码关联: " + ds);
        }
        String code = pid == null ? "" : pid.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("缺少关联编码");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ds", ds);
        out.put("pid", code);
        out.put("geometry", null);
        List<Map<String, Object>> rows = pgQueryService.queryForList(
                "SELECT " + d.idCol() + " AS rid, ST_AsGeoJSON(" + d.geomCol() + ") AS geo FROM " + d.table()
                        + " WHERE " + pidCol + " = ? AND " + d.geomCol() + " IS NOT NULL LIMIT 1", code);
        if (!rows.isEmpty()) {
            out.put("id", rows.get(0).get("rid"));
            String geo = ShReportMapGeometry.str(rows.get(0).get("geo"));
            if (geo != null && !geo.isEmpty()) {
                try {
                    out.put("geometry", JSON.readValue(geo, Map.class));
                } catch (Exception e) {
                    log.warn("按编码查几何解析失败 ds={} pid={}: {}", ds, code, e.getMessage());
                }
            }
        }
        return out;
    }

    private Double toNum(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
