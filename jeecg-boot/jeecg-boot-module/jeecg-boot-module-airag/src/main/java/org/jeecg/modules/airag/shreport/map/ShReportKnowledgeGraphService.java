package org.jeecg.modules.airag.shreport.map;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 山洪知识图谱：把小流域、政区、河流、防治对象、断面、隐患和调查成果连成一张图。
 * 不传流域时返回对象类型和关系（总览）；传入小流域编码时展开该流域下的具体对象。
 * 表名与列名均为常量，查询值全部参数化。
 */
@Slf4j
@Service
public class ShReportKnowledgeGraphService {

    private static final int CAP = 80;
    private static final int SEARCH_WS = 12;

    private record Cat(String key, String name, String ds, String blurb, int size) {
    }

    private static final List<Cat> CATS = List.of(
            new Cat("watershed", "小流域", "watershed", "一张图的核心。断面、防治对象、隐患和调查成果都挂在小流域编码上。", 78),
            new Cat("river", "河流", null, "小流域、断面和防治对象上填写的河流。", 54),
            new Cat("county", "县", null, "调查成果里的县级政区，下辖乡镇，并涉及若干小流域。", 50),
            new Cat("town", "乡镇", null, "防治对象、跨沟道路和沟滩占地所在的乡镇。", 46),
            new Cat("prevention", "防治对象", "prevention", "需要防山洪的村、集镇等。空间面和调查名录按防护对象代码合成一个点。", 58),
            new Cat("section", "断面", "section", "小流域上的横断面、纵断面，用来描述河床形态。", 48),
            new Cat("hazard", "隐患要素", "hazard", "沟道里的桥涵、塘坝等风险点，按防护对象代码指向受威胁的对象。", 52),
            new Cat("road", "跨沟道路", "survey_road", "阻水的路、桥、塘坝，并记录壅水、溃决、漫溢会影响哪些对象。", 48),
            new Cat("gully", "沟滩占地", "survey_gully", "沟道里被占用的滩地。", 46)
    );

    @Autowired
    private ShReportPgQueryService pgQueryService;

    public Map<String, Object> graph(String wscd, String q, Integer year) {
        String code = blank(wscd);
        String keyword = blank(q);
        Map<String, Object> out;
        if (code != null) {
            out = focus(code, keyword, year);
        } else if (keyword != null) {
            Set<String> codes = collectCodes(keyword, year);
            if (codes.size() == 1) {
                out = focus(codes.iterator().next(), keyword, year);
            } else if (!codes.isEmpty()) {
                out = sketch(codes, keyword, year);
            } else {
                out = overview(year);
                out.put("hint", "没有找到「" + keyword + "」，下面仍是对象关系总览。");
            }
        } else {
            out = overview(year);
        }
        out.put("watersheds", watershedOptions());
        out.put("years", yearOptions());
        return out;
    }

