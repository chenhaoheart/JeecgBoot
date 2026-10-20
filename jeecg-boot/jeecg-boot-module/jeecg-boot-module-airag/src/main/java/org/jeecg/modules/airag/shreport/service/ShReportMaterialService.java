package org.jeecg.modules.airag.shreport.service;

import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.common.util.CommonUtils;
import org.jeecg.common.util.SpringContextUtils;
import org.jeecg.common.util.filter.SsrfFileTypeFilter;
import org.jeecg.modules.airag.llm.handler.AIChatHandler;
import org.jeecg.modules.airag.shreport.component.ShReportConcurrencyLimiter;
import org.jeecg.modules.airag.shreport.component.ShReportSessionStore;
import org.jeecg.modules.airag.shreport.material.MaterialChapterTagger;
import org.jeecg.modules.airag.shreport.material.MaterialDocExtractor;
import org.jeecg.modules.airag.shreport.material.MaterialExcelExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 山洪报告流域资料：上传落盘、异步解析、按章选取注入生成。
 */
@Slf4j
@Service
public class ShReportMaterialService {

    public static final Set<String> ALLOWED_EXT = Set.of(
            "xlsx", "xls", "pdf", "doc", "docx", "txt", "md",
            "jpg", "jpeg", "png", "webp", "bmp");

    private static final String VISION_PROMPT = "请逐字转写图中全部可见文字和表格（表格输出为 Markdown）。"
            + "然后按山洪调查要素结构化描述：河沟名称、地点、时间、灾种、水毁现象、工程措施、可见数据。"
            + "图中没有的内容不要编造。输出 Markdown。";

    private static final String MATERIAL_RULES = """
            【用户上传资料（本流域调查人员提供，文件名即出处）】
            使用规则：
            1. 资料中的事实可以采用，引用具体数字、年份、灾情时必须写明来源文件名，如「据《XX县历史山洪调查记录》…」；
            2. 与「实测数据包」冲突时：标注「以此为准」的资料优先采用；否则一律以实测数据包为准，禁止在同一章混用两个数字；
            3. 资料中没有、数据包中也没有的内容，仍写「暂无调查数据」，严禁编造。
            """;

    @Autowired
    private ShReportPgQueryService pgQuery;

    @Autowired
    private ShReportSessionStore sessionStore;

    @Autowired
    private AIChatHandler aiChatHandler;

    @Autowired
    private ShReportConcurrencyLimiter concurrencyLimiter;

    @Value("${jeecg.path.upload:}")
    private String uploadPath;

    @Value("${jeecg.uploadType:local}")
    private String uploadType;

    @Value("${shreport.material.enabled:true}")
    private boolean enabled;

    @Value("${shreport.material.max-size-mb:50}")
    private int maxSizeMb;

    @Value("${shreport.material.parse-thread:2}")
    private int parseThread;

    @Value("${shreport.material.excel-max-rows:200}")
    private int excelMaxRows;

    @Value("${shreport.material.excel-max-chars:12000}")
    private int excelMaxChars;

    @Value("${shreport.material.doc-max-chars:20000}")
    private int docMaxChars;

    @Value("${shreport.material.chapter-max-chars:5000}")
    private int chapterMaxChars;

    @Value("${shreport.material.per-file-chars:2000}")
    private int perFileChars;

    @Value("${shreport.material.vision-model-id:}")
    private String visionModelId;

    @Value("${shreport.agent.model-id:}")
    private String defaultModelId;

    private ExecutorService parsePool;

