package org.jeecg.modules.airag.shreport.map;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 山洪一张图：从 PostGIS 空间库（sh_report_import 导入的 9 张表）组装
 * 筛选选项、GeoJSON、分页列表、详情与断面剖面。
 * 单表查询容错，缺表或 pg 未配置时返回空结果，不阻断页面；
 * 流域面表为空时回退到旧调查点凸包兜底。
 */
@Slf4j
@Service
public class ShReportMapService {

    private static final ObjectMapper JSON = new ObjectMapper();

    static final Map<String, String> SECTION_TYPE = Map.of("A", "横断面", "B", "纵断面");

    /** 展示范围包络：个别数据经纬度越界会把视野拉偏，仅返回中国范围内的几何（宽松取 lng 70~140、lat 10~60） */
    private static final String CHINA_ENVELOPE = "ST_MakeEnvelope(70, 10, 140, 60, 4326)";

    static final Map<String, String> COMMENT_WATERSHED = commentMap(
            "wscd", "小流域编码", "wsnm", "小流域名称", "alias_name", "沟名",
            "river_name", "河流名称", "wsarea", "面积(km²)", "wsperi", "周长(km)",
            "wsslp", "坡度", "outlet_lng", "出口经度", "outlet_lat", "出口纬度",
            "outlet_elv", "出口高程(m)", "max_elv", "最高点高程(m)",
            "jizhen_count", "集镇个数", "village_count", "防治村个数",
            "gully_count", "山洪沟道条数", "reservoirs", "涉及水库");

    static final Map<String, String> COMMENT_SECTION = commentMap(
            "pid", "断面编码", "sec_type_label", "断面类型", "sec_name", "断面名称",
            "rvname", "河流名称", "rvcd", "河流代码", "wsnm", "小流域名称", "wscd", "小流域编码");

    static final Map<String, String> COMMENT_PREVENTION_AREA = commentMap(
            "obj_pid", "防护对象代码", "obj_name", "防护对象名称", "obj_type", "防护对象类型",
            "pcount", "人口", "rvname", "河流名称", "rvcd", "河流代码",
            "wsnm", "小流域名称", "wscd", "小流域编码",
            "county_name", "所属县区", "town_name", "所属乡镇", "comments", "备注");

    static final Map<String, String> COMMENT_HAZARD_POINT = commentMap(
            "pid", "隐患要素编码", "name", "名称", "hz_type", "隐患类型",
            "rvname", "河流名称", "rvcd", "河流代码", "fcd", "防护对象代码",
            "fnm", "防护对象名称", "wsnm", "小流域名称", "wscd", "小流域编码",
            "lng", "经度", "lat", "纬度", "photos", "现场照片", "videos", "现场视频");

    /**
     * 一张图 PostgreSQL 查询统一走 {@link ShReportPgQueryService}
     * （@DS("shreport") 路由到 spring.datasource.dynamic 中的 shreport 数据源，复用全局 Druid 池）。
     */

    /** 旧调查表（MySQL），仅用于流域面表为空时的凸包兜底；Spring Boot 自动配置的 @Primary JdbcTemplate */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${shreport.map.tianditu-key:fced2cd1905b5b42d4167603c6ba32e2}")
    private String tiandituKey;

    @Value("${shreport.map.center-lat:35.6}")
    private double centerLat;

    @Value("${shreport.map.center-lng:104.2}")
    private double centerLng;

    @Value("${shreport.map.zoom:4}")
    private int zoom;

    @Autowired
    private ShReportPgQueryService pgQueryService;

