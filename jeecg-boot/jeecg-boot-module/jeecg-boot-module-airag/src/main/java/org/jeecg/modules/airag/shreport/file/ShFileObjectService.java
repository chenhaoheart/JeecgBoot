package org.jeecg.modules.airag.shreport.file;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一张图调查成果文件对象管理：把按规则存放在磁盘上的照片/视频
 * （根目录/流域/县/电子数据/照片/类别/河流代码/隐患点编码/文件）登记到
 * sh_file_object 表，并提供分页筛选、按隐患点取文件、手动绑定与安全预览。
 * <p>
 * 库表只存相对路径（与操作系统无关），物理位置由 shreport.file.root-path 配置：
 * Windows 用盘符，Linux 部署改为挂载目录即可，无需迁移数据结构。
 */
@Slf4j
@Service
public class ShFileObjectService {

    /** 文件名中的上下游标记：A0001上01.jpg -> 上 */
    private static final Pattern COUNTY_DIR = Pattern.compile("^(\\d{6})_(.+)$");
    /** 隐患点编码：县代码(6位数字)开头的长字母数字编码，如 622921ADA3700121N000000A0003 */
    private static final Pattern PID_CODE = Pattern.compile("^\\d{6}[A-Z][A-Z0-9]{9,}$");
    /** 河流代码（可带点类型后缀），如 ADA3700121、ADA3700121N000000 */
    private static final Pattern RIVER_CODE = Pattern.compile("^[A-Z]{2,4}\\d{4,}[A-Z0-9]*$");

    @Autowired
    private ShReportPgQueryService pgQueryService;

    @Value("${shreport.file.root-path:F:\\甘肃\\甘肃审核汇集2025一期126}")
    private String rootPath;

    @Value("${shreport.file.allowed-ext:jpg,jpeg,png,webp,bmp,mp4,mov,webm}")
    private String allowedExt;

    private Set<String> extWhitelist() {
        return new HashSet<>(Arrays.asList(allowedExt.toLowerCase(Locale.ROOT).split(",")));
    }

    public Path root() {
        return Paths.get(rootPath).toAbsolutePath().normalize();
    }