    /** 仿 flowVerByChapter：只存不删，saveChapter 取用 */
    private final ConcurrentHashMap<String, String> materialVerByChapter = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void initTable() {
        try {
            pgQuery.execute("CREATE TABLE IF NOT EXISTS sh_report_material ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "lybm TEXT, "
                    + "lymc TEXT, "
                    + "batch_no TEXT, "
                    + "file_name TEXT NOT NULL, "
                    + "file_path TEXT NOT NULL, "
                    + "file_type TEXT, "
                    + "ext TEXT, "
                    + "file_size BIGINT, "
                    + "category TEXT, "
                    + "chapter_nos TEXT, "
                    + "auto_tagged INT DEFAULT 0, "
                    + "data_override INT DEFAULT 0, "
                    + "parse_status TEXT DEFAULT 'pending', "
                    + "extracted_text TEXT, "
                    + "content_summary TEXT, "
                    + "fail_reason TEXT, "
                    + "model_id TEXT, "
                    + "create_by TEXT, "
                    + "create_time TIMESTAMP DEFAULT now(), "
                    + "update_time TIMESTAMP DEFAULT now(), "
                    + "deleted INT DEFAULT 0)");
            pgQuery.execute("CREATE INDEX IF NOT EXISTS idx_sh_report_material_watershed "
                    + "ON sh_report_material (lybm, batch_no)");
            pgQuery.execute("CREATE INDEX IF NOT EXISTS idx_sh_report_material_status "
                    + "ON sh_report_material (parse_status)");
            try {
                pgQuery.execute("ALTER TABLE sh_report_material ADD COLUMN IF NOT EXISTS model_id TEXT");
            } catch (Exception ignore) {
                // 新表已含该列
            }
            log.info("山洪报告资料表 sh_report_material 就绪");
        } catch (Exception e) {
            log.warn("创建 sh_report_material 失败: {}", e.getMessage());
        }
        int n = Math.max(1, parseThread);
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "sh-material-parse-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        parsePool = Executors.newFixedThreadPool(n, tf);
    }