    public Map<String, Object> config() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tiandituKey", tiandituKey == null ? "" : tiandituKey);
        out.put("center", List.of(centerLat, centerLng));
        out.put("zoom", zoom);
        return out;
    }

    public Map<String, Object> filters(String countyAdcd) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("watersheds", listWatersheds());
        out.put("counties", listAdmins("county_adcd", "county_name", null, 6));
        out.put("towns", listAdmins("town_adcd", "town_name", countyAdcd, 9));
        return out;
    }

    public Map<String, Object> geojson(String layer, String wscd, String adcd, String types, String bbox) {
        Set<String> typeSet = parseTypes(types);
        double[] box = parseBbox(bbox);
        String ly = layer == null ? "" : layer.trim().toLowerCase();
        switch (ly) {
            case "watershed":
            case "watersheds":
                return watershedGeojson(wscd, adcd);
            case "section":
            case "sections":
                return sectionGeojson(wscd, adcd, typeSet, box);
            case "prevention":
            case "prevention_objects":
                return preventionGeojson(wscd, adcd, typeSet);
            case "hazard":
            case "hazards":
            case "hazard_elements":
                return hazardGeojson(wscd, adcd, typeSet);
            default:
                throw new IllegalArgumentException("layer 仅允许 watershed / section / prevention / hazard");
        }
    }

    // ---------------------------------------------------------------- 图层 GeoJSON

    private Map<String, Object> watershedGeojson(String wscd, String adcd) {
        StringBuilder sql = new StringBuilder(
                "SELECT wscd, wsnm, alias_name, river_name, wsarea, wsperi, wsslp, "
                        + "outlet_lng, outlet_lat, outlet_elv, max_elv, jizhen_count, village_count, "
                        + "gully_count, reservoirs, ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, 0.0005)) AS geo "
                        + "FROM sh_map_watershed WHERE geom IS NOT NULL AND ST_Intersects(geom, " + CHINA_ENVELOPE + ") ");
        List<Object> args = new ArrayList<>();
        if (wscd != null && !wscd.isBlank()) {
            sql.append("AND wscd = ? ");
            args.add(wscd.trim());
        }
        if (adcd != null && !adcd.isBlank()) {
            sql.append("AND wscd IN (SELECT wscd FROM sh_survey_object "
                    + "WHERE county_adcd LIKE ? OR town_adcd LIKE ?) ");
            String prefix = adcd.trim() + "%";
            args.add(prefix);
            args.add(prefix);
        }
        List<Map<String, Object>> rows = query(sql.toString(), args.toArray());
        if (rows.isEmpty() && queryInt("SELECT COUNT(*) FROM sh_map_watershed") == 0) {
            // PostGIS 流域面未导入时回退旧调查点凸包
            return legacyWatershedGeojson(wscd, adcd);
        }
        List<Map<String, Object>> features = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("id", "ws-" + r.get("wscd"));
            props.put("wscd", r.get("wscd"));
            props.put("lybm", r.get("wscd"));
            props.put("wsnm", nz(r.get("wsnm"), str(r.get("wscd"))));
            props.put("lymc", props.get("wsnm"));
            props.put("alias_name", r.get("alias_name"));
            props.put("river_name", r.get("river_name"));
            props.put("wsarea", r.get("wsarea"));
            props.put("wsperi", r.get("wsperi"));
            props.put("wsslp", r.get("wsslp"));
            props.put("layer", "watershed");
            props.put("title", "小流域范围");
            features.add(geometryFeature(props, str(r.get("geo"))));
        }
        return ShReportMapGeometry.featureCollection(features);
    }

    private Map<String, Object> sectionGeojson(String wscd, String adcd, Set<String> types, double[] box) {
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.pid, s.sec_type, s.sec_name, s.rvname, s.rvcd, s.wscd, w.wsnm, "
                        + "ST_AsGeoJSON(s.geom) AS geo FROM sh_map_section s "
                        + "LEFT JOIN sh_map_watershed w ON w.wscd = s.wscd "
                        + "WHERE s.geom IS NOT NULL AND ST_Intersects(s.geom, " + CHINA_ENVELOPE + ") ");
        List<Object> args = new ArrayList<>();
        if (wscd != null && !wscd.isBlank()) {
            sql.append("AND s.wscd = ? ");
            args.add(wscd.trim());
        }
        if (adcd != null && !adcd.isBlank()) {
            sql.append("AND s.wscd IN (SELECT wscd FROM sh_survey_object "
                    + "WHERE county_adcd LIKE ? OR town_adcd LIKE ?) ");
            String prefix = adcd.trim() + "%";
            args.add(prefix);
            args.add(prefix);
        }
        appendTypeFilter(sql, args, "s.sec_type", types);
        appendBboxFilter(sql, args, "s.geom", box);
        List<Map<String, Object>> features = new ArrayList<>();
        for (Map<String, Object> r : query(sql.toString(), args.toArray())) {
            String type = str(r.get("sec_type"));
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("id", String.valueOf(r.get("id")));
            props.put("pid", r.get("pid"));
            props.put("sec_type", type);
            props.put("sec_type_label", SECTION_TYPE.getOrDefault(type, type));
            props.put("name", r.get("sec_name"));
            props.put("rvname", r.get("rvname"));
            props.put("rvcd", r.get("rvcd"));
            props.put("wscd", r.get("wscd"));
            props.put("wsnm", r.get("wsnm"));
            props.put("types", SECTION_TYPE.getOrDefault(type, type));
            props.put("layer", "section");
            features.add(geometryFeature(props, str(r.get("geo"))));
        }
        return ShReportMapGeometry.featureCollection(features);
    }

    private Map<String, Object> preventionGeojson(String wscd, String adcd, Set<String> types) {
        StringBuilder sql = new StringBuilder(
                "SELECT p.id, p.obj_pid, p.obj_name, p.obj_type, p.pcount, p.rvname, p.rvcd, p.wscd, w.wsnm, "
                        + "ST_AsGeoJSON(p.geom) AS geo FROM sh_map_prevention_area p "
                        + "LEFT JOIN sh_map_watershed w ON w.wscd = p.wscd "
                        + "WHERE p.geom IS NOT NULL AND ST_Intersects(p.geom, " + CHINA_ENVELOPE + ") ");
        List<Object> args = new ArrayList<>();
        appendWscdAdcd(sql, args, wscd, adcd, "p.wscd", "p.obj_pid");
        appendTypeFilter(sql, args, "p.obj_type", types);
        List<Map<String, Object>> features = new ArrayList<>();
        for (Map<String, Object> r : query(sql.toString(), args.toArray())) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("id", String.valueOf(r.get("id")));
            props.put("pid", r.get("obj_pid"));
            props.put("fcd", r.get("obj_pid"));
            props.put("name", r.get("obj_name"));
            props.put("fnm", r.get("obj_name"));
            props.put("types", r.get("obj_type"));
            props.put("pcount", r.get("pcount"));
            props.put("rvname", r.get("rvname"));
            props.put("rvcd", r.get("rvcd"));
            props.put("wscd", r.get("wscd"));
            props.put("wsnm", r.get("wsnm"));
            props.put("layer", "prevention");
            features.add(geometryFeature(props, str(r.get("geo"))));
        }
        return ShReportMapGeometry.featureCollection(features);
    }

    private Map<String, Object> hazardGeojson(String wscd, String adcd, Set<String> types) {
        StringBuilder sql = new StringBuilder(
                "SELECT h.id, h.pid, h.name, h.hz_type, h.rvname, h.rvcd, h.wscd, h.fcd, h.fnm, h.photos, w.wsnm, "
                        + "ST_X(h.geom) AS lng, ST_Y(h.geom) AS lat, ST_AsGeoJSON(h.geom) AS geo "
                        + "FROM sh_map_hazard_point h "
                        + "LEFT JOIN sh_map_watershed w ON w.wscd = h.wscd "
                        + "WHERE h.geom IS NOT NULL AND ST_X(h.geom) BETWEEN 70 AND 140 AND ST_Y(h.geom) BETWEEN 10 AND 60 ");
        List<Object> args = new ArrayList<>();
        appendWscdAdcd(sql, args, wscd, adcd, "h.wscd", "h.fcd");
        appendTypeFilter(sql, args, "h.hz_type", types);
        List<Map<String, Object>> features = new ArrayList<>();
        for (Map<String, Object> r : query(sql.toString(), args.toArray())) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("id", String.valueOf(r.get("id")));
            props.put("pid", r.get("pid"));
            props.put("name", r.get("name"));
            props.put("types", r.get("hz_type"));
            props.put("type_label", r.get("hz_type"));
            props.put("rvname", r.get("rvname"));
            props.put("rvcd", r.get("rvcd"));
            props.put("wscd", r.get("wscd"));
            props.put("wsnm", r.get("wsnm"));
            props.put("fcd", r.get("fcd"));
            props.put("fnm", r.get("fnm"));
            props.put("photos", r.get("photos"));
            props.put("lgtd", r.get("lng"));
            props.put("lttd", r.get("lat"));
            props.put("layer", "hazard");
            features.add(geometryFeature(props, str(r.get("geo"))));
        }
        return ShReportMapGeometry.featureCollection(features);
    }

    // ---------------------------------------------------------------- 分页列表 / 详情

    public Map<String, Object> listPrevention(int pageNo, int pageSize, String search, String wscd, String adcd) {
        String where = " WHERE 1=1 ";
        List<Object> args = new ArrayList<>();
        if (wscd != null && !wscd.isBlank()) {
            where += " AND p.wscd = ? ";
            args.add(wscd.trim());
        }
        if (adcd != null && !adcd.isBlank()) {
            where += " AND p.obj_pid IN (SELECT fcd FROM sh_survey_object WHERE county_adcd LIKE ? OR town_adcd LIKE ?) ";
            String prefix = adcd.trim() + "%";
            args.add(prefix);
            args.add(prefix);
        }
        if (search != null && !search.isBlank()) {
            where += " AND (p.obj_name ILIKE ? OR p.rvname ILIKE ?) ";
            String q = "%" + search.trim() + "%";
            args.add(q);
            args.add(q);
        }
        return pagedQuery("SELECT p.id, p.obj_pid, p.obj_name AS name, p.obj_type AS types, p.pcount, "
                + "p.rvname, p.rvcd, p.wscd, w.wsnm FROM sh_map_prevention_area p "
                + "LEFT JOIN sh_map_watershed w ON w.wscd = p.wscd" + where, args, pageNo, pageSize);
    }

    public Map<String, Object> getPrevention(String id) {
        List<Map<String, Object>> rows = query(
                "SELECT p.id, p.obj_pid, p.obj_name, p.obj_type, p.pcount, p.rvname, p.rvcd, p.wscd, w.wsnm, "
                        + "o.risk_flags, o.comments, o.county_name, o.town_name "
                        + "FROM sh_map_prevention_area p "
                        + "LEFT JOIN sh_map_watershed w ON w.wscd = p.wscd "
                        + "LEFT JOIN sh_survey_object o ON o.fcd = p.obj_pid WHERE CAST(p.id AS TEXT) = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>(cleanRow(rows.get(0)));
        d.put("comment_map", COMMENT_PREVENTION_AREA);
        // 关联调查成果（一张图数据维护的「防治对象名录」）：按政区编码（obj_pid = 调查成果 fcd）映射，
        // 同一编码多年调查时取调查年份最新的一条
        String objPid = str(d.get("obj_pid"));
        if (objPid != null && !objPid.isBlank()) {
            List<Map<String, Object>> survey = query(
                    "SELECT * FROM sh_survey_object WHERE fcd = ? "
                            + "ORDER BY survey_year DESC NULLS LAST, id DESC LIMIT 1", objPid);
            if (!survey.isEmpty()) {
                d.put("survey", cleanRow(survey.get(0)));
                d.put("survey_type", "object");
            }
        }
        return d;
    }

    public Map<String, Object> listHazards(int pageNo, int pageSize, String search, String wscd, String adcd, String types) {
        StringBuilder where = new StringBuilder(
                " WHERE h.geom IS NOT NULL AND ST_X(h.geom) BETWEEN 70 AND 140 AND ST_Y(h.geom) BETWEEN 10 AND 60 ");
        List<Object> args = new ArrayList<>();
        appendTypeFilter(where, args, "h.hz_type", parseTypes(types));
        if (wscd != null && !wscd.isBlank()) {
            where.append(" AND h.wscd = ? ");
            args.add(wscd.trim());
        }
        if (adcd != null && !adcd.isBlank()) {
            where.append(" AND h.fcd IN (SELECT fcd FROM sh_survey_object WHERE county_adcd LIKE ? OR town_adcd LIKE ?) ");
            String prefix = adcd.trim() + "%";
            args.add(prefix);
            args.add(prefix);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (h.name ILIKE ? OR h.rvname ILIKE ?) ");
            String q = "%" + search.trim() + "%";
            args.add(q);
            args.add(q);
        }
        return pagedQuery("SELECT h.id, h.pid, h.name, h.hz_type AS types, h.rvname, h.rvcd, h.wscd, h.fcd, "
                + "h.fnm, w.wsnm, ST_X(h.geom) AS lng, ST_Y(h.geom) AS lat FROM sh_map_hazard_point h "
                + "LEFT JOIN sh_map_watershed w ON w.wscd = h.wscd" + where, args, pageNo, pageSize);
    }

    public Map<String, Object> getHazard(String id) {
        List<Map<String, Object>> rows = query(
                "SELECT h.id, h.pid, h.name, h.hz_type, h.rvname, h.rvcd, h.wscd, h.fcd, h.fnm, h.photos, h.videos, w.wsnm, "
                        + "ST_X(h.geom) AS lng, ST_Y(h.geom) AS lat FROM sh_map_hazard_point h "
                        + "LEFT JOIN sh_map_watershed w ON w.wscd = h.wscd WHERE CAST(h.id AS TEXT) = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>(cleanRow(rows.get(0)));
        // 关联调查成果：跨沟道路/桥涵/塘坝、沟滩占地按编码匹配，补充沟宽沟深、阻水比、影响对象等
        String pid = str(d.get("pid"));
        List<Map<String, Object>> road = query("SELECT * FROM sh_survey_road WHERE code = ?", pid);
        if (!road.isEmpty()) {
            d.put("survey", cleanRow(road.get(0)));
            d.put("survey_type", "road");
        } else {
            List<Map<String, Object>> gully = query("SELECT * FROM sh_survey_gully WHERE code = ?", pid);
            if (!gully.isEmpty()) {
                d.put("survey", cleanRow(gully.get(0)));
                d.put("survey_type", "gully");
            }
        }
        d.put("comment_map", COMMENT_HAZARD_POINT);
        return d;
    }

    /** 按隐患点编码取详情，内容与 {@link #getHazard(String)} 相同 */
    public Map<String, Object> getHazardByPid(String pid) {
        if (pid == null || pid.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = query(
                "SELECT CAST(h.id AS TEXT) AS id FROM sh_map_hazard_point h WHERE h.pid = ? ORDER BY h.id DESC LIMIT 1",
                pid.trim());
        if (rows.isEmpty()) {
            return null;
        }
        return getHazard(str(rows.get(0).get("id")));
    }

    public Map<String, Object> getSection(String id) {
        List<Map<String, Object>> rows = query(
                "SELECT s.*, w.wsnm, ST_AsGeoJSON(s.geom) AS geo FROM sh_map_section s "
                        + "LEFT JOIN sh_map_watershed w ON w.wscd = s.wscd WHERE CAST(s.id AS TEXT) = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>(cleanRow(rows.get(0)));
        d.put("sec_type_label", SECTION_TYPE.getOrDefault(str(d.get("sec_type")), str(d.get("sec_type"))));
        d.put("has_cross", queryInt("SELECT COUNT(*) FROM sh_section_cross_point WHERE pid = ?", str(d.get("pid"))) > 0);
        d.put("has_long", queryInt("SELECT COUNT(*) FROM sh_section_long_point WHERE pid = ?", str(d.get("pid"))) > 0);
        d.put("comment_map", COMMENT_SECTION);
        return d;
    }

    /** 断面剖面数据：横断面（起点距-高程）或纵断面（距离-河底/水面高程） */
    public Map<String, Object> sectionProfile(String pid) {
        String code = pid == null ? "" : pid.trim();
        if (code.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> meta = query(
                "SELECT s.pid, s.sec_type, s.sec_name, s.rvname, s.wscd, w.wsnm FROM sh_map_section s "
                        + "LEFT JOIN sh_map_watershed w ON w.wscd = s.wscd WHERE s.pid = ?", code);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!meta.isEmpty()) {
            Map<String, Object> m = meta.get(0);
            out.put("pid", m.get("pid"));
            out.put("secType", m.get("sec_type"));
            out.put("secTypeLabel", SECTION_TYPE.getOrDefault(str(m.get("sec_type")), str(m.get("sec_type"))));
            out.put("secName", m.get("sec_name"));
            out.put("rvname", m.get("rvname"));
            out.put("wscd", m.get("wscd"));
            out.put("wsnm", m.get("wsnm"));
        } else {
            out.put("pid", code);
        }
        List<Map<String, Object>> cross = query("SELECT point_no, point_desc, dist, elev, roughness, lgtd, lttd "
                + "FROM sh_section_cross_point WHERE pid = ? ORDER BY point_no, dist", code);
        if (!cross.isEmpty()) {
            out.put("kind", "cross");
            out.put("points", cross.stream().map(this::cleanRow).collect(Collectors.toList()));
            return out;
        }
        List<Map<String, Object>> lng = query("SELECT point_no, point_desc, dist, bed_elev, water_elev, lgtd, lttd "
                + "FROM sh_section_long_point WHERE pid = ? ORDER BY point_no, dist", code);
        if (!lng.isEmpty()) {
            out.put("kind", "long");
            out.put("points", lng.stream().map(this::cleanRow).collect(Collectors.toList()));
            return out;
        }
        out.put("kind", "none");
        out.put("points", List.of());
        return out;
    }

    // ---------------------------------------------------------------- 筛选 / 统计

    List<Map<String, Object>> listWatersheds() {
        List<Map<String, Object>> rows = query(
                "SELECT w.wscd, w.wsnm, w.wsarea, w.alias_name, "
                        + "ST_XMin(w.geom) AS minLng, ST_YMin(w.geom) AS minLat, "
                        + "ST_XMax(w.geom) AS maxLng, ST_YMax(w.geom) AS maxLat, "
                        + "COALESCE(o.objectCount, 0) AS objectCount, COALESCE(o.population, 0) AS population "
                        + "FROM sh_map_watershed w "
                        + "LEFT JOIN (SELECT wscd, COUNT(*) AS objectCount, SUM(pcount) AS population "
                        + "           FROM sh_survey_object GROUP BY wscd) o ON o.wscd = w.wscd "
                        + "WHERE w.geom IS NOT NULL AND ST_Intersects(w.geom, " + CHINA_ENVELOPE + ") "
                        + "ORDER BY w.wscd");
        if (rows.isEmpty() && queryInt("SELECT COUNT(*) FROM sh_map_watershed") == 0) {
            return legacyListWatersheds();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wscd", r.get("wscd"));
            row.put("wsnm", nz(r.get("wsnm"), str(r.get("wscd"))));
            row.put("aliasName", r.get("alias_name"));
            row.put("wsarea", r.get("wsarea"));
            row.put("objectCount", num(r.get("objectCount")));
            row.put("population", num(r.get("population")));
            Map<String, Object> bbox = new LinkedHashMap<>();
            bbox.put("minLng", r.get("minLng"));
            bbox.put("minLat", r.get("minLat"));
            bbox.put("maxLng", r.get("maxLng"));
            bbox.put("maxLat", r.get("maxLat"));
            row.put("bbox", bbox);
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> listAdmins(String codeCol, String nameCol, String parentPrefix, int minLen) {
        List<Map<String, Object>> rows = query(
                "SELECT DISTINCT " + codeCol + " AS code, " + nameCol + " AS name FROM sh_survey_object "
                        + "WHERE " + codeCol + " IS NOT NULL AND TRIM(" + codeCol + ")<>''");
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String code = ShReportMapGeometry.str(r.get("code"));
            if (code == null || code.length() < minLen) {
                continue;
            }
            if (parentPrefix != null && !parentPrefix.isBlank() && !code.startsWith(parentPrefix.trim())) {
                continue;
            }
            String key = code.length() > minLen ? code.substring(0, minLen) : code;
            if (uniq.containsKey(key)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("adcd", key);
            item.put("name", nz(r.get("name"), key));
            item.put("level", minLen);
            uniq.put(key, item);
        }
        return new ArrayList<>(uniq.values());
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("watershedCount", queryInt("SELECT COUNT(*) FROM sh_map_watershed"));
        int sectionCount = queryInt("SELECT COUNT(*) FROM sh_map_section WHERE geom IS NOT NULL");
        out.put("sectionCount", sectionCount);
        out.put("preventionCount", queryInt("SELECT COUNT(*) FROM sh_map_prevention_area WHERE geom IS NOT NULL"));
        out.put("hazardCount", queryInt("SELECT COUNT(*) FROM sh_map_hazard_point WHERE geom IS NOT NULL"));
        out.put("crossPointCount", queryInt("SELECT COUNT(*) FROM sh_section_cross_point"));
        out.put("longPointCount", queryInt("SELECT COUNT(*) FROM sh_section_long_point"));
        out.put("surveyObjectCount", queryInt("SELECT COUNT(*) FROM sh_survey_object"));

        Map<String, Integer> sectionByType = new LinkedHashMap<>();
        for (Map<String, Object> r : query("SELECT sec_type, COUNT(*) AS n FROM sh_map_section GROUP BY sec_type")) {
            String t = str(r.get("sec_type"));
            sectionByType.put(SECTION_TYPE.getOrDefault(t, t == null ? "未知" : t), num(r.get("n")));
        }
        out.put("sectionByType", sectionByType);

        Map<String, Integer> preventionByType = new LinkedHashMap<>();
        for (Map<String, Object> r : query("SELECT obj_type, COUNT(*) AS n FROM sh_map_prevention_area "
                + "GROUP BY obj_type ORDER BY n DESC")) {
            preventionByType.put(nz(r.get("obj_type"), "未知"), num(r.get("n")));
        }
        out.put("preventionByType", preventionByType);

        Map<String, Integer> hazardByType = new LinkedHashMap<>();
        for (Map<String, Object> r : query("SELECT hz_type, COUNT(*) AS n FROM sh_map_hazard_point "
                + "GROUP BY hz_type ORDER BY n DESC")) {
            hazardByType.put(nz(r.get("hz_type"), "未知"), num(r.get("n")));
        }
        out.put("hazardByType", hazardByType);
        return out;
    }

    // ---------------------------------------------------------------- 旧调查表凸包兜底

    private Map<String, Object> legacyWatershedGeojson(String wscd, String adcd) {
        Map<String, List<double[]>> pts = collectCoordsByWscd(wscd, adcd);
        List<Map<String, Object>> features = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> e : pts.entrySet()) {
            List<List<double[]>> rings = ShReportMapGeometry.hullOrBbox(e.getValue());
            if (rings.isEmpty()) {
                continue;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("wscd", e.getKey());
            props.put("lybm", e.getKey());
            props.put("wsnm", e.getKey());
            props.put("lymc", e.getKey());
            props.put("layer", "watershed");
            props.put("title", "流域范围（调查点外包）");
            features.add(ShReportMapGeometry.polygonFeature(props, rings));
        }
        return ShReportMapGeometry.featureCollection(features);
    }

    private Map<String, List<double[]>> collectCoordsByWscd(String wscd, String adcd) {
        Map<String, List<double[]>> map = new LinkedHashMap<>();
        collectCoords(map, "prevention_object", wscd, adcd);
        collectCoords(map, "road_culvert_bridge_dam", wscd, adcd);
        collectCoords(map, "gully_occupy_area", wscd, adcd);
        return map;
    }

    private void collectCoords(Map<String, List<double[]>> map, String table, String wscd, String adcd) {
        StringBuilder sql = new StringBuilder("SELECT wscd, lgtd, lttd FROM " + table + " WHERE lgtd IS NOT NULL AND lttd IS NOT NULL ");
        List<Object> args = new ArrayList<>();
        if (wscd != null && !wscd.isBlank()) {
            sql.append("AND wscd = ? ");
            args.add(wscd.trim());
        }
        if (adcd != null && !adcd.isBlank()) {
            sql.append("AND (county_adcd LIKE ? OR town_adcd LIKE ?) ");
            String prefix = adcd.trim() + "%";
            args.add(prefix);
            args.add(prefix);
        }
        for (Map<String, Object> r : legacyQuery(sql.toString(), args.toArray())) {
            String ws = ShReportMapGeometry.str(r.get("wscd"));
            if (ws == null || ws.isEmpty()) {
                continue;
            }
            Double lng = ShReportMapGeometry.parseCoord(r.get("lgtd"));
            Double lat = ShReportMapGeometry.parseCoord(r.get("lttd"));
            if (ShReportMapGeometry.validLngLat(lng, lat)) {
                map.computeIfAbsent(ws, k -> new ArrayList<>()).add(new double[]{lng, lat});
            }
        }
    }

    List<Map<String, Object>> legacyListWatersheds() {
        Map<String, List<double[]>> pts = collectCoordsByWscd(null, null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<double[]>> e : pts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wscd", e.getKey());
            row.put("wsnm", e.getKey());
            row.put("objectCount", 0);
            row.put("population", 0);
            row.put("bbox", ShReportMapGeometry.bboxOf(e.getValue()));
            out.add(row);
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private void appendWscdAdcd(StringBuilder sql, List<Object> args, String wscd, String adcd,
                                String wscdCol, String joinCol) {
        if (wscd != null && !wscd.isBlank()) {
            sql.append("AND ").append(wscdCol).append(" = ? ");
            args.add(wscd.trim());
        }
        if (adcd != null && !adcd.isBlank()) {
            sql.append("AND ").append(joinCol).append(" IN (SELECT fcd FROM sh_survey_object "
                    + "WHERE county_adcd LIKE ? OR town_adcd LIKE ?) ");
            String prefix = adcd.trim() + "%";
            args.add(prefix);
            args.add(prefix);
        }
    }

    private void appendTypeFilter(StringBuilder sql, List<Object> args, String col, Set<String> types) {
        if (types.isEmpty()) {
            return;
        }
        sql.append("AND ").append(col).append(" = ANY(?) ");
        args.add(types.toArray(new String[0]));
    }

    private void appendBboxFilter(StringBuilder sql, List<Object> args, String geomCol, double[] box) {
        if (box == null) {
            return;
        }
        sql.append("AND ").append(geomCol).append(" && ST_MakeEnvelope(?, ?, ?, ?, 4326) ");
        args.add(box[0]);
        args.add(box[1]);
        args.add(box[2]);
        args.add(box[3]);
    }

    /** ST_AsGeoJSON 结果 -> Feature。解析成普通 Map（容器用 fastjson 序列化，
     *  不能放 Jackson JsonNode，否则几何会被序列化成空对象）。 */
    private Map<String, Object> geometryFeature(Map<String, Object> props, String geo) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("properties", props);
        Object geometry = null;
        try {
            geometry = JSON.readValue(geo == null ? "" : geo, Map.class);
        } catch (Exception e) {
            log.debug("GeoJSON 解析失败: {}", e.getMessage());
        }
        f.put("geometry", geometry);
        return f;
    }

    private Map<String, Object> pagedQuery(String baseSql, List<Object> args, int pageNo, int pageSize) {
        int pn = Math.max(pageNo, 1);
        int ps = Math.min(Math.max(pageSize, 1), 200);
        String countSql = "SELECT COUNT(*) FROM (" + baseSql + ") t";
        int total = queryInt(countSql, args.toArray());
        String pageSql = baseSql + " LIMIT " + ps + " OFFSET " + ((pn - 1) * ps);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : query(pageSql, args.toArray())) {
            data.add(cleanRow(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("pageNo", pn);
        out.put("pageSize", ps);
        out.put("data", data);
        return out;
    }

    /** 行数据清洗：数值/JSONB 数组转可序列化类型，剔 geometry 之外不需要的二进制列 */
    private Map<String, Object> cleanRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : row.entrySet()) {
            Object v = e.getValue();
            if (v instanceof java.sql.Array arr) {
                try {
                    v = Arrays.asList((Object[]) arr.getArray());
                } catch (Exception ex) {
                    v = null;
                }
            } else if (v instanceof java.util.UUID || v instanceof byte[]) {
                v = String.valueOf(v);
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    private List<Map<String, Object>> query(String sql, Object... args) {
        try {
            List<Map<String, Object>> rows = pgQueryService.queryForList(sql, args);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.debug("一张图 PostGIS 查询失败（降级为空）：{} | {}", sql, e.getMessage());
            return List.of();
        }
    }

    private int queryInt(String sql, Object... args) {
        try {
            return pgQueryService.queryInt(sql, args);
        } catch (Exception e) {
            log.debug("一张图 PostGIS 统计失败（按 0 处理）：{} | {}", sql, e.getMessage());
            return 0;
        }
    }

    private List<Map<String, Object>> legacyQuery(String sql, Object... args) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.debug("旧调查表查询失败（降级为空）：{} | {}", sql, e.getMessage());
            return List.of();
        }
    }

    private static double[] parseBbox(String bbox) {
        if (bbox == null || bbox.isBlank()) {
            return null;
        }
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            return null;
        }
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) {
            Double d = ShReportMapGeometry.parseCoord(parts[i]);
            if (d == null) {
                return null;
            }
            v[i] = d;
        }
        return v;
    }

    private static Set<String> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(types.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String str(Object v) {
        return ShReportMapGeometry.str(v);
    }

    private static String nz(Object v, String fallback) {
        String s = ShReportMapGeometry.str(v);
        return (s == null || s.isEmpty()) ? fallback : s;
    }

    private static int num(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(v)));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Map<String, String> commentMap(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return Map.copyOf(m);
    }
}
