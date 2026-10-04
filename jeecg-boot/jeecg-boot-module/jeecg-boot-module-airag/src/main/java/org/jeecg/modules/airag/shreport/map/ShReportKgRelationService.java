package org.jeecg.modules.airag.shreport.map;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 山洪知识图谱关系库。对象和连线原先只在查询时按业务字段现算，这里把它们整理成可维护的记录：
 * 初始化写入派生关系，停用后图谱不再画，手工关系在重新初始化时保留。
 * 节点编号与 {@link ShReportKnowledgeGraphService} 保持一致（ws:、obj:、sec:、haz:、river: 等）。
 */
@Slf4j
@Service
public class ShReportKgRelationService {

    private static final String UPSERT = """
            ON CONFLICT (rel_key, src_id, tgt_id, wscd_key) DO UPDATE SET
                rel_label = EXCLUDED.rel_label,
                src_cat = EXCLUDED.src_cat,
                src_name = EXCLUDED.src_name,
                tgt_cat = EXCLUDED.tgt_cat,
                tgt_name = EXCLUDED.tgt_name,
                wscd = EXCLUDED.wscd,
                survey_year = EXCLUDED.survey_year,
                batch_id = EXCLUDED.batch_id,
                update_time = CURRENT_TIMESTAMP
            """;

    private record Job(String key, String label, String srcCat, String tgtCat, String blurb, String note, String sql) {
    }

    private static final Map<String, String> CAT_NAME = Map.of(
            "watershed", "小流域",
            "river", "河流",
            "county", "县",
            "town", "乡镇",
            "prevention", "防治对象",
            "section", "断面",
            "hazard", "隐患要素",
            "road", "跨沟道路",
            "gully", "沟滩占地"
    );

    private static final List<Job> JOBS = jobs();

    @Autowired
    private ShReportPgQueryService pgQueryService;

    private volatile boolean tableReady;

    public boolean available() {
        try {
            ensure();
            return true;
        } catch (Exception e) {
            log.warn("知识图谱关系库不可用: {}", e.getMessage());
            return false;
        }
    }

    public boolean initialized() {
        List<Map<String, Object>> rows = pgQueryService.queryForList(
                "SELECT initialized FROM sh_kg_rel_meta WHERE id = 1");
        return !rows.isEmpty() && asInt(rows.get(0).get("initialized")) == 1;
    }

    public int countAll() {
        return pgQueryService.queryInt("SELECT COUNT(*) FROM sh_kg_relation");
    }