    /** 应用就绪后建文件对象表（幂等） */
    @EventListener(ApplicationReadyEvent.class)
    public void initTable() {
        try {
            pgQueryService.execute("CREATE TABLE IF NOT EXISTS sh_file_object ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "file_name TEXT, "
                    + "relative_path TEXT NOT NULL, "
                    + "category TEXT, "
                    + "river_code TEXT, "
                    + "pid TEXT, "
                    + "file_position TEXT, "
                    + "county_adcd TEXT, "
                    + "county_name TEXT, "
                    + "wsnm TEXT, "
                    + "file_type TEXT, "
                    + "ext TEXT, "
                    + "file_size BIGINT, "
                    + "file_mtime TIMESTAMP, "
                    + "source TEXT DEFAULT 'scan', "
                    + "create_time TIMESTAMP DEFAULT now(), "
                    + "CONSTRAINT uk_sh_file_object_path UNIQUE (relative_path))");
            pgQueryService.execute("CREATE INDEX IF NOT EXISTS idx_sh_file_object_pid ON sh_file_object (pid)");
            pgQueryService.execute("CREATE INDEX IF NOT EXISTS idx_sh_file_object_category ON sh_file_object (category)");
            log.info("一张图文件对象表 sh_file_object 就绪，根目录: {}", root());
        } catch (Exception e) {
            log.warn("创建 sh_file_object 表失败: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 扫描索引

    /** 扫描结果统计 */
    public Map<String, Object> scan() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("照片根目录不存在或不可读: " + root);
        }
        Set<String> exts = extWhitelist();
        int inserted = 0;
        int updated = 0;
        int total = 0;
        List<String> errors = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                String name = p.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot < 0) {
                    continue;
                }
                String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (!exts.contains(ext)) {
                    continue;
                }
                total++;
                String relative = root.relativize(p).normalize().toString().replace('\\', '/');
                Map<String, Object> parsed = parseRelative(relative);
                try {
                    int n = upsert(relative, name, ext, p, parsed);
                    if (n > 0) {
                        inserted++;
                    } else {
                        updated++;
                    }
                } catch (Exception e) {
                    if (errors.size() < 5) {
                        errors.add(relative + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("扫描目录失败: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("inserted", inserted);
        out.put("updated", updated);
        out.put("errors", errors);
        // 与隐患点编码的匹配情况（文件夹名 = 县代码+河流代码+点号 = pid）
        try {
            int matchedFiles = pgQueryService.queryInt(
                    "SELECT COUNT(*) FROM sh_file_object f WHERE f.pid IS NOT NULL "
                            + "AND EXISTS (SELECT 1 FROM sh_map_hazard_point h WHERE h.pid = f.pid)");
            Integer matchedPids = pgQueryService.queryForObject(
                    "SELECT COUNT(DISTINCT f.pid) FROM sh_file_object f WHERE f.pid IS NOT NULL "
                            + "AND EXISTS (SELECT 1 FROM sh_map_hazard_point h WHERE h.pid = f.pid)", Integer.class);
            Integer allPids = pgQueryService.queryForObject(
                    "SELECT COUNT(DISTINCT pid) FROM sh_file_object WHERE pid IS NOT NULL", Integer.class);
            out.put("matchedFiles", matchedFiles);
            out.put("matchedPids", matchedPids == null ? 0 : matchedPids);
            out.put("allPids", allPids == null ? 0 : allPids);
        } catch (Exception e) {
            log.warn("统计文件与隐患点匹配情况失败: {}", e.getMessage());
        }
        log.info("一张图文件扫描完成: {}", out);
        return out;
    }

    /**
     * 解析相对路径：流域/县/.../照片/类别/河流代码/隐患点编码/文件名。
     * 目录层级以「照片」段为锚点；隐患点编码（县代码开头的长编码）从最深的目录段
     * 往回识别，兼容缺「类别」层的 照片/河流代码/隐患点编码/文件 结构——
     * 固定层数解析会把 pid 错位成文件名，导致报告按编码取不到照片。
     * 层级异常时尽量解析、其余留空。
     */
    Map<String, Object> parseRelative(String relative) {
        Map<String, Object> m = new LinkedHashMap<>();
        String[] parts = relative.split("/");
        m.put("wsnm", parts.length > 0 ? parts[0] : null);
        m.put("countyAdcd", null);
        m.put("countyName", null);
        if (parts.length > 1) {
            Matcher mt = COUNTY_DIR.matcher(parts[1]);
            if (mt.matches()) {
                m.put("countyAdcd", mt.group(1));
                m.put("countyName", mt.group(2));
            }
        }
        m.put("category", null);
        m.put("riverCode", null);
        m.put("pid", null);
        int photoIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            if ("照片".equals(parts[i])) {
                photoIdx = i;
                break;
            }
        }
        if (photoIdx >= 0) {
            // 照片之后的目录段（不含末尾文件名）：最深优先找隐患点编码，其上一段若是河流代码则一并认领
            for (int i = parts.length - 2; i > photoIdx; i--) {
                String seg = parts[i];
                if (PID_CODE.matcher(seg).matches()) {
                    m.put("pid", seg);
                    if (i - 1 > photoIdx && RIVER_CODE.matcher(parts[i - 1]).matches()) {
                        m.put("riverCode", parts[i - 1]);
                    }
                    break;
                }
            }
            // 类别：照片后第一段，仅当它不是河流/隐患点编码（即真正的中文类别层存在）时才算
            if (parts.length > photoIdx + 1) {
                String first = parts[photoIdx + 1];
                boolean isCode = PID_CODE.matcher(first).matches() || RIVER_CODE.matcher(first).matches();
                if (!isCode) {
                    m.put("category", first);
                }
            }
        }
        String fileName = parts[parts.length - 1];
        m.put("filePosition", fileName.contains("上") ? "上" : fileName.contains("下") ? "下" : null);
        String lower = fileName.toLowerCase(Locale.ROOT);
        m.put("fileType", lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm") ? "video" : "photo");
        return m;
    }

    private int upsert(String relative, String fileName, String ext, Path file, Map<String, Object> parsed) throws IOException {
        long size;
        Timestamp mtime;
        try {
            size = Files.size(file);
            mtime = Timestamp.from(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()));
        } catch (IOException e) {
            size = 0;
            mtime = null;
        }
        return pgQueryService.update(
                "INSERT INTO sh_file_object (file_name, relative_path, category, river_code, pid, file_position, "
                        + "county_adcd, county_name, wsnm, file_type, ext, file_size, file_mtime, source) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'scan') "
                        + "ON CONFLICT (relative_path) DO UPDATE SET file_size = EXCLUDED.file_size, "
                        + "file_mtime = EXCLUDED.file_mtime",
                fileName, relative, parsed.get("category"), parsed.get("riverCode"), parsed.get("pid"),
                parsed.get("filePosition"), parsed.get("countyAdcd"), parsed.get("countyName"), parsed.get("wsnm"),
                parsed.get("fileType"), ext, size, mtime);
    }

    // ---------------------------------------------------------------- 查询 / 绑定

    /** 分页筛选：category/filePosition/fileType/riverCode/wsnm/countyAdcd/pid/search/matched */
    public Map<String, Object> list(Integer pageNo, Integer pageSize, String category, String filePosition,
                                    String fileType, String riverCode, String wsnm, String countyAdcd,
                                    String pid, String search, Boolean matched) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (notBlank(category)) {
            where.append(" AND f.category = ? ");
            args.add(category.trim());
        }
        if (notBlank(filePosition)) {
            where.append(" AND f.file_position = ? ");
            args.add(filePosition.trim());
        }
        if (notBlank(fileType)) {
            where.append(" AND f.file_type = ? ");
            args.add(fileType.trim());
        }
        if (notBlank(riverCode)) {
            where.append(" AND f.river_code = ? ");
            args.add(riverCode.trim());
        }
        if (notBlank(wsnm)) {
            where.append(" AND f.wsnm = ? ");
            args.add(wsnm.trim());
        }
        if (notBlank(countyAdcd)) {
            where.append(" AND f.county_adcd = ? ");
            args.add(countyAdcd.trim());
        }
        if (notBlank(pid)) {
            where.append(" AND f.pid = ? ");
            args.add(pid.trim());
        }
        if (matched != null) {
            String exists = "EXISTS (SELECT 1 FROM sh_map_hazard_point h WHERE h.pid = f.pid)";
            if (matched) {
                where.append(" AND f.pid IS NOT NULL AND ").append(exists);
            } else {
                where.append(" AND (f.pid IS NULL OR NOT ").append(exists).append(") ");
            }
        }
        if (notBlank(search)) {
            where.append(" AND (f.file_name ILIKE ? OR f.pid ILIKE ?) ");
            String q = "%" + search.trim() + "%";
            args.add(q);
            args.add(q);
        }
        int pn = Math.max(pageNo == null ? 1 : pageNo, 1);
        int ps = Math.min(Math.max(pageSize == null ? 20 : pageSize, 1), 200);
        String base = "SELECT f.* FROM sh_file_object f" + where;
        Integer total = pgQueryService.queryForObject("SELECT COUNT(*) FROM (" + base + ") t", Integer.class, args.toArray());
        List<Map<String, Object>> data = pgQueryService.queryForList(
                base + " ORDER BY f.wsnm, f.pid NULLS LAST, f.file_position NULLS LAST, f.file_name "
                        + "LIMIT " + ps + " OFFSET " + ((pn - 1) * ps), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("pageNo", pn);
        out.put("pageSize", ps);
        out.put("data", data);
        return out;
    }

    /**
     * 报告表格用：同一隐患点编码只取一张可嵌入 Word 的现场照片。
     * 优先文件名解析为「上」的，其次「下」，其余按文件名排序取第一张。视频和 webp 不选。
     */
    public PickedPhoto pickPhoto(String pid) {
        Map<String, Object> row = choosePhotoRow(listByPid(pid));
        if (row == null) {
            return null;
        }
        try {
            Path path = resolveForPreview(String.valueOf(row.get("relative_path")));
            byte[] bytes = Files.readAllBytes(path);
            String ext = extOf(row);
            String name = row.get("file_name") == null ? "photo." + ext : String.valueOf(row.get("file_name"));
            return new PickedPhoto(bytes, pictureTypeOf(ext), name);
        } catch (Exception e) {
            log.warn("读取现场照片失败 pid={}: {}", pid, e.getMessage());
            return null;
        }
    }

    /**
     * 从同一编码的文件里选出要嵌入报告的那一张。没有合格照片时返回 null。
     */
    public Map<String, Object> choosePhotoRow(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, Object> best = null;
        int bestRank = Integer.MAX_VALUE;
        String bestName = null;
        for (Map<String, Object> row : rows) {
            if (!embeddablePhoto(row)) {
                continue;
            }
            int rank = positionRank(row.get("file_position"));
            String name = row.get("file_name") == null ? "" : String.valueOf(row.get("file_name"));
            if (best == null || rank < bestRank || (rank == bestRank && name.compareTo(bestName) < 0)) {
                best = row;
                bestRank = rank;
                bestName = name;
            }
        }
        return best;
    }

    /** 按隐患点编码取文件（详情弹窗照片/视频分组用） */
    public List<Map<String, Object>> listByPid(String pid) {
        if (!notBlank(pid)) {
            return List.of();
        }
        return pgQueryService.queryForList(
                "SELECT id, file_name, relative_path, category, file_position, file_type, ext, file_size "
                        + "FROM sh_file_object WHERE pid = ? "
                        + "ORDER BY category, file_position NULLS LAST, file_name", pid.trim());
    }

    /** 手动绑定：把文件关联到指定隐患点编码 */
    public int bind(Long id, String pid) {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        String v = pid == null ? null : pid.trim();
        if (v != null && v.isEmpty()) {
            v = null;
        }
        return pgQueryService.update("UPDATE sh_file_object SET pid = ? WHERE id = ?", v, id);
    }

    /** 筛选项：类别/流域/县 去重清单 */
    public Map<String, Object> facets() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", distinct("category"));
        out.put("wsnms", distinct("wsnm"));
        out.put("counties", pgQueryService.queryForList(
                "SELECT DISTINCT county_adcd AS adcd, county_name AS name FROM sh_file_object "
                        + "WHERE county_adcd IS NOT NULL ORDER BY county_adcd"));
        return out;
    }

    private List<String> distinct(String col) {
        List<Map<String, Object>> rows = pgQueryService.queryForList(
                "SELECT DISTINCT " + col + " AS v FROM sh_file_object WHERE " + col
                        + " IS NOT NULL ORDER BY " + col);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object v = r.get("v");
            if (v != null) {
                out.add(String.valueOf(v));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 预览安全

    /** 预览校验：路径不得越出根目录、扩展名在白名单内；返回物理文件 */
    public Path resolveForPreview(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("缺少文件路径");
        }
        String rel = relativePath.trim().replace('\\', '/');
        if (rel.contains("..") || rel.startsWith("/")) {
            throw new IllegalArgumentException("非法文件路径");
        }
        Path root = root();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extWhitelist().contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + ext);
        }
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("文件不存在");
        }
        return target;
    }

