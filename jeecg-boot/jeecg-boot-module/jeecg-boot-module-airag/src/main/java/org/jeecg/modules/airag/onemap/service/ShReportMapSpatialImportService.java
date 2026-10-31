package org.jeecg.modules.airag.onemap.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.onemap.util.ShpParser;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 一张图空间数据导入（Shapefile zip / GeoJSON，按调查年份 survey_year 入库）。
 * 与 Excel 导入（{@link ShReportMapImportService}）同一年份追加/覆盖语义；
 * 几何直接写 geom 列（EPSG:4326），导入即可在地图图层显示。
 * 字段识别：白名单列名 / 中文列名 / 源数据字段名（省平台 shp、geojson 属性，
 * 见 SOURCE_ALIASES，口径同 sh_report_import/import_shmap.py）；wscd 清洗与
 * 类型归并规则亦与该脚本一致。
 */
@Slf4j
@Service
public class ShReportMapSpatialImportService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 压缩包解压总量上限，防止误传超大文件撑爆内存 */
    private static final int MAX_ZIP_BYTES = 500 * 1024 * 1024;

    /** 源数据字段名（小写）-> 目标列；解决 shp 短字段名/geojson 属性名与表列名不一致 */
    private static final Map<String, Map<String, String>> SOURCE_ALIASES = Map.of(
            "watershed", Map.ofEntries(
                    Map.entry("wswscd", "wscd"), Map.entry("wkmm", "wsnm"), Map.entry("mc", "wsnm"),
                    Map.entry("centerx", "center_lng"), Map.entry("centery", "center_lat"),
                    Map.entry("centerelv", "center_elv"), Map.entry("outletx", "outlet_lng"),
                    Map.entry("outlety", "outlet_lat"),
                    Map.entry("outletelev", "outlet_elv"), Map.entry("outletele", "outlet_elv"),
                    Map.entry("maxelv", "max_elv"), Map.entry("name", "river_name"),
                    Map.entry("重命名", "alias_name"), Map.entry("集镇个数", "jizhen_count"),
                    Map.entry("防治村个数", "village_count"), Map.entry("山洪沟道条", "gully_count"),
                    Map.entry("山洪沟道条数", "gully_count"), Map.entry("涉及水库", "reservoirs")),
            "section", Map.of("types", "sec_type", "name", "sec_name", "wswscd", "wscd"),
            "prevention", Map.of("pid", "obj_pid", "name", "obj_name", "types", "obj_type", "wswscd", "wscd"),
            "hazard", Map.of("types", "hz_type", "wswscd", "wscd"));

    /** 各数据集允许写入的 GeoJSON 几何类型（同 ShReportMapAdminService.GEOM_TYPES） */
    private static final Map<String, List<String>> GEOM_TYPES = Map.of(
            "watershed", List.of("Polygon", "MultiPolygon"),
            "section", List.of("LineString", "MultiLineString"),
            "prevention", List.of("Polygon", "MultiPolygon"),
            "hazard", List.of("Point"));

    /** 类型同义归并（同 import_shmap.py TYPE_SYNONYMS） */
    private static final Map<String, String> TYPE_SYNONYMS = Map.of(
            "跨沟桥梁", "跨沟桥涵",
            "跨沟路涵", "跨沟道路",
            "路涵道路", "跨沟道路",
            "临河滑坡体", "临河滑坡");

    @Autowired
    private ShReportMapAdminService adminService;

    @Autowired
    private ShReportPgQueryService pgQueryService;

    /**
     * @return imported 成功 / skipped 缺几何或类型不符跳过 / failed 入库失败 /
     *         fields 未识别的源字段名（前 30 个，供页面提示）
     */
    public Map<String, Object> importSpatial(String ds, int year, String mode, MultipartFile file) throws Exception {
        ShReportMapAdminService.DsConf d = adminService.dataset(ds);
        if (d.geomCol() == null) {
            throw new IllegalArgumentException("该数据集无空间几何列，请使用 Excel 导入");
        }
        if (!"append".equalsIgnoreCase(mode) && !"replace".equalsIgnoreCase(mode)) {
            throw new IllegalArgumentException("导入模式仅支持 append / replace");
        }
        if (year <= 0) {
            throw new IllegalArgumentException("必须指定调查年份");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的文件");
        }

        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        List<ShpParser.Feature> features;
        if (name.endsWith(".zip")) {
            features = parseZip(file);
        } else if (name.endsWith(".geojson") || name.endsWith(".json")) {
            features = parseGeoJson(file);
        } else {
            throw new IllegalArgumentException("空间导入仅支持 .zip（内含 .shp/.dbf）或 .geojson 文件");
        }
        if (features.isEmpty()) {
            throw new IllegalArgumentException("文件中未解析到任何要素");
        }

        Map<String, String> headerToCol = headerMap(d);
        Set<String> baseCodes = "watershed".equals(d.key()) ? Set.of() : loadBaseCodes();

        if ("replace".equalsIgnoreCase(mode)) {
            int removed = pgQueryService.update("DELETE FROM " + d.table() + " WHERE survey_year = ?", year);
            log.info("空间覆盖导入 {} 年份 {}：先清除 {} 行", d.table(), year, removed);
        }

        int inserted = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> seenWscd = new HashSet<>();
        Set<String> unmatched = new LinkedHashSet<>();
        for (ShpParser.Feature f : features) {
            Map<String, Object> geom = coerceGeometry(d.key(), f.geometry());
            Map<String, Object> props = f.props() == null ? Map.of() : f.props();
            if (geom == null) {
                skipped++;
                continue;
            }
            Map<String, Object> sets = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : props.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                String col = headerToCol.get(String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT));
                if (col == null) {
                    if (e.getValue() != null && !String.valueOf(e.getValue()).isBlank()) {
                        unmatched.add(String.valueOf(e.getKey()));
                    }
                    continue;
                }
                Object v = cleanValue(e.getValue());
                sets.put(col, numeric(d, col) ? toD(v) : v);
            }
            try {
                validateCoords(geom.get("coordinates"));
                postProcess(d, sets, geom, baseCodes);
                if ("watershed".equals(d.key())) {
                    // 流域表 wscd 唯一索引：文件内重复编码只保留第一条
                    String wscd = (String) sets.get("wscd");
                    if (wscd == null || !seenWscd.add(wscd)) {
                        skipped++;
                        continue;
                    }
                }
                if (insertRow(d, sets, geom, props, year)) {
                    inserted++;
                } else {
                    failed++;
                }
            } catch (Exception ex) {
                failed++;
                if (failed <= 3) {
                    log.warn("空间导入行失败 ds={}：{}", ds, ex.getMessage());
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", inserted);
        out.put("skipped", skipped);
        out.put("failed", failed);
        if (!unmatched.isEmpty()) {
            out.put("fields", unmatched.stream().limit(30).toList());
        }
        log.info("空间导入完成 ds={} year={} mode={}：成功 {} 跳过 {} 失败 {}，未识别字段 {}", ds, year, mode, inserted, skipped, failed, unmatched);
        return out;
    }

    // ---------------------------------------------------------------- 文件解析

    /** zip 中取首个图层的 .shp + 同名 .dbf（.shx 不需要，顺序解析 .shp 记录流） */
    private List<ShpParser.Feature> parseZip(MultipartFile file) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int total = 0;
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String n = entry.getName().toLowerCase(Locale.ROOT);
                int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
                if (slash >= 0) {
                    n = n.substring(slash + 1);
                }
                if (!n.endsWith(".shp") && !n.endsWith(".dbf") && !n.endsWith(".shx") && !n.endsWith(".prj") && !n.endsWith(".cpg")) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                zis.transferTo(bos);
                byte[] data = bos.toByteArray();
                total += data.length;
                if (total > MAX_ZIP_BYTES) {
                    throw new IllegalArgumentException("压缩包解压后超过 500MB，拒绝处理");
                }
                entries.putIfAbsent(n, data);
            }
        }
        String shpName = entries.keySet().stream().filter(n -> n.endsWith(".shp")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("压缩包中未找到 .shp 文件"));
        byte[] dbf = entries.get(shpName.substring(0, shpName.length() - 4) + ".dbf");
        if (dbf == null) {
            throw new IllegalArgumentException("压缩包中未找到与 " + shpName + " 同名的 .dbf 属性文件");
        }
        return ShpParser.parse(entries.get(shpName), dbf);
    }

    private List<ShpParser.Feature> parseGeoJson(MultipartFile file) throws Exception {
        Object node = JSON.readValue(file.getInputStream(), Object.class);
        List<ShpParser.Feature> out = new ArrayList<>();
        if (node instanceof Map<?, ?> m && "FeatureCollection".equals(m.get("type")) && m.get("features") instanceof List<?> list) {
            for (Object o : list) {
                addFeature(out, o);
            }
        } else {
            addFeature(out, node);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void addFeature(List<ShpParser.Feature> out, Object o) {
        if (!(o instanceof Map<?, ?> m) || !"Feature".equals(m.get("type"))) {
            return;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        if (m.get("properties") instanceof Map<?, ?> p) {
            p.forEach((k, v) -> props.put(String.valueOf(k), v));
        }
        Map<String, Object> geom = m.get("geometry") instanceof Map<?, ?> g
                ? new LinkedHashMap<>((Map<String, Object>) g) : null;
        out.add(new ShpParser.Feature(geom, props));
    }

    // ---------------------------------------------------------------- 映射与清洗

    /** 列名 / 中文列名 / 源字段名（后者优先级最高）-> 白名单列 */
    private Map<String, String> headerMap(ShReportMapAdminService.DsConf d) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ShReportMapAdminService.ColConf c : d.cols()) {
            m.put(c.col().toLowerCase(Locale.ROOT), c.col());
            m.put(c.label(), c.col());
        }
        m.putAll(SOURCE_ALIASES.getOrDefault(d.key(), Map.of()));
        return m;
    }

    private boolean numeric(ShReportMapAdminService.DsConf d, String col) {
        return d.cols().stream().anyMatch(c -> c.col().equals(col) && c.numeric());
    }

    private Set<String> loadBaseCodes() {
        try {
            Set<String> out = new HashSet<>();
            for (Map<String, Object> r : pgQueryService.queryForList("SELECT wscd FROM sh_map_watershed")) {
                Object v = r.get("wscd");
                if (v != null && !String.valueOf(v).isBlank()) {
                    out.add(String.valueOf(v).trim().toUpperCase(Locale.ROOT));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("读取基准小流域编码失败，wscd 仅做大写清洗：{}", e.getMessage());
            return new HashSet<>();
        }
    }

    /** 几何类型校验；隐患点单点 MultiPoint 降为 Point */
    private Map<String, Object> coerceGeometry(String key, Map<String, Object> geom) {
        if (geom == null || geom.get("type") == null) {
            return null;
        }
        String type = String.valueOf(geom.get("type"));
        if ("hazard".equals(key) && "MultiPoint".equals(type)
                && geom.get("coordinates") instanceof List<?> pts && pts.size() == 1) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("type", "Point");
            point.put("coordinates", pts.get(0));
            return point;
        }
        return GEOM_TYPES.getOrDefault(key, List.of()).contains(type) ? geom : null;
    }

    private void postProcess(ShReportMapAdminService.DsConf d, Map<String, Object> sets,
                             Map<String, Object> geom, Set<String> baseCodes) {
        switch (d.key()) {
            case "watershed" -> {
                if (sets.get("wscd") instanceof String s) {
                    sets.put("wscd", s.trim().toUpperCase(Locale.ROOT));
                }
            }
            case "section", "prevention", "hazard" -> {
                if (sets.get("wscd") instanceof String s) {
                    sets.put("wscd", cleanWscd(s, baseCodes));
                }
            }
            default -> {
            }
        }
        if (sets.get("obj_type") instanceof String s) {
            sets.put("obj_type", normType(s));
        }
        if (sets.get("hz_type") instanceof String s) {
            sets.put("hz_type", normType(s));
        }
        if ("hazard".equals(d.key())) {
            // 经纬度优先取自点几何（属性列常为空），超出中国范围视为脏数据置空
            Double lng = null;
            Double lat = null;
            if (geom.get("coordinates") instanceof List<?> c && c.size() >= 2) {
                lng = toD(c.get(0));
                lat = toD(c.get(1));
            }
            if (!inChina(lng, lat)) {
                lng = toD(sets.get("lgtd"));
                lat = toD(sets.get("lttd"));
            }
            if (!inChina(lng, lat)) {
                lng = null;
                lat = null;
            }
            sets.put("lgtd", lng);
            sets.put("lttd", lat);
        }
    }

    private boolean inChina(Double lng, Double lat) {
        return lng != null && lat != null && lng >= 70 && lng <= 140 && lat >= 10 && lat <= 60;
    }

    /**
     * 小流域编码清洗，同 import_shmap.py WscdCleaner：大写、GS/SW 前缀剥离后
     * 匹配 126 基准编码，再套人工核定映射（check_unmapped.py 空间包含核对生成，
     * 杨家河 X2000xx 子编码、瓜子沟 WFD1630W 系列、阿坞河 281EB00）。
     */
    private static String cleanWscd(String raw, Set<String> base) {
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty() || base.contains(s)) {
            return s;
        }
        for (String prefix : new String[]{"GS", "SW"}) {
            if (s.startsWith(prefix) && base.contains(s.substring(prefix.length()))) {
                return s.substring(prefix.length());
            }
        }
        if (s.matches("WDA380012X2000(0[1-9]|[12][0-9]|3[0-5])")) {
            return "WDA380012X200000";
        }
        if (s.matches("WFD1630W000000(0[1-9]|[12][0-9]|3[0-5])")) {
            return "WFD1630W00000000";
        }
        if ("WFD160000281EB00".equals(s)) {
            return "WFD16307G0000000";
        }
        return s;
    }

    /** 类型清洗同 import_shmap.py norm_type：去空白、半角括号转全角、换行重复折叠、同义归并 */
    private static String normType(String raw) {
        String s = raw.replaceAll("\\s+", "").replace("(", "（").replace(")", "）");
        if (s.length() % 2 == 0 && s.length() >= 2 && s.substring(0, s.length() / 2).repeat(2).equals(s)) {
            s = s.substring(0, s.length() / 2);
        }
        return TYPE_SYNONYMS.getOrDefault(s, s);
    }

    /** 同 import_shmap.py to_str：空白与 nan/null/none 视为空值（pyshp 会把 None 写成字面量 "None"） */
    private static Object cleanValue(Object v) {
        if (!(v instanceof String s)) {
            return v;
        }
        String t = s.trim();
        return (t.isEmpty() || "nan".equalsIgnoreCase(t) || "null".equalsIgnoreCase(t) || "none".equalsIgnoreCase(t)) ? null : t;
    }

    /** 递归校验坐标结构：最内层为至少两位的有限数值（经纬度） */
    private static void validateCoords(Object coords) {
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

    private static Double toD(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 入库

    /** 面/线数据集写入时 ST_Multi 提升为 MultiPolygon / MultiLineString（与表列类型一致） */
    private boolean insertRow(ShReportMapAdminService.DsConf d, Map<String, Object> sets, Map<String, Object> geom,
                              Map<String, Object> rawProps, int year) throws Exception {
        List<String> cols = new ArrayList<>(sets.keySet());
        List<String> ph = new ArrayList<>(Collections.nCopies(cols.size(), "?"));
        List<Object> args = new ArrayList<>(sets.values());
        cols.add("survey_year");
        ph.add("?");
        args.add(year);
        // 流域面全量原始属性存 attrs JSONB（同 import_shmap.py）
        if ("watershed".equals(d.key())) {
            cols.add("attrs");
            ph.add("?::jsonb");
            args.add(JSON.writeValueAsString(rawProps));
        }
        cols.add(d.geomCol());
        ph.add("hazard".equals(d.key())
                ? "ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)"
                : "ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))");
        args.add(JSON.writeValueAsString(geom));
        return pgQueryService.update("INSERT INTO " + d.table() + " (" + String.join(", ", cols)
                + ") VALUES (" + String.join(", ", ph) + ")", args.toArray()) > 0;
    }
}