    private Map<String, Object> overview(Integer year) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("watershed", countWhere("sh_map_watershed", "wscd IS NOT NULL AND TRIM(wscd) <> ''", year));
        counts.put("section", countWhere("sh_map_section", "1=1", year));
        counts.put("prevention", countPrevention(year));
        counts.put("hazard", countWhere("sh_map_hazard_point", "1=1", year));
        counts.put("road", countWhere("sh_survey_road", "1=1", year));
        counts.put("gully", countWhere("sh_survey_gully", "1=1", year));
        counts.put("county", countDistinctAdmin("county_adcd", year));
        counts.put("town", countDistinctAdmin("town_adcd", year));
        counts.put("river", countRivers(year));

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Cat c : CATS) {
            int n = counts.getOrDefault(c.key(), 0);
            Map<String, Object> node = typeNode(c, n);
            nodes.add(node);
        }
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        link(links, seen, "type:county", "type:town", "下辖", (long) countAdminPairs(year));
        link(links, seen, "type:county", "type:watershed", "涉及", countPairs("county_adcd", "wscd", year));
        link(links, seen, "type:town", "type:watershed", "涉及", countPairs("town_adcd", "wscd", year));
        link(links, seen, "type:town", "type:prevention", "辖有",
                countWhere("sh_survey_object", "town_adcd IS NOT NULL AND TRIM(town_adcd) <> '' AND fcd IS NOT NULL AND TRIM(fcd) <> ''", year));
        link(links, seen, "type:watershed", "type:prevention", "包含",
                countWhere("sh_map_prevention_area", "wscd IS NOT NULL AND TRIM(wscd) <> ''", year));
        link(links, seen, "type:watershed", "type:section", "包含",
                countWhere("sh_map_section", "wscd IS NOT NULL AND TRIM(wscd) <> ''", year));
        link(links, seen, "type:watershed", "type:hazard", "包含",
                countWhere("sh_map_hazard_point", "wscd IS NOT NULL AND TRIM(wscd) <> ''", year));
        link(links, seen, "type:watershed", "type:road", "包含",
                countWhere("sh_survey_road", "wscd IS NOT NULL AND TRIM(wscd) <> ''", year));
        link(links, seen, "type:watershed", "type:gully", "包含",
                countWhere("sh_survey_gully", "wscd IS NOT NULL AND TRIM(wscd) <> ''", year));
        link(links, seen, "type:river", "type:watershed", "流经",
                countWhere("sh_map_watershed", "river_name IS NOT NULL AND TRIM(river_name) <> ''", year));
        link(links, seen, "type:section", "type:river", "位于",
                countWhere("sh_map_section", "rvname IS NOT NULL AND TRIM(rvname) <> ''", year));
        link(links, seen, "type:prevention", "type:river", "沿河",
                countWhere("sh_survey_object", "rvnm IS NOT NULL AND TRIM(rvnm) <> ''", year));
        link(links, seen, "type:hazard", "type:prevention", "威胁",
                countWhere("sh_map_hazard_point", "fcd IS NOT NULL AND TRIM(fcd) <> ''", year));
        link(links, seen, "type:road", "type:prevention", "壅水影响",
                countWhere("sh_survey_road", "backwater_objname IS NOT NULL AND TRIM(backwater_objname) <> ''", year));
        link(links, seen, "type:road", "type:prevention", "溃决影响",
                countWhere("sh_survey_road", "burst_objname IS NOT NULL AND TRIM(burst_objname) <> ''", year));
        link(links, seen, "type:road", "type:prevention", "漫溢影响",
                countWhere("sh_survey_road", "overflow_objname IS NOT NULL AND TRIM(overflow_objname) <> ''", year));

        Map<String, Object> out = shell("overview", null, null);
        out.put("hint", "这是山洪对象和它们之间的关系。选一条小流域，或搜索名称，即可展开到具体的村、断面、隐患和桥涵。");
        out.put("nodes", nodes);
        out.put("links", links);
        out.put("truncated", List.of());
        return out;
    }

    private Map<String, Object> focus(String wscd, String keyword, Integer year) {
        List<String> notes = new ArrayList<>();
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        List<Map<String, Object>> wsRows = safeList(
                "SELECT id, wscd, wsnm, alias_name, river_name, wsarea, village_count, jizhen_count, survey_year "
                        + "FROM sh_map_watershed WHERE wscd = ?" + yearSql(year) + " ORDER BY id LIMIT 1",
                yearArgs(wscd, year));
        String wsName = wscd;
        String riverOfWs = null;
        if (!wsRows.isEmpty()) {
            Map<String, Object> w = wsRows.get(0);
            wsName = firstNonBlank(str(w.get("alias_name")), str(w.get("wsnm")), wscd);
            riverOfWs = blank(str(w.get("river_name")));
            Map<String, Object> node = entity(nodes, "ws:" + wscd, wsName, cat("watershed"), "watershed", num(w.get("id")), null, null, wscd, wscd);
            prop(node, "小流域编码", wscd);
            prop(node, "沟名", str(w.get("alias_name")));
            prop(node, "流域名称", str(w.get("wsnm")));
            prop(node, "河流", riverOfWs);
            prop(node, "面积(km²)", str(w.get("wsarea")));
            prop(node, "防治村", str(w.get("village_count")));
            prop(node, "集镇", str(w.get("jizhen_count")));
            prop(node, "调查年份", str(w.get("survey_year")));
            mark(node, keyword);
        } else {
            Map<String, Object> node = entity(nodes, "ws:" + wscd, wscd, cat("watershed"), null, null, null, null, wscd, wscd);
            prop(node, "小流域编码", wscd);
            prop(node, "说明", "空间图层里还没有这个流域面，关系来自调查成果。");
        }
        if (riverOfWs != null) {
            ensureRiver(nodes, riverOfWs);
            link(links, seen, "river:" + riverOfWs, "ws:" + wscd, "流经", null);
        }

        List<Map<String, Object>> nameIndex = new ArrayList<>();

        for (Map<String, Object> r : capped(
                "SELECT id, obj_pid, obj_name, obj_type, pcount, rvname FROM sh_map_prevention_area WHERE wscd = ?"
                        + yearSql(year) + " ORDER BY id LIMIT " + (CAP + 1),
                yearArgs(wscd, year), "防治对象（空间面）", notes)) {
            String code = blank(str(r.get("obj_pid")));
            String id = code != null ? "obj:" + code : "prev:" + r.get("id");
            String name = firstNonBlank(str(r.get("obj_name")), code, "防治对象");
            Map<String, Object> node = entity(nodes, id, name, cat("prevention"), "prevention", num(r.get("id")), null, null, wscd, code);
            prop(node, "防护对象代码", code);
            prop(node, "类型", str(r.get("obj_type")));
            prop(node, "人口", str(r.get("pcount")));
            prop(node, "河流", str(r.get("rvname")));
            mark(node, keyword);
            rememberName(nameIndex, id, name);
            link(links, seen, "ws:" + wscd, id, "包含", null);
            linkRiver(nodes, links, seen, id, str(r.get("rvname")), "沿河");
        }

        for (Map<String, Object> r : capped(
                "SELECT id, fnm, fcd, ftp, pcount, county_name, county_adcd, town_name, town_adcd, rvnm "
                        + "FROM sh_survey_object WHERE wscd = ?" + yearSql(year) + " ORDER BY id LIMIT " + (CAP + 1),
                yearArgs(wscd, year), "防治对象（名录）", notes)) {
            String code = blank(str(r.get("fcd")));
            String id = code != null ? "obj:" + code : "sobj:" + r.get("id");
            String name = firstNonBlank(str(r.get("fnm")), code, "防治对象");
            boolean existed = nodes.containsKey(id);
            Map<String, Object> node = entity(nodes, id, name, cat("prevention"),
                    existed ? strOr(nodes.get(id).get("ds"), "prevention") : "survey_object",
                    existed ? num(nodes.get(id).get("rowId")) : num(r.get("id")),
                    existed ? "survey_object" : null,
                    existed ? num(r.get("id")) : null,
                    wscd, code);
            if (!existed) {
                node.put("ds", "survey_object");
                node.put("rowId", num(r.get("id")));
            }
            prop(node, "防护对象代码", code);
            prop(node, "类型", str(r.get("ftp")));
            prop(node, "人口", str(r.get("pcount")));
            prop(node, "县", str(r.get("county_name")));
            prop(node, "乡镇", str(r.get("town_name")));
            prop(node, "河流", str(r.get("rvnm")));
            mark(node, keyword);
            rememberName(nameIndex, id, name);
            link(links, seen, "ws:" + wscd, id, "包含", null);
            linkRiver(nodes, links, seen, id, str(r.get("rvnm")), "沿河");
            linkAdmin(nodes, links, seen, id, r);
        }

        for (Map<String, Object> r : capped(
                "SELECT id, pid, sec_name, sec_type, rvname FROM sh_map_section WHERE wscd = ?"
                        + yearSql(year) + " ORDER BY id LIMIT " + (CAP + 1),
                yearArgs(wscd, year), "断面", notes)) {
            String id = "sec:" + r.get("id");
            String name = firstNonBlank(str(r.get("sec_name")), str(r.get("pid")), "断面");
            Map<String, Object> node = entity(nodes, id, name, cat("section"), "section", num(r.get("id")), null, null, wscd, str(r.get("pid")));
            prop(node, "断面编码", str(r.get("pid")));
            prop(node, "类型", sectionType(str(r.get("sec_type"))));
            prop(node, "河流", str(r.get("rvname")));
            mark(node, keyword);
            link(links, seen, "ws:" + wscd, id, "包含", null);
            linkRiver(nodes, links, seen, id, str(r.get("rvname")), "位于");
        }

        for (Map<String, Object> r : capped(
                "SELECT id, pid, name, hz_type, fcd, fnm, rvname FROM sh_map_hazard_point WHERE wscd = ?"
                        + yearSql(year) + " ORDER BY id LIMIT " + (CAP + 1),
                yearArgs(wscd, year), "隐患要素", notes)) {
            String id = "haz:" + r.get("id");
            String name = firstNonBlank(str(r.get("name")), str(r.get("pid")), "隐患");
            Map<String, Object> node = entity(nodes, id, name, cat("hazard"), "hazard", num(r.get("id")), null, null, wscd, str(r.get("pid")));
            prop(node, "编码", str(r.get("pid")));
            prop(node, "隐患类型", str(r.get("hz_type")));
            prop(node, "防护对象", firstNonBlank(str(r.get("fnm")), str(r.get("fcd")), null));
            prop(node, "河流", str(r.get("rvname")));
            mark(node, keyword);
            link(links, seen, "ws:" + wscd, id, "包含", null);
            String fcd = blank(str(r.get("fcd")));
            if (fcd != null && nodes.containsKey("obj:" + fcd)) {
                link(links, seen, id, "obj:" + fcd, "威胁", null);
            }
            linkRiver(nodes, links, seen, id, str(r.get("rvname")), "沿河");
        }

        for (Map<String, Object> r : capped(
                "SELECT id, name, code, stype, rvnm, backwater_objname, burst_objname, overflow_objname, "
                        + "town_name, town_adcd, county_name, county_adcd FROM sh_survey_road WHERE wscd = ?"
                        + yearSql(year) + " ORDER BY id LIMIT " + (CAP + 1),
                yearArgs(wscd, year), "跨沟道路", notes)) {
            String id = "road:" + r.get("id");
            String name = firstNonBlank(str(r.get("name")), str(r.get("code")), "跨沟道路");
            Map<String, Object> node = entity(nodes, id, name, cat("road"), "survey_road", num(r.get("id")), null, null, wscd, str(r.get("code")));
            prop(node, "编码", str(r.get("code")));
            prop(node, "类型", str(r.get("stype")));
            prop(node, "壅水影响", str(r.get("backwater_objname")));
            prop(node, "溃决影响", str(r.get("burst_objname")));
            prop(node, "漫溢影响", str(r.get("overflow_objname")));
            mark(node, keyword);
            link(links, seen, "ws:" + wscd, id, "包含", null);
            linkRiver(nodes, links, seen, id, str(r.get("rvnm")), "沿河");
            linkAdmin(nodes, links, seen, id, r);
            affect(links, seen, id, str(r.get("backwater_objname")), "壅水影响", nameIndex);
            affect(links, seen, id, str(r.get("burst_objname")), "溃决影响", nameIndex);
            affect(links, seen, id, str(r.get("overflow_objname")), "漫溢影响", nameIndex);
        }

        for (Map<String, Object> r : capped(
                "SELECT id, name, code, stype, rvnm, town_name, town_adcd, county_name, county_adcd "
                        + "FROM sh_survey_gully WHERE wscd = ?" + yearSql(year) + " ORDER BY id LIMIT " + (CAP + 1),
                yearArgs(wscd, year), "沟滩占地", notes)) {
            String id = "gully:" + r.get("id");
            String name = firstNonBlank(str(r.get("name")), str(r.get("code")), "沟滩占地");
            Map<String, Object> node = entity(nodes, id, name, cat("gully"), "survey_gully", num(r.get("id")), null, null, wscd, str(r.get("code")));
            prop(node, "编码", str(r.get("code")));
            prop(node, "类型", str(r.get("stype")));
            prop(node, "河流", str(r.get("rvnm")));
            mark(node, keyword);
            link(links, seen, "ws:" + wscd, id, "包含", null);
            linkRiver(nodes, links, seen, id, str(r.get("rvnm")), "沿河");
            linkAdmin(nodes, links, seen, id, r);
        }

        Map<String, Object> out = shell("focus", wscd, wsName);
        out.put("hint", notes.isEmpty()
                ? "已展开「" + wsName + "」。点圆点看它和谁有关，也可以直接改这条数据。"
                : "已展开「" + wsName + "」。" + String.join("；", notes) + "。");
        out.put("nodes", new ArrayList<>(nodes.values()));
        out.put("links", links);
        out.put("truncated", notes);
        return out;
    }

    /** 命中多个小流域时，只画出匹配到的对象，并挂到各自的流域上。 */
    private Map<String, Object> sketch(Set<String> codes, String keyword, Integer year) {
        List<String> limited = new ArrayList<>(codes);
        List<String> notes = new ArrayList<>();
        if (limited.size() > SEARCH_WS) {
            notes.add("命中小流域较多，只展开前 " + SEARCH_WS + " 条");
            limited = limited.subList(0, SEARCH_WS);
        }
        String in = placeholders(limited.size());
        List<Object> wsArgs = new ArrayList<>(limited);
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> r : safeList(
                "SELECT wscd, wsnm, alias_name FROM sh_map_watershed WHERE wscd IN (" + in + ")", wsArgs.toArray())) {
            names.put(str(r.get("wscd")), firstNonBlank(str(r.get("alias_name")), str(r.get("wsnm")), str(r.get("wscd"))));
        }
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String code : limited) {
            String name = names.getOrDefault(code, code);
            Map<String, Object> node = entity(nodes, "ws:" + code, name, cat("watershed"), "watershed", null, null, null, code, code);
            prop(node, "小流域编码", code);
            prop(node, "说明", "点「展开这个小流域」查看完整关系。");
            mark(node, keyword);
        }
        String like = like(keyword);
        List<Object> args = new ArrayList<>();
        args.add(like);
        args.add(like);
        args.add(like);
        args.addAll(limited);
        if (year != null && year != 0) {
            args.add(year);
        }
        String yearBit = year == null ? "" : (year == 0 ? " AND survey_year IS NULL" : " AND survey_year = ?");
        for (Map<String, Object> r : safeList(
                "SELECT id, name, pid, hz_type, wscd, fcd, fnm FROM sh_map_hazard_point "
                        + "WHERE (COALESCE(name,'') ILIKE ? ESCAPE '!' OR COALESCE(pid,'') ILIKE ? ESCAPE '!' "
                        + "OR COALESCE(fnm,'') ILIKE ? ESCAPE '!') AND wscd IN (" + in + ")" + yearBit + " LIMIT 40",
                args.toArray())) {
            String w = str(r.get("wscd"));
            if (!nodes.containsKey("ws:" + w)) {
                continue;
            }
            String id = "haz:" + r.get("id");
            String name = firstNonBlank(str(r.get("name")), str(r.get("pid")), "隐患");
            Map<String, Object> node = entity(nodes, id, name, cat("hazard"), "hazard", num(r.get("id")), null, null, w, str(r.get("pid")));
            prop(node, "隐患类型", str(r.get("hz_type")));
            prop(node, "防护对象", str(r.get("fnm")));
            node.put("highlight", true);
            link(links, seen, "ws:" + w, id, "包含", null);
        }
        List<Object> objArgs = new ArrayList<>();
        objArgs.add(like);
        objArgs.add(like);
        objArgs.add(like);
        objArgs.addAll(limited);
        if (year != null && year != 0) {
            objArgs.add(year);
        }
        for (Map<String, Object> r : safeList(
                "SELECT id, fnm, fcd, wscd, town_name FROM sh_survey_object "
                        + "WHERE (COALESCE(fnm,'') ILIKE ? ESCAPE '!' OR COALESCE(fcd,'') ILIKE ? ESCAPE '!' "
                        + "OR COALESCE(wsnm,'') ILIKE ? ESCAPE '!') AND wscd IN (" + in + ")" + yearBit + " LIMIT 40",
                objArgs.toArray())) {
            String w = str(r.get("wscd"));
            if (!nodes.containsKey("ws:" + w)) {
                continue;
            }
            String code = blank(str(r.get("fcd")));
            String id = code != null ? "obj:" + code : "sobj:" + r.get("id");
            String name = firstNonBlank(str(r.get("fnm")), code, "防治对象");
            Map<String, Object> node = entity(nodes, id, name, cat("prevention"), "survey_object", num(r.get("id")), null, null, w, code);
            prop(node, "乡镇", str(r.get("town_name")));
            node.put("highlight", true);
            link(links, seen, "ws:" + w, id, "包含", null);
        }
        Map<String, Object> out = shell("search", null, null);
        out.put("hint", "找到 " + codes.size() + " 条小流域。点一条流域后展开它的完整关系。");
        out.put("nodes", new ArrayList<>(nodes.values()));
        out.put("links", links);
        out.put("truncated", notes);
        return out;
    }

    private Set<String> collectCodes(String keyword, Integer year) {
        String like = like(keyword);
        Set<String> codes = new LinkedHashSet<>();
        addCodes(codes, "SELECT DISTINCT wscd FROM sh_map_watershed WHERE wscd IS NOT NULL AND ("
                + "wscd ILIKE ? ESCAPE '!' OR COALESCE(wsnm,'') ILIKE ? ESCAPE '!' "
                + "OR COALESCE(alias_name,'') ILIKE ? ESCAPE '!' OR COALESCE(river_name,'') ILIKE ? ESCAPE '!')"
                + yearSql(year) + " LIMIT 20", like, 4, year);
        addCodes(codes, "SELECT DISTINCT wscd FROM sh_survey_object WHERE wscd IS NOT NULL AND ("
                + "COALESCE(fnm,'') ILIKE ? ESCAPE '!' OR COALESCE(wsnm,'') ILIKE ? ESCAPE '!' "
                + "OR COALESCE(fcd,'') ILIKE ? ESCAPE '!' OR wscd ILIKE ? ESCAPE '!')"
                + yearSql(year) + " LIMIT 20", like, 4, year);
        addCodes(codes, "SELECT DISTINCT wscd FROM sh_map_hazard_point WHERE wscd IS NOT NULL AND ("
                + "COALESCE(name,'') ILIKE ? ESCAPE '!' OR COALESCE(pid,'') ILIKE ? ESCAPE '!' "
                + "OR COALESCE(fnm,'') ILIKE ? ESCAPE '!')" + yearSql(year) + " LIMIT 20", like, 3, year);
        addCodes(codes, "SELECT DISTINCT wscd FROM sh_map_prevention_area WHERE wscd IS NOT NULL AND ("
                + "COALESCE(obj_name,'') ILIKE ? ESCAPE '!' OR COALESCE(obj_pid,'') ILIKE ? ESCAPE '!')"
                + yearSql(year) + " LIMIT 20", like, 2, year);
        addCodes(codes, "SELECT DISTINCT wscd FROM sh_survey_road WHERE wscd IS NOT NULL AND ("
                + "COALESCE(name,'') ILIKE ? ESCAPE '!' OR COALESCE(code,'') ILIKE ? ESCAPE '!')"
                + yearSql(year) + " LIMIT 20", like, 2, year);
        addCodes(codes, "SELECT DISTINCT wscd FROM sh_survey_gully WHERE wscd IS NOT NULL AND ("
                + "COALESCE(name,'') ILIKE ? ESCAPE '!' OR COALESCE(code,'') ILIKE ? ESCAPE '!')"
                + yearSql(year) + " LIMIT 20", like, 2, year);
        codes.remove("");
        return codes;
    }

    private void addCodes(Set<String> codes, String sql, String like, int times, Integer year) {
        if (codes.size() > SEARCH_WS) {
            return;
        }
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            args.add(like);
        }
        if (year != null && year != 0) {
            args.add(year);
        }
        for (Map<String, Object> r : safeList(sql, args.toArray())) {
            String c = str(r.get("wscd")).trim();
            if (!c.isEmpty()) {
                codes.add(c);
            }
        }
    }

    private void linkAdmin(Map<String, Map<String, Object>> nodes, List<Map<String, Object>> links, Set<String> seen,
                           String childId, Map<String, Object> r) {
        String countyAdcd = blank(str(r.get("county_adcd")));
        String townAdcd = blank(str(r.get("town_adcd")));
        String countyId = null;
        String townId = null;
        if (countyAdcd != null) {
            countyId = "county:" + countyAdcd;
            String countyName = firstNonBlank(str(r.get("county_name")), countyAdcd, "县");
            Map<String, Object> node = entity(nodes, countyId, countyName, cat("county"), null, null, null, null, null, countyAdcd);
            prop(node, "县代码", countyAdcd);
        }
        if (townAdcd != null) {
            townId = "town:" + townAdcd;
            String townName = firstNonBlank(str(r.get("town_name")), townAdcd, "乡镇");
            Map<String, Object> node = entity(nodes, townId, townName, cat("town"), null, null, null, null, null, townAdcd);
            prop(node, "乡镇代码", townAdcd);
            if (countyId != null) {
                link(links, seen, countyId, townId, "下辖", null);
            }
            link(links, seen, townId, childId, "辖有", null);
        }
        String wscd = blank(str(nodes.get(childId) == null ? null : nodes.get(childId).get("wscd")));
        if (wscd != null && nodes.containsKey("ws:" + wscd)) {
            if (countyId != null) {
                link(links, seen, countyId, "ws:" + wscd, "涉及", null);
            }
            if (townId != null) {
                link(links, seen, townId, "ws:" + wscd, "涉及", null);
            }
        }
    }

    private void linkRiver(Map<String, Map<String, Object>> nodes, List<Map<String, Object>> links, Set<String> seen,
                           String childId, String riverName, String label) {
        String name = blank(riverName);
        if (name == null) {
            return;
        }
        ensureRiver(nodes, name);
        link(links, seen, childId, "river:" + name, label, null);
    }

    private void ensureRiver(Map<String, Map<String, Object>> nodes, String name) {
        entity(nodes, "river:" + name, name, cat("river"), null, null, null, null, null, name);
    }

    private void affect(List<Map<String, Object>> links, Set<String> seen, String roadId, String text, String label,
                        List<Map<String, Object>> nameIndex) {
        String raw = blank(text);
        if (raw == null) {
            return;
        }
        for (Map<String, Object> item : nameIndex) {
            String name = str(item.get("name"));
            if (name.length() >= 2 && raw.contains(name)) {
                link(links, seen, roadId, str(item.get("id")), label, null);
            }
        }
    }

    private void rememberName(List<Map<String, Object>> nameIndex, String id, String name) {
        if (name == null || name.isBlank() || name.length() < 2) {
            return;
        }
        nameIndex.add(Map.of("id", id, "name", name.trim()));
    }

    private List<Map<String, Object>> capped(String sql, Object[] args, String label, List<String> notes) {
        List<Map<String, Object>> rows = safeList(sql, args);
        if (rows.size() > CAP) {
            notes.add(label + "较多，图谱只展开前 " + CAP + " 个");
            return new ArrayList<>(rows.subList(0, CAP));
        }
        return rows;
    }

    private Map<String, Object> typeNode(Cat c, int count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "type:" + c.key());
        m.put("name", c.name());
        m.put("category", c.name());
        m.put("categoryKey", c.key());
        m.put("kind", "type");
        int extra = (int) Math.round(Math.log10(count + 1) * 14);
        m.put("symbolSize", Math.min(96, c.size() + extra));
        m.put("count", count);
        m.put("ds", c.ds());
        m.put("blurb", c.blurb());
        m.put("highlight", false);
        List<Map<String, String>> props = new ArrayList<>();
        props.add(Map.of("label", "数量", "value", String.valueOf(count)));
        m.put("props", props);
        return m;
    }

    /** 已存在的节点只补第二数据源，不覆盖名称和第一条记录。 */
    private Map<String, Object> entity(Map<String, Map<String, Object>> nodes, String id, String name, Cat c,
                                       String ds, Long rowId, String ds2, Long rowId2, String wscd, String code) {
        Map<String, Object> exist = nodes.get(id);
        if (exist != null) {
            if (ds2 != null && exist.get("ds2") == null && !ds2.equals(exist.get("ds"))) {
                exist.put("ds2", ds2);
                exist.put("rowId2", rowId2);
            } else if (ds != null && exist.get("ds") == null) {
                exist.put("ds", ds);
                exist.put("rowId", rowId);
            }
            return exist;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name == null || name.isBlank() ? c.name() : name);
        m.put("category", c.name());
        m.put("categoryKey", c.key());
        m.put("kind", "entity");
        m.put("symbolSize", c.size() > 60 ? 54 : 32);
        m.put("ds", ds);
        m.put("rowId", rowId);
        m.put("ds2", ds2);
        m.put("rowId2", rowId2);
        m.put("wscd", wscd);
        m.put("code", code);
        m.put("blurb", null);
        m.put("highlight", false);
        m.put("props", new ArrayList<Map<String, String>>());
        nodes.put(id, m);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void prop(Map<String, Object> node, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        List<Map<String, String>> props = (List<Map<String, String>>) node.get("props");
        for (Map<String, String> p : props) {
            if (label.equals(p.get("label"))) {
                return;
            }
        }
        props.add(Map.of("label", label, "value", value));
    }

    private void mark(Map<String, Object> node, String keyword) {
        if (keyword == null) {
            return;
        }
        String k = keyword.toLowerCase(Locale.ROOT);
        if (str(node.get("name")).toLowerCase(Locale.ROOT).contains(k)
                || str(node.get("code")).toLowerCase(Locale.ROOT).contains(k)) {
            node.put("highlight", true);
        }
    }

    private void link(List<Map<String, Object>> links, Set<String> seen, String source, String target, String label, Number count) {
        if (source == null || target == null || source.equals(target)) {
            return;
        }
        if (!seen.add(source + "\0" + target + "\0" + label)) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("target", target);
        m.put("label", label);
        if (count != null) {
            m.put("count", count.longValue());
        }
        links.add(m);
    }

    private Map<String, Object> shell(String mode, String wscd, String wsnm) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("wscd", wscd);
        out.put("wsnm", wsnm);
        List<Map<String, String>> cats = new ArrayList<>();
        for (Cat c : CATS) {
            cats.add(Map.of("key", c.key(), "name", c.name()));
        }
        out.put("categories", cats);
        return out;
    }

    private Cat cat(String key) {
        for (Cat c : CATS) {
            if (c.key().equals(key)) {
                return c;
            }
        }
        throw new IllegalArgumentException(key);
    }

    private int countWhere(String table, String where, Integer year) {
        List<Object> args = new ArrayList<>();
        return safeInt("SELECT COUNT(*) FROM " + table + " WHERE " + where + yearAnd(year, args), args.toArray());
    }

    private int countPrevention(Integer year) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM ("
                + "SELECT TRIM(obj_pid) cd FROM sh_map_prevention_area WHERE obj_pid IS NOT NULL AND TRIM(obj_pid) <> ''" + yearAnd(year, args)
                + " UNION SELECT TRIM(fcd) FROM sh_survey_object WHERE fcd IS NOT NULL AND TRIM(fcd) <> ''" + yearAnd(year, args)
                + ") t";
        return safeInt(sql, args.toArray());
    }

    private int countDistinctAdmin(String col, Integer year) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM ("
                + branch(col, "sh_survey_object", year, args)
                + " UNION " + branch(col, "sh_survey_road", year, args)
                + " UNION " + branch(col, "sh_survey_gully", year, args)
                + ") t WHERE nm IS NOT NULL AND nm <> ''";
        return safeInt(sql, args.toArray());
    }

    private String branch(String col, String table, Integer year, List<Object> args) {
        return "SELECT TRIM(" + col + ") nm FROM " + table + " WHERE " + col + " IS NOT NULL AND TRIM(" + col + ") <> ''" + yearAnd(year, args);
    }

    private int countAdminPairs(Integer year) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM ("
                + pairBranch("sh_survey_object", year, args)
                + " UNION " + pairBranch("sh_survey_road", year, args)
                + " UNION " + pairBranch("sh_survey_gully", year, args)
                + ") t";
        return safeInt(sql, args.toArray());
    }

    private String pairBranch(String table, Integer year, List<Object> args) {
        return "SELECT TRIM(county_adcd) c, TRIM(town_adcd) t FROM " + table
                + " WHERE county_adcd IS NOT NULL AND TRIM(county_adcd) <> '' AND town_adcd IS NOT NULL AND TRIM(town_adcd) <> ''"
                + yearAnd(year, args);
    }

    private long countPairs(String adminCol, String wsCol, Integer year) {
        List<Object> args = new ArrayList<>();
        String sql = "SELECT COUNT(*) FROM ("
                + "SELECT DISTINCT TRIM(" + adminCol + "), TRIM(" + wsCol + ") FROM sh_survey_object WHERE "
                + adminCol + " IS NOT NULL AND TRIM(" + adminCol + ") <> '' AND " + wsCol + " IS NOT NULL AND TRIM(" + wsCol + ") <> ''"
                + yearAnd(year, args)
                + ") t";
        return safeInt(sql, args.toArray());
    }

    private int countRivers(Integer year) {
        List<Object> args = new ArrayList<>();
        String[] parts = {
                "SELECT TRIM(river_name) nm FROM sh_map_watershed WHERE river_name IS NOT NULL AND TRIM(river_name) <> ''",
                "SELECT TRIM(rvnm) FROM sh_survey_object WHERE rvnm IS NOT NULL AND TRIM(rvnm) <> ''",
                "SELECT TRIM(rvname) FROM sh_map_section WHERE rvname IS NOT NULL AND TRIM(rvname) <> ''",
                "SELECT TRIM(rvname) FROM sh_map_prevention_area WHERE rvname IS NOT NULL AND TRIM(rvname) <> ''",
                "SELECT TRIM(rvname) FROM sh_map_hazard_point WHERE rvname IS NOT NULL AND TRIM(rvname) <> ''",
                "SELECT TRIM(rvnm) FROM sh_survey_road WHERE rvnm IS NOT NULL AND TRIM(rvnm) <> ''",
                "SELECT TRIM(rvnm) FROM sh_survey_gully WHERE rvnm IS NOT NULL AND TRIM(rvnm) <> ''"
        };
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) FROM (");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(" UNION ");
            }
            sb.append(parts[i]).append(yearAnd(year, args));
        }
        sb.append(") t WHERE nm IS NOT NULL AND nm <> ''");
        return safeInt(sb.toString(), args.toArray());
    }

    private List<Map<String, Object>> watershedOptions() {
        List<Map<String, Object>> rows = safeList(
                "SELECT wscd AS cd, COALESCE(NULLIF(TRIM(alias_name), ''), NULLIF(TRIM(wsnm), ''), wscd) AS nm "
                        + "FROM sh_map_watershed WHERE wscd IS NOT NULL AND TRIM(wscd) <> '' ORDER BY nm LIMIT 1000");
        if (!rows.isEmpty()) {
            return rows;
        }
        return safeList(
                "SELECT wscd AS cd, COALESCE(NULLIF(TRIM(MAX(wsnm)), ''), wscd) AS nm FROM sh_survey_object "
                        + "WHERE wscd IS NOT NULL AND TRIM(wscd) <> '' GROUP BY wscd ORDER BY nm LIMIT 1000");
    }

    private List<Map<String, Object>> yearOptions() {
        Map<Integer, Long> acc = new LinkedHashMap<>();
        for (String table : List.of("sh_map_watershed", "sh_map_section", "sh_map_prevention_area", "sh_map_hazard_point",
                "sh_survey_object", "sh_survey_road", "sh_survey_gully")) {
            for (Map<String, Object> r : safeList("SELECT survey_year AS y, COUNT(*) AS n FROM " + table + " GROUP BY survey_year")) {
                int y = r.get("y") == null ? 0 : ((Number) r.get("y")).intValue();
                long n = ((Number) r.get("n")).longValue();
                acc.merge(y, n, Long::sum);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        acc.entrySet().stream().sorted((a, b) -> Integer.compare(b.getKey(), a.getKey())).forEach(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year", e.getKey());
            m.put("count", e.getValue());
            out.add(m);
        });
        return out;
    }

    private String yearSql(Integer year) {
        if (year == null) {
            return "";
        }
        if (year == 0) {
            return " AND survey_year IS NULL";
        }
        return " AND survey_year = ?";
    }

    private String yearAnd(Integer year, List<Object> args) {
        if (year == null) {
            return "";
        }
        if (year == 0) {
            return " AND survey_year IS NULL";
        }
        args.add(year);
        return " AND survey_year = ?";
    }

    private Object[] yearArgs(String wscd, Integer year) {
        if (year != null && year != 0) {
            return new Object[]{wscd, year};
        }
        return new Object[]{wscd};
    }

    private String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private String like(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private int safeInt(String sql, Object... args) {
        try {
            return pgQueryService.queryInt(sql, args);
        } catch (Exception e) {
            log.warn("知识图谱统计失败: {}", e.getMessage());
            return 0;
        }
    }

    private List<Map<String, Object>> safeList(String sql, Object... args) {
        try {
            return pgQueryService.queryForList(sql, args);
        } catch (Exception e) {
            log.warn("知识图谱查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String blank(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... parts) {
        if (parts == null) {
            return null;
        }
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                return p.trim();
            }
        }
        return null;
    }

    private static String strOr(Object v, String fallback) {
        String s = str(v);
        return s.isEmpty() ? fallback : s;
    }

    private static Long num(Object v) {
        if (v == null || "".equals(v)) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String sectionType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if ("A".equalsIgnoreCase(raw)) {
            return "横断面";
        }
        if ("B".equalsIgnoreCase(raw)) {
            return "纵断面";
        }
        return raw;
    }
}