    public Map<String, Object> status() {
        ensure();
        Map<String, Object> meta = new LinkedHashMap<>();
        List<Map<String, Object>> rows = pgQueryService.queryForList(
                "SELECT initialized, init_time, init_count, message FROM sh_kg_rel_meta WHERE id = 1");
        if (!rows.isEmpty()) {
            meta = rows.get(0);
        }
        Map<String, Object> agg = new LinkedHashMap<>();
        List<Map<String, Object>> counts = pgQueryService.queryForList("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE origin = 'init') AS derived,
                       COUNT(*) FILTER (WHERE origin = 'manual') AS manual,
                       COUNT(*) FILTER (WHERE enabled = 0) AS disabled
                FROM sh_kg_relation
                """);
        if (!counts.isEmpty()) {
            agg = counts.get(0);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("initialized", asInt(meta.get("initialized")) == 1);
        out.put("initTime", timeText(meta.get("init_time")));
        out.put("message", blank(str(meta.get("message"))));
        out.put("total", asLong(agg.get("total")));
        out.put("derived", asLong(agg.get("derived")));
        out.put("manual", asLong(agg.get("manual")));
        out.put("disabled", asLong(agg.get("disabled")));
        out.put("rules", rules());
        return out;
    }

    public Map<String, Object> list(String relKey, String srcCat, String tgtCat, String origin, Integer enabled,
                                    String q, int pageNo, int pageSize) {
        ensure();
        if (pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > 100) {
            pageSize = 100;
        }
        List<Object> args = new ArrayList<>();
        String where = where(relKey, srcCat, tgtCat, origin, enabled, q, args);
        int total = pgQueryService.queryInt("SELECT COUNT(*) FROM sh_kg_relation WHERE " + where, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((pageNo - 1) * pageSize);
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> r : pgQueryService.queryForList(
                "SELECT id, rel_key, rel_label, src_cat, src_id, src_name, tgt_cat, tgt_id, tgt_name, wscd, survey_year, origin, enabled, update_time "
                        + "FROM sh_kg_relation WHERE " + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray())) {
            data.add(toRow(r));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("pageNo", pageNo);
        out.put("pageSize", pageSize);
        out.put("data", data);
        return out;
    }

    public Map<String, Object> initialize() {
        ensure();
        String batch = String.valueOf(System.currentTimeMillis());
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Job job : JOBS) {
            Map<String, Object> step = new LinkedHashMap<>();
            String title = catName(job.srcCat) + " " + job.label + " " + catName(job.tgtCat)
                    + (job.note == null || job.note.isBlank() ? "" : "（" + job.note + "）");
            step.put("title", title);
            try {
                int n = runJob(job.sql, batch);
                step.put("affected", n);
            } catch (Exception e) {
                String msg = rootMessage(e);
                step.put("error", msg);
                errors.add(title + "：" + msg);
                log.warn("知识图谱关系初始化失败 {}: {}", title, msg);
            }
            steps.add(step);
        }
        if (errors.isEmpty()) {
            pgQueryService.update(
                    "DELETE FROM sh_kg_relation WHERE origin = 'init' AND batch_id IS DISTINCT FROM ?", batch);
            int derived = pgQueryService.queryInt("SELECT COUNT(*) FROM sh_kg_relation WHERE origin = 'init'");
            String message = "已从业务数据整理关系 " + derived + " 条";
            pgQueryService.update(
                    "UPDATE sh_kg_rel_meta SET initialized = 1, init_time = CURRENT_TIMESTAMP, init_count = ?, message = ? WHERE id = 1",
                    derived, clip(message, 500));
        } else {
            pgQueryService.update(
                    "UPDATE sh_kg_rel_meta SET message = ? WHERE id = 1",
                    clip("有 " + errors.size() + " 类关系没有生成", 500));
        }
        Map<String, Object> out = status();
        out.put("ok", errors.isEmpty());
        out.put("errors", errors);
        out.put("steps", steps);
        return out;
    }

    public Map<String, Object> saveManual(Map<String, Object> body) {
        ensure();
        String key = text(body, "relKey");
        String label = text(body, "relLabel");
        String srcCat = text(body, "srcCat");
        String tgtCat = text(body, "tgtCat");
        boolean custom = bool(body.get("custom"));
        if (custom) {
            label = relationLabel(label);
            srcCat = knownCat(srcCat, "起点");
            tgtCat = knownCat(tgtCat, "终点");
            key = resolveKey(label, srcCat, tgtCat);
        } else {
            Job matched = null;
            for (Job job : JOBS) {
                if (job.key.equals(key) && job.label.equals(label) && job.srcCat.equals(srcCat) && job.tgtCat.equals(tgtCat)) {
                    matched = job;
                    break;
                }
            }
            if (matched == null) {
                throw new IllegalArgumentException("请选择一种已有的关系，或改用「新增一种关系」");
            }
        }
        String srcName = requiredName(text(body, "srcName"), "起点");
        String tgtName = requiredName(text(body, "tgtName"), "终点");
        String srcId = nodeId(text(body, "srcId"), srcCat, srcName);
        String tgtId = nodeId(text(body, "tgtId"), tgtCat, tgtName);
        if (srcId.equals(tgtId)) {
            throw new IllegalArgumentException("起点和终点不能是同一个对象");
        }
        String wscd = blank(text(body, "wscd"));
        String wscdKey = wscd == null ? "" : wscd;
        Integer year = yearOf(body.get("surveyYear"));
        int n = pgQueryService.update("""
                INSERT INTO sh_kg_relation (
                    rel_key, rel_label, src_cat, src_id, src_name, tgt_cat, tgt_id, tgt_name,
                    wscd, wscd_key, survey_year, origin, enabled, batch_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, 'manual', 1, NULL)
                ON CONFLICT (rel_key, src_id, tgt_id, wscd_key) DO NOTHING
                """, key, label, srcCat, srcId, clip(srcName, 300), tgtCat, tgtId, clip(tgtName, 300), wscd, wscdKey, year);
        if (n == 0) {
            throw new IllegalArgumentException("关系库里已经有这一条，可在列表里找到后启用");
        }
        return Map.of("ok", true);
    }

    public void setEnabled(long id, boolean enabled) {
        ensure();
        int n = pgQueryService.update(
                "UPDATE sh_kg_relation SET enabled = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                enabled ? 1 : 0, id);
        if (n == 0) {
            throw new IllegalArgumentException("关系不存在");
        }
    }

    public void delete(long id) {
        ensure();
        int n = pgQueryService.update("DELETE FROM sh_kg_relation WHERE id = ?", id);
        if (n == 0) {
            throw new IllegalArgumentException("关系不存在");
        }
    }

    /** 总览：某类关系在关系库中的启用数与总数。未初始化时调用方应保持现算连线。 */
    public List<Map<String, Object>> overviewStats(Integer year) {
        List<Object> args = new ArrayList<>();
        String yearSql = yearAnd(year, args);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : pgQueryService.queryForList(
                "SELECT src_cat, rel_label, tgt_cat, "
                        + "COUNT(DISTINCT src_id || CHR(1) || tgt_id) FILTER (WHERE enabled = 1) AS enabled_n, "
                        + "COUNT(DISTINCT src_id || CHR(1) || tgt_id) AS all_n "
                        + "FROM sh_kg_relation WHERE 1=1" + yearSql
                        + " GROUP BY src_cat, rel_label, tgt_cat",
                args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("srcCat", str(r.get("src_cat")));
            m.put("label", str(r.get("rel_label")));
            m.put("tgtCat", str(r.get("tgt_cat")));
            m.put("enabled", asLong(r.get("enabled_n")));
            m.put("all", asLong(r.get("all_n")));
            out.add(m);
        }
        return out;
    }

    /** 某个小流域上要隐藏或补上的边。enabled 传 0/1，origin 为空表示不限来源。 */
    public List<Map<String, Object>> edges(String wscd, Integer year, int enabled, String origin) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT src_id, tgt_id, rel_label, src_name, tgt_name, src_cat, tgt_cat FROM sh_kg_relation "
                        + "WHERE enabled = ? AND (wscd = ? OR src_id = ? OR tgt_id = ?)");
        args.add(enabled);
        args.add(wscd);
        args.add("ws:" + wscd);
        args.add("ws:" + wscd);
        if (origin != null && !origin.isBlank()) {
            sql.append(" AND origin = ?");
            args.add(origin);
        }
        sql.append(yearAnd(year, args));
        if ("manual".equals(origin)) {
            sql.append(" ORDER BY id DESC LIMIT 200");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : pgQueryService.queryForList(sql.toString(), args.toArray())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("srcId", str(r.get("src_id")));
            m.put("tgtId", str(r.get("tgt_id")));
            m.put("label", str(r.get("rel_label")));
            m.put("srcName", str(r.get("src_name")));
            m.put("tgtName", str(r.get("tgt_name")));
            m.put("srcCat", str(r.get("src_cat")));
            m.put("tgtCat", str(r.get("tgt_cat")));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> rules() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : pgQueryService.queryForList(
                "SELECT src_cat, rel_label, tgt_cat, COUNT(*) AS n FROM sh_kg_relation GROUP BY src_cat, rel_label, tgt_cat")) {
            counts.put(str(r.get("src_cat")) + "\0" + str(r.get("rel_label")) + "\0" + str(r.get("tgt_cat")), asLong(r.get("n")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> shapes = new LinkedHashSet<>();
        for (Job job : JOBS) {
            String shape = job.srcCat + "\0" + job.label + "\0" + job.tgtCat;
            String token = job.key + "|" + job.srcCat + "|" + job.tgtCat + "|" + job.label;
            if (!seen.add(token) || !shapes.add(shape)) {
                continue;
            }
            out.add(ruleRow(token, job.key, job.label, job.srcCat, job.tgtCat, job.blurb, false,
                    counts.getOrDefault(shape, 0L)));
        }
        for (Map<String, Object> r : pgQueryService.queryForList(
                "SELECT MIN(rel_key) AS rel_key, rel_label, src_cat, tgt_cat FROM sh_kg_relation "
                        + "GROUP BY rel_label, src_cat, tgt_cat")) {
            String src = str(r.get("src_cat"));
            String tgt = str(r.get("tgt_cat"));
            String label = str(r.get("rel_label"));
            String shape = src + "\0" + label + "\0" + tgt;
            if (label.isEmpty() || !shapes.add(shape) || !CAT_NAME.containsKey(src) || !CAT_NAME.containsKey(tgt)) {
                continue;
            }
            String key = str(r.get("rel_key"));
            String token = key + "|" + src + "|" + tgt + "|" + label;
            out.add(ruleRow(token, key, label, src, tgt, "手工补充的关系。", true, counts.getOrDefault(shape, 0L)));
        }
        return out;
    }

    private Map<String, Object> ruleRow(String token, String key, String label, String srcCat, String tgtCat,
                                        String blurb, boolean custom, long count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("key", key);
        m.put("label", label);
        m.put("srcCat", srcCat);
        m.put("tgtCat", tgtCat);
        m.put("srcCatName", catName(srcCat));
        m.put("tgtCatName", catName(tgtCat));
        m.put("srcHint", hint(srcCat));
        m.put("tgtHint", hint(tgtCat));
        m.put("blurb", blurb);
        m.put("custom", custom);
        m.put("count", count);
        return m;
    }

    /** 已有同名同两端类型时沿用原来的编号，避免下拉里出现两条一样的关系。 */
    private String resolveKey(String label, String srcCat, String tgtCat) {
        for (Job job : JOBS) {
            if (job.label.equals(label) && job.srcCat.equals(srcCat) && job.tgtCat.equals(tgtCat)) {
                return job.key;
            }
        }
        List<Map<String, Object>> rows = pgQueryService.queryForList(
                "SELECT rel_key FROM sh_kg_relation WHERE rel_label = ? AND src_cat = ? AND tgt_cat = ? LIMIT 1",
                label, srcCat, tgtCat);
        if (!rows.isEmpty()) {
            String key = str(rows.get(0).get("rel_key"));
            if (!key.isEmpty()) {
                return key;
            }
        }
        String key = "x:" + label;
        if (key.length() > 48) {
            throw new IllegalArgumentException("关系名称过长");
        }
        return key;
    }

    private static String relationLabel(String label) {
        String n = blank(label);
        if (n == null) {
            throw new IllegalArgumentException("请填写关系名称");
        }
        if (n.length() > 16) {
            throw new IllegalArgumentException("关系名称请控制在 16 个字以内");
        }
        if (n.indexOf('|') >= 0 || n.indexOf('\n') >= 0 || n.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("关系名称不能包含换行或竖线");
        }
        return n;
    }

    private static String knownCat(String cat, String side) {
        String n = blank(cat);
        if (n == null || !CAT_NAME.containsKey(n)) {
            throw new IllegalArgumentException("请选择" + side + "类型");
        }
        return n;
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    private int runJob(String select, String batch) {
        String sql = """
                INSERT INTO sh_kg_relation (
                    rel_key, rel_label, src_cat, src_id, src_name, tgt_cat, tgt_id, tgt_name,
                    wscd, wscd_key, survey_year, origin, enabled, batch_id)
                SELECT DISTINCT ON (rel_key, LEFT(src_id, 300), LEFT(tgt_id, 300), COALESCE(NULLIF(btrim(wscd), ''), ''))
                    rel_key, rel_label, src_cat,
                    LEFT(src_id, 300),
                    LEFT(COALESCE(NULLIF(btrim(src_name), ''), src_id), 300),
                    tgt_cat,
                    LEFT(tgt_id, 300),
                    LEFT(COALESCE(NULLIF(btrim(tgt_name), ''), tgt_id), 300),
                    NULLIF(btrim(wscd), ''),
                    COALESCE(NULLIF(btrim(wscd), ''), ''),
                    survey_year,
                    'init', 1, ?
                FROM (
                """ + select + """
                ) q
                WHERE src_id IS NOT NULL AND tgt_id IS NOT NULL
                  AND btrim(src_id) <> '' AND btrim(tgt_id) <> '' AND src_id <> tgt_id
                ORDER BY rel_key, LEFT(src_id, 300), LEFT(tgt_id, 300), COALESCE(NULLIF(btrim(wscd), ''), ''), survey_year DESC NULLS LAST
                """ + UPSERT;
        return pgQueryService.update(sql, batch);
    }

    private void ensure() {
        if (tableReady) {
            return;
        }
        synchronized (this) {
            if (tableReady) {
                return;
            }
            pgQueryService.execute("""
                    CREATE TABLE IF NOT EXISTS sh_kg_relation (
                        id BIGSERIAL PRIMARY KEY,
                        rel_key VARCHAR(48) NOT NULL,
                        rel_label VARCHAR(32) NOT NULL,
                        src_cat VARCHAR(32) NOT NULL,
                        src_id VARCHAR(300) NOT NULL,
                        src_name VARCHAR(300),
                        tgt_cat VARCHAR(32) NOT NULL,
                        tgt_id VARCHAR(300) NOT NULL,
                        tgt_name VARCHAR(300),
                        wscd VARCHAR(64),
                        wscd_key VARCHAR(64) NOT NULL DEFAULT '',
                        survey_year INT,
                        origin VARCHAR(16) NOT NULL DEFAULT 'init',
                        enabled SMALLINT NOT NULL DEFAULT 1,
                        batch_id VARCHAR(32),
                        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT uk_sh_kg_relation UNIQUE (rel_key, src_id, tgt_id, wscd_key),
                        CONSTRAINT sh_kg_relation_origin CHECK (origin IN ('init', 'manual')),
                        CONSTRAINT sh_kg_relation_enabled CHECK (enabled IN (0, 1))
                    )""");
            pgQueryService.execute("CREATE INDEX IF NOT EXISTS idx_sh_kg_relation_wscd ON sh_kg_relation (wscd, enabled)");
            pgQueryService.execute("""
                    CREATE TABLE IF NOT EXISTS sh_kg_rel_meta (
                        id INT PRIMARY KEY,
                        initialized SMALLINT NOT NULL DEFAULT 0,
                        init_time TIMESTAMP,
                        init_count INT,
                        message VARCHAR(500)
                    )""");
            pgQueryService.execute(
                    "INSERT INTO sh_kg_rel_meta (id, initialized) VALUES (1, 0) ON CONFLICT (id) DO NOTHING");
            tableReady = true;
        }
    }

    private String where(String relKey, String srcCat, String tgtCat, String origin, Integer enabled, String q, List<Object> args) {
        StringBuilder sb = new StringBuilder("1=1");
        if (blank(relKey) != null) {
            sb.append(" AND rel_key = ?");
            args.add(relKey.trim());
        }
        if (blank(srcCat) != null) {
            sb.append(" AND src_cat = ?");
            args.add(srcCat.trim());
        }
        if (blank(tgtCat) != null) {
            sb.append(" AND tgt_cat = ?");
            args.add(tgtCat.trim());
        }
        if ("init".equals(origin) || "manual".equals(origin)) {
            sb.append(" AND origin = ?");
            args.add(origin);
        }
        if (enabled != null && (enabled == 0 || enabled == 1)) {
            sb.append(" AND enabled = ?");
            args.add(enabled);
        }
        String keyword = blank(q);
        if (keyword != null) {
            String like = like(keyword);
            sb.append(" AND (COALESCE(src_name,'') ILIKE ? ESCAPE '!' OR COALESCE(tgt_name,'') ILIKE ? ESCAPE '!' "
                    + "OR COALESCE(src_id,'') ILIKE ? ESCAPE '!' OR COALESCE(tgt_id,'') ILIKE ? ESCAPE '!' "
                    + "OR COALESCE(wscd,'') ILIKE ? ESCAPE '!')");
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        }
        return sb.toString();
    }

    private Map<String, Object> toRow(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", asLong(r.get("id")));
        m.put("relKey", str(r.get("rel_key")));
        m.put("relLabel", str(r.get("rel_label")));
        m.put("srcCat", str(r.get("src_cat")));
        m.put("srcCatName", catName(str(r.get("src_cat"))));
        m.put("srcId", str(r.get("src_id")));
        m.put("srcName", str(r.get("src_name")));
        m.put("tgtCat", str(r.get("tgt_cat")));
        m.put("tgtCatName", catName(str(r.get("tgt_cat"))));
        m.put("tgtId", str(r.get("tgt_id")));
        m.put("tgtName", str(r.get("tgt_name")));
        m.put("wscd", blank(str(r.get("wscd"))));
        Object year = r.get("survey_year");
        m.put("surveyYear", year == null ? null : asInt(year));
        m.put("origin", str(r.get("origin")));
        m.put("enabled", asInt(r.get("enabled")) == 1);
        m.put("updateTime", timeText(r.get("update_time")));
        return m;
    }

    private String yearAnd(Integer year, List<Object> args) {
        if (year == null) {
            return "";
        }
        if (year == 0) {
            return " AND survey_year IS NULL";
        }
        args.add(year);
        return " AND (survey_year = ? OR (origin = 'manual' AND survey_year IS NULL))";
    }

    private static String nodeId(String given, String cat, String name) {
        String g = blank(given);
        if (g != null) {
            if (g.indexOf(':') < 0) {
                g = prefix(cat) + g;
            }
            if (g.length() > 300) {
                throw new IllegalArgumentException("标识过长");
            }
            return g;
        }
        return "manual:" + cat + ":" + name;
    }

    private static String prefix(String cat) {
        return switch (cat) {
            case "watershed" -> "ws:";
            case "river" -> "river:";
            case "county" -> "county:";
            case "town" -> "town:";
            case "prevention" -> "obj:";
            case "section" -> "sec:";
            case "hazard" -> "haz:";
            case "road" -> "road:";
            case "gully" -> "gully:";
            default -> cat + ":";
        };
    }

    private static String hint(String cat) {
        return switch (cat) {
            case "watershed" -> "小流域编码";
            case "river" -> "河流名称";
            case "county" -> "县代码";
            case "town" -> "乡镇代码";
            case "prevention" -> "防护对象代码";
            case "section" -> "断面记录 ID";
            case "hazard" -> "隐患记录 ID";
            case "road" -> "跨沟道路记录 ID";
            case "gully" -> "沟滩占地记录 ID";
            default -> "标识";
        };
    }

    private static String catName(String cat) {
        return CAT_NAME.getOrDefault(cat, cat == null ? "" : cat);
    }

    private static Integer yearOf(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        int n;
        try {
            n = raw instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("调查年份不正确");
        }
        if (n == 0) {
            return null;
        }
        if (n < 1900 || n > 2999) {
            throw new IllegalArgumentException("调查年份不正确");
        }
        return n;
    }

    private static String requiredName(String name, String side) {
        String n = blank(name);
        if (n == null) {
            throw new IllegalArgumentException("请填写" + side + "名称");
        }
        if (n.length() > 300) {
            throw new IllegalArgumentException(side + "名称过长");
        }
        return n;
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        return String.valueOf(body.get(key)).trim();
    }

    private static String like(String keyword) {
        return "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return clip(cur.getMessage() == null ? "生成失败" : cur.getMessage(), 240);
    }

    private static String timeText(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static int asInt(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long asLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
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

    private static String wsName(String wscdExpr) {
        return "COALESCE((SELECT COALESCE(NULLIF(TRIM(w.alias_name), ''), NULLIF(TRIM(w.wsnm), ''), TRIM(w.wscd)) "
                + "FROM sh_map_watershed w WHERE TRIM(w.wscd) = TRIM(" + wscdExpr + ") ORDER BY w.id LIMIT 1), TRIM(" + wscdExpr + "))";
    }

    private static String codeId(String codeExpr, String fallbackPrefix, String idExpr) {
        return "CASE WHEN NULLIF(TRIM(" + codeExpr + "), '') IS NULL THEN '" + fallbackPrefix + ":' || " + idExpr
                + "::text ELSE 'obj:' || TRIM(" + codeExpr + ") END";
    }

    private static List<Job> jobs() {
        List<Job> list = new ArrayList<>();
        list.add(contains("prevention",
                codeId("p.obj_pid", "prev", "p.id"),
                "COALESCE(NULLIF(TRIM(p.obj_name), ''), NULLIF(TRIM(p.obj_pid), ''), '防治对象')",
                "sh_map_prevention_area p",
                "空间面和调查名录按防护对象代码合成一条包含关系。",
                "空间面"));
        list.add(contains("prevention",
                codeId("p.fcd", "sobj", "p.id"),
                "COALESCE(NULLIF(TRIM(p.fnm), ''), NULLIF(TRIM(p.fcd), ''), '防治对象')",
                "sh_survey_object p",
                "空间面和调查名录按防护对象代码合成一条包含关系。",
                "名录"));
        list.add(contains("section",
                "'sec:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.sec_name), ''), NULLIF(TRIM(p.pid), ''), '断面')",
                "sh_map_section p",
                "小流域上的横断面、纵断面。",
                ""));
        list.add(contains("hazard",
                "'haz:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.name), ''), NULLIF(TRIM(p.pid), ''), '隐患')",
                "sh_map_hazard_point p",
                "沟道里的桥涵、塘坝等风险点。",
                ""));
        list.add(contains("road",
                "'road:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.name), ''), NULLIF(TRIM(p.code), ''), '跨沟道路')",
                "sh_survey_road p",
                "阻水的路、桥、塘坝。",
                ""));
        list.add(contains("gully",
                "'gully:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.name), ''), NULLIF(TRIM(p.code), ''), '沟滩占地')",
                "sh_survey_gully p",
                "沟道里被占用的滩地。",
                ""));
        list.add(new Job("flow", "流经", "river", "watershed", "小流域上填写的河流。", "",
                "SELECT 'flow' AS rel_key, '流经' AS rel_label, 'river' AS src_cat, "
                        + "'river:' || TRIM(p.river_name) AS src_id, TRIM(p.river_name) AS src_name, "
                        + "'watershed' AS tgt_cat, 'ws:' || TRIM(p.wscd) AS tgt_id, " + wsName("p.wscd") + " AS tgt_name, "
                        + "TRIM(p.wscd) AS wscd, p.survey_year AS survey_year "
                        + "FROM sh_map_watershed p "
                        + "WHERE p.wscd IS NOT NULL AND TRIM(p.wscd) <> '' "
                        + "AND p.river_name IS NOT NULL AND TRIM(p.river_name) <> ''"));
        list.add(along("section", "位于", "p.rvname", "sh_map_section p", "'sec:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.sec_name), ''), NULLIF(TRIM(p.pid), ''), '断面')",
                "断面上填写的河流。"));
        list.add(along("prevention", "沿河", "p.rvname", "sh_map_prevention_area p",
                codeId("p.obj_pid", "prev", "p.id"),
                "COALESCE(NULLIF(TRIM(p.obj_name), ''), NULLIF(TRIM(p.obj_pid), ''), '防治对象')",
                "防治对象所沿的河流。"));
        list.add(along("prevention", "沿河", "p.rvnm", "sh_survey_object p",
                codeId("p.fcd", "sobj", "p.id"),
                "COALESCE(NULLIF(TRIM(p.fnm), ''), NULLIF(TRIM(p.fcd), ''), '防治对象')",
                "防治对象所沿的河流。"));
        list.add(along("hazard", "沿河", "p.rvname", "sh_map_hazard_point p", "'haz:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.name), ''), NULLIF(TRIM(p.pid), ''), '隐患')",
                "隐患要素所沿的河流。"));
        list.add(along("road", "沿河", "p.rvnm", "sh_survey_road p", "'road:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.name), ''), NULLIF(TRIM(p.code), ''), '跨沟道路')",
                "跨沟道路所沿的河流。"));
        list.add(along("gully", "沿河", "p.rvnm", "sh_survey_gully p", "'gully:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.name), ''), NULLIF(TRIM(p.code), ''), '沟滩占地')",
                "沟滩占地所沿的河流。"));
        list.add(new Job("threaten", "威胁", "hazard", "prevention", "隐患按防护对象代码指向受威胁的对象。", "",
                "SELECT 'threaten' AS rel_key, '威胁' AS rel_label, 'hazard' AS src_cat, "
                        + "'haz:' || p.id::text AS src_id, "
                        + "COALESCE(NULLIF(TRIM(p.name), ''), NULLIF(TRIM(p.pid), ''), '隐患') AS src_name, "
                        + "'prevention' AS tgt_cat, 'obj:' || TRIM(p.fcd) AS tgt_id, "
                        + "COALESCE(NULLIF(TRIM(p.fnm), ''), TRIM(p.fcd)) AS tgt_name, "
                        + "TRIM(p.wscd) AS wscd, p.survey_year AS survey_year "
                        + "FROM sh_map_hazard_point p "
                        + "WHERE p.fcd IS NOT NULL AND TRIM(p.fcd) <> ''"));
        list.add(govern());
        list.add(involve("county", "县代码对应到所涉小流域。"));
        list.add(involve("town", "乡镇代码对应到所涉小流域。"));
        list.add(hasTown("prevention", "sh_survey_object", codeId("p.fcd", "sobj", "p.id"),
                "COALESCE(NULLIF(TRIM(p.fnm), ''), NULLIF(TRIM(p.fcd), ''), '防治对象')",
                "乡镇辖有的防治对象。"));
        list.add(hasTown("road", "sh_survey_road", "'road:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.name), ''), NULLIF(TRIM(p.code), ''), '跨沟道路')",
                "乡镇辖有的跨沟道路。"));
        list.add(hasTown("gully", "sh_survey_gully", "'gully:' || p.id::text",
                "COALESCE(NULLIF(TRIM(p.name), ''), NULLIF(TRIM(p.code), ''), '沟滩占地')",
                "乡镇辖有的沟滩占地。"));
        list.add(affect("backwater", "壅水影响", "backwater_objname", "壅水时名称能对上的防治对象。"));
        list.add(affect("burst", "溃决影响", "burst_objname", "溃决时名称能对上的防治对象。"));
        list.add(affect("overflow", "漫溢影响", "overflow_objname", "漫溢时名称能对上的防治对象。"));
        return List.copyOf(list);
    }

    private static Job contains(String tgtCat, String tgtId, String tgtName, String table, String blurb, String note) {
        String sql = "SELECT 'contains' AS rel_key, '包含' AS rel_label, 'watershed' AS src_cat, "
                + "'ws:' || TRIM(p.wscd) AS src_id, " + wsName("p.wscd") + " AS src_name, "
                + "'" + tgtCat + "' AS tgt_cat, " + tgtId + " AS tgt_id, " + tgtName + " AS tgt_name, "
                + "TRIM(p.wscd) AS wscd, p.survey_year AS survey_year FROM " + table
                + " WHERE p.wscd IS NOT NULL AND TRIM(p.wscd) <> ''";
        return new Job("contains", "包含", "watershed", tgtCat, blurb, note, sql);
    }

    private static Job along(String srcCat, String label, String riverExpr, String table, String srcId, String srcName, String blurb) {
        String key = "位于".equals(label) ? "locate" : "along";
        String sql = "SELECT '" + key + "' AS rel_key, '" + label + "' AS rel_label, '" + srcCat + "' AS src_cat, "
                + srcId + " AS src_id, " + srcName + " AS src_name, "
                + "'river' AS tgt_cat, 'river:' || TRIM(" + riverExpr + ") AS tgt_id, TRIM(" + riverExpr + ") AS tgt_name, "
                + "TRIM(p.wscd) AS wscd, p.survey_year AS survey_year FROM " + table
                + " WHERE " + riverExpr + " IS NOT NULL AND TRIM(" + riverExpr + ") <> ''";
        return new Job(key, label, srcCat, "river", blurb, "", sql);
    }

    private static Job govern() {
        String sql = "SELECT 'govern' AS rel_key, '下辖' AS rel_label, 'county' AS src_cat, "
                + "'county:' || TRIM(t.county_adcd) AS src_id, "
                + "COALESCE(NULLIF(TRIM(t.county_name), ''), TRIM(t.county_adcd)) AS src_name, "
                + "'town' AS tgt_cat, 'town:' || TRIM(t.town_adcd) AS tgt_id, "
                + "COALESCE(NULLIF(TRIM(t.town_name), ''), TRIM(t.town_adcd)) AS tgt_name, "
                + "NULLIF(TRIM(t.wscd), '') AS wscd, t.survey_year AS survey_year FROM ("
                + "SELECT county_adcd, county_name, town_adcd, town_name, wscd, survey_year FROM sh_survey_object "
                + "UNION ALL SELECT county_adcd, county_name, town_adcd, town_name, wscd, survey_year FROM sh_survey_road "
                + "UNION ALL SELECT county_adcd, county_name, town_adcd, town_name, wscd, survey_year FROM sh_survey_gully"
                + ") t WHERE t.county_adcd IS NOT NULL AND TRIM(t.county_adcd) <> '' "
                + "AND t.town_adcd IS NOT NULL AND TRIM(t.town_adcd) <> ''";
        return new Job("govern", "下辖", "county", "town", "调查成果里的县与乡镇。", "", sql);
    }

    private static Job involve(String adminCat, String blurb) {
        String adcd = "county".equals(adminCat) ? "county_adcd" : "town_adcd";
        String name = "county".equals(adminCat) ? "county_name" : "town_name";
        String prefix = "county".equals(adminCat) ? "county:" : "town:";
        String sql = "SELECT 'involve' AS rel_key, '涉及' AS rel_label, '" + adminCat + "' AS src_cat, "
                + "'" + prefix + "' || TRIM(t." + adcd + ") AS src_id, "
                + "COALESCE(NULLIF(TRIM(t." + name + "), ''), TRIM(t." + adcd + ")) AS src_name, "
                + "'watershed' AS tgt_cat, 'ws:' || TRIM(t.wscd) AS tgt_id, " + wsName("t.wscd") + " AS tgt_name, "
                + "TRIM(t.wscd) AS wscd, t.survey_year AS survey_year FROM ("
                + "SELECT " + adcd + ", " + name + ", wscd, survey_year FROM sh_survey_object "
                + "UNION ALL SELECT " + adcd + ", " + name + ", wscd, survey_year FROM sh_survey_road "
                + "UNION ALL SELECT " + adcd + ", " + name + ", wscd, survey_year FROM sh_survey_gully"
                + ") t WHERE t." + adcd + " IS NOT NULL AND TRIM(t." + adcd + ") <> '' "
                + "AND t.wscd IS NOT NULL AND TRIM(t.wscd) <> ''";
        return new Job("involve", "涉及", adminCat, "watershed", blurb, "", sql);
    }

    private static Job hasTown(String tgtCat, String table, String tgtId, String tgtName, String blurb) {
        String sql = "SELECT 'has' AS rel_key, '辖有' AS rel_label, 'town' AS src_cat, "
                + "'town:' || TRIM(p.town_adcd) AS src_id, "
                + "COALESCE(NULLIF(TRIM(p.town_name), ''), TRIM(p.town_adcd)) AS src_name, "
                + "'" + tgtCat + "' AS tgt_cat, " + tgtId + " AS tgt_id, " + tgtName + " AS tgt_name, "
                + "TRIM(p.wscd) AS wscd, p.survey_year AS survey_year FROM " + table + " p "
                + "WHERE p.town_adcd IS NOT NULL AND TRIM(p.town_adcd) <> ''";
        return new Job("has", "辖有", "town", tgtCat, blurb, "", sql);
    }

    private static Job affect(String key, String label, String column, String blurb) {
        String sql = "SELECT '" + key + "' AS rel_key, '" + label + "' AS rel_label, 'road' AS src_cat, "
                + "'road:' || r.id::text AS src_id, "
                + "COALESCE(NULLIF(TRIM(r.name), ''), NULLIF(TRIM(r.code), ''), '跨沟道路') AS src_name, "
                + "'prevention' AS tgt_cat, "
                + "CASE WHEN NULLIF(TRIM(o.code), '') IS NULL THEN o.fallback ELSE 'obj:' || TRIM(o.code) END AS tgt_id, "
                + "COALESCE(NULLIF(TRIM(o.obj_name), ''), NULLIF(TRIM(o.code), ''), '防治对象') AS tgt_name, "
                + "TRIM(r.wscd) AS wscd, r.survey_year AS survey_year "
                + "FROM sh_survey_road r JOIN ("
                + "SELECT wscd, fnm AS obj_name, fcd AS code, 'sobj:' || id::text AS fallback FROM sh_survey_object "
                + "UNION ALL SELECT wscd, obj_name, obj_pid, 'prev:' || id::text FROM sh_map_prevention_area"
                + ") o ON TRIM(o.wscd) = TRIM(r.wscd) "
                + "WHERE r.wscd IS NOT NULL AND TRIM(r.wscd) <> '' "
                + "AND o.obj_name IS NOT NULL AND char_length(TRIM(o.obj_name)) >= 2 "
                + "AND position(TRIM(o.obj_name) in COALESCE(r." + column + ", '')) > 0";
        return new Job(key, label, "road", "prevention", blurb, "", sql);
    }
}