    @PreDestroy
    public void shutdown() {
        if (parsePool != null) {
            parsePool.shutdownNow();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public Map<String, Object> upload(MultipartFile file, String conversationId, String lybmParam,
                                      String lymcParam, String batchNoParam, String category, String chapterNos) {
        ensureEnabled();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择文件");
        }
        String orig = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = FilenameUtils.getExtension(orig).toLowerCase(Locale.ROOT);
        if ("heic".equals(ext) || "heif".equals(ext)) {
            throw new IllegalArgumentException("暂不支持 HEIC，请转为 jpg/png 后上传");
        }
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型：" + ext + "，允许 "
                    + String.join("/", ALLOWED_EXT));
        }
        long maxBytes = (long) maxSizeMb * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException("文件超过 " + maxSizeMb + "MB");
        }
        JSONObject ctx = conversationId == null ? null : sessionStore.get(conversationId);
        String lybm = firstNonBlank(lybmParam, ctx == null ? null : ctx.getString("lybm"));
        String lymc = firstNonBlank(lymcParam, ctx == null ? null : ctx.getString("lymc"));
        String batchNo = firstNonBlank(batchNoParam, ctx == null ? null : ctx.getString("batchNo"));
        if (lybm == null || lybm.isBlank()) {
            throw new IllegalArgumentException("请先绑定流域再上传资料");
        }
        if (lybm.contains("..") || lybm.contains("/") || lybm.contains("\\")) {
            throw new IllegalArgumentException("流域编码非法");
        }
        String bizPath = "shreport/material/" + lybm.trim();
        try {
            SsrfFileTypeFilter.checkUploadFileType(file, bizPath);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage() == null ? "文件类型校验失败" : e.getMessage());
        }
        String saved;
        if (CommonConstant.UPLOAD_TYPE_LOCAL.equalsIgnoreCase(uploadType)) {
            saved = CommonUtils.uploadLocal(file, bizPath, uploadPath);
        } else {
            saved = CommonUtils.upload(file, bizPath, uploadType);
        }
        if (saved == null || saved.isBlank()) {
            throw new IllegalArgumentException("文件保存失败");
        }
        saved = saved.replace('\\', '/');
        String fileType = fileTypeOf(ext);
        String user = currentUser();
        String modelId = ctx == null ? null : ctx.getString("modelId");
        Long id = pgQuery.queryForObject(
                "INSERT INTO sh_report_material (lybm,lymc,batch_no,file_name,file_path,file_type,ext,file_size,"
                        + "category,chapter_nos,auto_tagged,parse_status,model_id,create_by,create_time,update_time,deleted) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,0,'pending',?,?,NOW(),NOW(),0) RETURNING id",
                Long.class,
                lybm, lymc, blankToNull(batchNo), orig.replace('\\', '/'), saved, fileType, ext, file.getSize(),
                blankToNull(category), blankToNull(chapterNos), blankToNull(modelId), user);
        submitParse(id);
        return getPublic(id);
    }

    public List<Map<String, Object>> list(String conversationId, String lybmParam, String batchNoParam) {
        ensureEnabled();
        JSONObject ctx = conversationId == null ? null : sessionStore.get(conversationId);
        String lybm = firstNonBlank(lybmParam, ctx == null ? null : ctx.getString("lybm"));
        String batchNo = firstNonBlank(batchNoParam, ctx == null ? null : ctx.getString("batchNo"));
        if (lybm == null || lybm.isBlank()) {
            return List.of();
        }
        List<Map<String, Object>> rows = pgQuery.queryForList(
                "SELECT id, lybm, lymc, batch_no, file_name, file_path, file_type, ext, file_size, category, "
                        + "chapter_nos, auto_tagged, data_override, parse_status, content_summary, fail_reason, "
                        + "create_by, create_time, update_time "
                        + "FROM sh_report_material WHERE deleted=0 AND COALESCE(lybm,'')=COALESCE(?,'') "
                        + "AND (batch_no IS NULL OR batch_no=? OR ? IS NULL OR ?='') "
                        + "ORDER BY create_time DESC",
                lybm, batchNo, batchNo, batchNo);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(toPublic(r));
        }
        return out;
    }

    public Map<String, Object> updateTags(Long id, String conversationId, String category,
                                          String chapterNos, Boolean dataOverride) {
        ensureEnabled();
        Map<String, Object> row = requireOwned(id, conversationId);
        pgQuery.update(
                "UPDATE sh_report_material SET category=COALESCE(?,category), chapter_nos=COALESCE(?,chapter_nos), "
                        + "auto_tagged=CASE WHEN ? IS NULL THEN auto_tagged ELSE 0 END, "
                        + "data_override=COALESCE(?,data_override), update_time=NOW() WHERE id=?",
                blankToNull(category), blankToNull(chapterNos), chapterNos,
                dataOverride == null ? null : (dataOverride ? 1 : 0), id);
        return getPublic(id);
    }

    public void delete(Long id, String conversationId) {
        ensureEnabled();
        requireOwned(id, conversationId);
        pgQuery.update("UPDATE sh_report_material SET deleted=1, update_time=NOW() WHERE id=?", id);
    }

    public Map<String, Object> reparse(Long id, String conversationId) {
        ensureEnabled();
        requireOwned(id, conversationId);
        pgQuery.update("UPDATE sh_report_material SET parse_status='pending', fail_reason=NULL, update_time=NOW() WHERE id=?",
                id);
        submitParse(id);
        return getPublic(id);
    }

    public byte[] previewBytes(Long id, String conversationId, String[] fileNameOut, String[] mediaOut) {
        ensureEnabled();
        Map<String, Object> row = requireOwned(id, conversationId);
        String ext = str(row.get("ext")).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("不允许预览该类型");
        }
        String path = str(row.get("file_path"));
        byte[] bytes = readBytes(path);
        if (fileNameOut != null && fileNameOut.length > 0) {
            fileNameOut[0] = str(row.get("file_name"));
        }
        if (mediaOut != null && mediaOut.length > 0) {
            mediaOut[0] = mediaTypeOf(ext);
        }
        return bytes;
    }

    public int countUnready(String lybm, String batchNo) {
        if (!enabled || lybm == null || lybm.isBlank()) {
            return 0;
        }
        Integer n = pgQuery.queryForObject(
                "SELECT COUNT(*) FROM sh_report_material WHERE deleted=0 AND COALESCE(lybm,'')=COALESCE(?,'') "
                        + "AND parse_status IN ('pending','parsing') "
                        + "AND (batch_no IS NULL OR batch_no=? OR ? IS NULL)",
                Integer.class, lybm, batchNo, batchNo);
        return n == null ? 0 : n;
    }

    public boolean hasHistoryMaterials(String lybm, String batchNo) {
        if (!enabled || lybm == null || lybm.isBlank()) {
            return false;
        }
        Integer n = pgQuery.queryForObject(
                "SELECT COUNT(*) FROM sh_report_material WHERE deleted=0 AND parse_status='ready' "
                        + "AND COALESCE(lybm,'')=COALESCE(?,'') "
                        + "AND (batch_no IS NULL OR batch_no=? OR ? IS NULL) "
                        + "AND (category='历史山洪调查' OR COALESCE(chapter_nos,'') LIKE '%5%')",
                Integer.class, lybm, batchNo, batchNo);
        return n != null && n > 0;
    }

    /**
     * 按章选取 ready 资料，写入 materialVerByChapter，返回注入正文（不含使用规则外壳）。空则 null。
     */
    public String buildChapterMaterials(String batchNo, String lybm, int chapterNo, boolean halfBudget) {
        if (!enabled || lybm == null || lybm.isBlank()) {
            materialVerByChapter.put(verKey(batchNo, chapterNo), "");
            return null;
        }
        List<Map<String, Object>> rows = pgQuery.queryForList(
                "SELECT id, file_name, file_type, category, chapter_nos, data_override, batch_no, "
                        + "extracted_text, update_time FROM sh_report_material "
                        + "WHERE deleted=0 AND parse_status='ready' AND COALESCE(lybm,'')=COALESCE(?,'') "
                        + "AND (batch_no IS NULL OR batch_no=?)",
                lybm, batchNo);
        List<Map<String, Object>> hit = new ArrayList<>();
        List<Map<String, Object>> fallback = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (MaterialChapterTagger.coversChapter(str(r.get("chapter_nos")), chapterNo)) {
                hit.add(r);
            } else if ("基础数据".equals(str(r.get("category")))) {
                fallback.add(r);
            }
        }
        List<Map<String, Object>> picked = new ArrayList<>(hit);
        if (picked.isEmpty() && !fallback.isEmpty()) {
            fallback.sort(order());
            picked.add(fallback.get(0));
        }
        picked.sort(order());
        int budget = Math.max(500, halfBudget ? chapterMaxChars / 2 : chapterMaxChars);
        int per = Math.max(200, perFileChars);
        StringBuilder body = new StringBuilder();
        List<String> sources = new ArrayList<>();
        int used = 0;
        boolean fallbackOnly = hit.isEmpty();
        for (Map<String, Object> r : picked) {
            if (used >= budget) {
                break;
            }
            String text = str(r.get("extracted_text"));
            if (text.isBlank()) {
                continue;
            }
            int cap = "excel".equals(str(r.get("file_type"))) ? Math.min(per * 2, 4000) : per;
            if (fallbackOnly) {
                cap = Math.min(cap, budget / 2);
            }
            int remain = budget - used;
            cap = Math.min(cap, remain);
            if (text.length() > cap) {
                text = text.substring(0, cap) + "\n（本文件已截断）";
            }
            String fname = str(r.get("file_name"));
            boolean override = num(r.get("data_override")) == 1;
            body.append("### 来源：").append(fname);
            if (override) {
                body.append("（以此为准）");
            }
            body.append('\n').append(text).append("\n\n");
            used += text.length();
            sources.add(fname + "@" + str(r.get("update_time")));
        }
        materialVerByChapter.put(verKey(batchNo, chapterNo), String.join(";", sources));
        return body.length() == 0 ? null : body.toString().trim();
    }

    public String lastMaterialVer(String batchNo, int chapterNo) {
        return materialVerByChapter.get(verKey(batchNo, chapterNo));
    }

    public String materialRulesBlock() {
        return MATERIAL_RULES;
    }

    private static Comparator<Map<String, Object>> order() {
        return Comparator
                .<Map<String, Object>>comparingInt(r -> num(r.get("data_override")) == 1 ? 0 : 1)
                .thenComparingInt(r -> r.get("batch_no") == null || str(r.get("batch_no")).isBlank() ? 1 : 0)
                .thenComparingInt(r -> "历史山洪调查".equals(str(r.get("category"))) ? 0 : 1)
                .thenComparing((Map<String, Object> r) -> str(r.get("update_time")), Comparator.reverseOrder());
    }

    private void submitParse(Long id) {
        if (id == null || parsePool == null) {
            return;
        }
        CompletableFuture.runAsync(() -> parseOne(id), parsePool);
    }

    void parseOne(Long id) {
        try {
            pgQuery.update("UPDATE sh_report_material SET parse_status='parsing', update_time=NOW() WHERE id=?", id);
            List<Map<String, Object>> rows = pgQuery.queryForList(
                    "SELECT * FROM sh_report_material WHERE id=? AND deleted=0", id);
            if (rows.isEmpty()) {
                return;
            }
            Map<String, Object> row = rows.get(0);
            String ext = str(row.get("ext")).toLowerCase(Locale.ROOT);
            String fileType = str(row.get("file_type"));
            String fileName = str(row.get("file_name"));
            String path = str(row.get("file_path"));
            String userChapter = str(row.get("chapter_nos"));
            String userCategory = str(row.get("category"));
            byte[] bytes = readBytes(path);
            String text;
            if ("excel".equals(fileType)) {
                text = MaterialExcelExtractor.extract(new ByteArrayInputStream(bytes), fileName, excelMaxRows, excelMaxChars);
            } else if ("image".equals(fileType)) {
                text = transcribeImage(bytes, ext, str(row.get("model_id")));
            } else {
                Path tmp = Files.createTempFile("sh-mat-", "." + ext);
                try {
                    Files.write(tmp, bytes);
                    text = MaterialDocExtractor.extract(tmp.toFile(), docMaxChars);
                    if (!MaterialDocExtractor.hasUsableText(text)) {
                        if (MaterialDocExtractor.likelyScan(text, bytes.length)) {
                            fail(id, "疑似扫描件，请上传翻拍图片");
                            return;
                        }
                        if (text == null || text.isBlank()) {
                            fail(id, "未能抽出文字");
                            return;
                        }
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            if (text == null || text.isBlank()) {
                fail(id, "解析结果为空");
                return;
            }
            MaterialChapterTagger.Tag tag = MaterialChapterTagger.tag(fileName, text, fileType);
            String chapterNos = userChapter.isBlank() ? tag.chapterNos : userChapter;
            String category = userCategory.isBlank() ? tag.category : userCategory;
            int auto = userChapter.isBlank() ? 1 : num(row.get("auto_tagged"));
            String summary = text.replaceAll("\\s+", " ");
            if (summary.length() > 200) {
                summary = summary.substring(0, 200);
            }
            pgQuery.update(
                    "UPDATE sh_report_material SET parse_status='ready', extracted_text=?, content_summary=?, "
                            + "chapter_nos=?, category=?, auto_tagged=?, fail_reason=NULL, update_time=NOW() WHERE id=?",
                    text, summary, chapterNos, category, auto, id);
        } catch (Exception e) {
            log.warn("资料解析失败 id={}: {}", id, e.getMessage());
            fail(id, clip(e.getMessage(), 400));
        }
    }

    private String transcribeImage(byte[] bytes, String ext, String sessionModelId) {
        ImageContent img = aiChatHandler.imageContentFromBytes(bytes, mediaTypeOf(ext));
        if (img == null) {
            throw new IllegalArgumentException("图片读取失败");
        }
        String modelId = firstNonBlank(visionModelId, sessionModelId, defaultModelId);
        List<dev.langchain4j.data.message.Content> contents = List.of(TextContent.from(VISION_PROMPT), img);
        List<dev.langchain4j.data.message.ChatMessage> messages = List.of(
                SystemMessage.from("你是山洪调查资料转写助手，只描述图中可见内容。"),
                UserMessage.from(contents));
        try {
            return concurrencyLimiter.withLlmPermit(() -> {
                String resp = (modelId == null || modelId.isBlank())
                        ? aiChatHandler.completionsByDefaultModel(messages, null)
                        : aiChatHandler.completions(modelId, messages, null);
                if (resp == null || resp.isBlank()) {
                    throw new IllegalArgumentException("视觉模型未返回转写结果");
                }
                return resp.trim();
            });
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.toLowerCase(Locale.ROOT).contains("vision")
                    || msg.contains("不支持")
                    || msg.toLowerCase(Locale.ROOT).contains("image")
                    || msg.toLowerCase(Locale.ROOT).contains("multimodal")) {
                throw new IllegalArgumentException("图片未转写：当前模型不支持视觉，请选择视觉模型后点重新解析");
            }
            throw e;
        }
    }

    private void fail(Long id, String reason) {
        pgQuery.update("UPDATE sh_report_material SET parse_status='failed', fail_reason=?, update_time=NOW() WHERE id=?",
                clip(reason, 400), id);
    }

    private Map<String, Object> requireOwned(Long id, String conversationId) {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
        List<Map<String, Object>> rows = pgQuery.queryForList(
                "SELECT * FROM sh_report_material WHERE id=? AND deleted=0", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("资料不存在");
        }
        Map<String, Object> row = rows.get(0);
        if (conversationId != null && !conversationId.isBlank()) {
            JSONObject ctx = sessionStore.get(conversationId);
            String lybm = ctx == null ? null : ctx.getString("lybm");
            if (lybm != null && !lybm.isBlank() && !lybm.equals(str(row.get("lybm")))) {
                throw new IllegalArgumentException("不能操作其它流域的资料");
            }
        }
        return row;
    }

    private Map<String, Object> getPublic(Long id) {
        List<Map<String, Object>> rows = pgQuery.queryForList(
                "SELECT id, lybm, lymc, batch_no, file_name, file_path, file_type, ext, file_size, category, "
                        + "chapter_nos, auto_tagged, data_override, parse_status, content_summary, fail_reason, "
                        + "create_by, create_time, update_time FROM sh_report_material WHERE id=?",
                id);
        return rows.isEmpty() ? Map.of() : toPublic(rows.get(0));
    }

    private Map<String, Object> toPublic(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("lybm", r.get("lybm"));
        m.put("lymc", r.get("lymc"));
        m.put("batchNo", r.get("batch_no"));
        m.put("fileName", r.get("file_name"));
        m.put("fileType", r.get("file_type"));
        m.put("ext", r.get("ext"));
        m.put("fileSize", r.get("file_size"));
        m.put("category", r.get("category"));
        m.put("chapterNos", r.get("chapter_nos"));
        m.put("autoTagged", num(r.get("auto_tagged")));
        m.put("dataOverride", num(r.get("data_override")));
        m.put("parseStatus", r.get("parse_status"));
        m.put("contentSummary", r.get("content_summary"));
        m.put("failReason", r.get("fail_reason"));
        m.put("createBy", r.get("create_by"));
        m.put("createTime", r.get("create_time"));
        m.put("updateTime", r.get("update_time"));
        return m;
    }

    private byte[] readBytes(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("文件路径为空");
        }
        String p = filePath.replace('\\', '/');
        if (p.startsWith("http://") || p.startsWith("https://")) {
            SsrfFileTypeFilter.checkSsrfHttpUrl(p);
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(p).toURL().openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                try (InputStream in = conn.getInputStream()) {
                    return in.readAllBytes();
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("读取对象存储文件失败：" + e.getMessage());
            }
        }
        if (p.contains("..")) {
            throw new IllegalArgumentException("非法文件路径");
        }
        if (!p.contains("shreport/material/")) {
            throw new IllegalArgumentException("文件不在资料目录内");
        }
        Path root = Paths.get(uploadPath).toAbsolutePath().normalize();
        Path file = root.resolve(p).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        SsrfFileTypeFilter.checkPathTraversal(file.toString());
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new IllegalArgumentException("读取文件失败：" + e.getMessage());
        }
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new IllegalArgumentException("流域资料功能未开启");
        }
    }

    private static String fileTypeOf(String ext) {
        if ("xlsx".equals(ext) || "xls".equals(ext)) {
            return "excel";
        }
        if ("jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext) || "webp".equals(ext) || "bmp".equals(ext)) {
            return "image";
        }
        return "document";
    }

    static String mediaTypeOf(String ext) {
        return switch (ext == null ? "" : ext.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG_VALUE;
            case "png" -> MediaType.IMAGE_PNG_VALUE;
            case "gif" -> MediaType.IMAGE_GIF_VALUE;
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "pdf" -> MediaType.APPLICATION_PDF_VALUE;
            case "txt", "md" -> MediaType.TEXT_PLAIN_VALUE;
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    private static String currentUser() {
        try {
            return JwtUtil.getUserNameByToken(SpringContextUtils.getHttpServletRequest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String verKey(String batchNo, int chapterNo) {
        return (batchNo == null ? "" : batchNo) + "|" + chapterNo;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int num(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 测试用：解析池未就绪时直接跑 */
    ExecutorService parsePool() {
        return parsePool;
    }
}