    public String mediaTypeOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (name.endsWith(".webm")) {
            return "video/webm";
        }
        return "application/octet-stream";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Word 能直接嵌入的照片：jpg/png/gif/bmp。webp 与视频跳过。 */
    static boolean embeddablePhoto(Map<String, Object> row) {
        if (row == null) {
            return false;
        }
        if ("video".equalsIgnoreCase(str(row.get("file_type")))) {
            return false;
        }
        String ext = extOf(row);
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext)
                || "gif".equals(ext) || "bmp".equals(ext);
    }

    private static String extOf(Map<String, Object> row) {
        String ext = str(row.get("ext")).toLowerCase(Locale.ROOT);
        if (!ext.isEmpty()) {
            return ext;
        }
        String name = str(row.get("file_name"));
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 上游优先，其次下游，其余靠后 */
    private static int positionRank(Object position) {
        String p = str(position);
        if ("上".equals(p)) {
            return 0;
        }
        if ("下".equals(p)) {
            return 1;
        }
        return 2;
    }

    static int pictureTypeOf(String ext) {
        if ("png".equals(ext)) {
            return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG;
        }
        if ("gif".equals(ext)) {
            return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_GIF;
        }
        if ("bmp".equals(ext)) {
            return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_BMP;
        }
        return org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_JPEG;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 选出的一张现场照片 */
    public record PickedPhoto(byte[] bytes, int pictureType, String fileName) {
    }
}
