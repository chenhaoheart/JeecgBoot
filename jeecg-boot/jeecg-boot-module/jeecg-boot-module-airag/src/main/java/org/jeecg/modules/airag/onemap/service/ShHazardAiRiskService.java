package org.jeecg.modules.airag.onemap.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.airag.app.entity.AiragApp;
import org.jeecg.modules.airag.app.mapper.AiragAppMapper;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.handler.AIChatHandler;
import org.jeecg.modules.airag.llm.mapper.AiragModelMapper;
import org.jeecg.modules.airag.prompts.entity.AiragPrompts;
import org.jeecg.modules.airag.prompts.mapper.AiragPromptsMapper;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 一张图隐患点详情：用现场照片和外业备注做一次风险识别。
 * 结果不回写调查表，但每次操作写入识别台账，供事后查阅。
 */
@Slf4j
@Service
public class ShHazardAiRiskService {

    /** 与图像识别独立页绑定的 AI 应用一致，优先用它配置的视觉模型 */
    private static final String IMGREC_APP_ID = "1996471445272088578";
    /** 单次最多送入模型的照片张数，控制耗时和费用 */
    private static final int MAX_PHOTOS = 4;
    private static final int COMMENT_LIMIT = 8000;
    /**
     * 系统提示词在 airag_prompts 的这条记录里。
     * 后台「AI提示词」改 content 后，下一次识别即用新文案，不用发版。
     */
    static final String SYSTEM_PROMPT_ID = "1990000000000000020";

    /** 提示词库没有这条记录、或正文为空时使用 */
    static final String DEFAULT_SYSTEM_PROMPT = """
            你是山洪灾害风险隐患调查分析人员。根据隐患点基础信息、外业调查备注和现场照片，识别风险隐患。
            用简体中文分条写出，不要用表格，不要输出 JSON：
            1. 主要风险：壅水、溃决、漫溢改道、阻水、沟道束窄、岸坡失稳等。每条写明依据来自备注的哪一句，或照片里看到了什么。
            2. 照片可见情况：桥涵净空、淤积、侵占、破损、上下游差异。看不清的不要写成事实。
            3. 备注与照片不一致，或备注没写但照片能看出的问题，单独列出。
            4. 建议现场复核的 2 到 4 个问题。
            5. 阻水面积比估算：调查表只有填报百分数，没有桥涵净宽、净高。不要用沟宽乘沟深去反推，也不要因为没有尺子读数就回答无法估算。
            看照片里过水孔、桥墩、梁底、涵洞、路堤挡住了沟槽断面的多少：孔洞大、墩少、梁底高，阻水偏低；孔洞小、涵洞埋在路基下、路堤封沟，阻水偏高。
            必须写出按照片估算的百分数或区间（例如约 30%，或 25%–40%），并和填报值对照，说明偏高、偏低还是接近，以及照片里哪一处支持这个判断。把握不高时仍给区间，并写明把握不高。
            只有所有照片都没拍到桥涵或涵洞本身时，才写“照片未拍到过水断面”，此时不要编造百分数。没有照片时不要估，只复述填报值。
            信息不足时直接说明缺什么，不要编造对象名称或照片里没有的内容。
            """;

    /** 主回答没给出照片估算时，用同一批照片再问一次，只盯阻水面积比 */
    static final String RATIO_RETRY_PROMPT = """
            你只根据所附现场照片估算桥涵或路涵的阻水面积比。
            没有实测净宽、净高，不要说缺少尺寸所以无法估算，也不要用沟宽乘沟深去算。
            看过水孔、桥墩、梁底、涵洞、路堤挡住沟槽断面的比例，必须给出百分数或区间（例如约 30%，或 25%–40%）。
            下文若有填报值，只用一句话对照偏高、偏低或接近，填报值不是你的估算。
            只有照片完全没拍到桥涵或涵洞时，才写“照片未拍到过水断面”，不要编造百分数。
            用简体中文，三到五句，不要表格，不要 JSON。
            """;

    private static final Pattern RATIO_ESTIMATE = Pattern.compile(
            "(?:估算|粗估|大约|约为|约)\\s*\\d{1,3}(?:\\.\\d+)?\\s*[%％]");
    private static final Pattern RATIO_RANGE = Pattern.compile(
            "\\d{1,3}(?:\\.\\d+)?\\s*[%％]?\\s*[~～\\-—–至到]\\s*\\d{1,3}(?:\\.\\d+)?\\s*[%％]");

    /** 送入模型的调查字段（备注放最后） */
    private static final Map<String, String> SURVEY_LABELS = new LinkedHashMap<>();

    static {
        SURVEY_LABELS.put("name", "名称");
        SURVEY_LABELS.put("stype", "类型");
        SURVEY_LABELS.put("channelw", "沟道宽度(m)");
        SURVEY_LABELS.put("channeld", "沟道深度(m)");
        SURVEY_LABELS.put("csshpt", "坝顶高程(m)");
        SURVEY_LABELS.put("obstruction_area_ratio", "阻水面积比");
        SURVEY_LABELS.put("obstruction_storage_capacity", "阻水库容(万m³)");
        SURVEY_LABELS.put("backwater_objname", "壅水影响对象");
        SURVEY_LABELS.put("burst_objname", "溃决影响对象");
        SURVEY_LABELS.put("overflow_objname", "漫溢影响对象");
        SURVEY_LABELS.put("width", "宽度(m)");
        SURVEY_LABELS.put("deep", "深度(m)");
        SURVEY_LABELS.put("dmform", "占地形态");
        SURVEY_LABELS.put("zsmj", "占地面积");
        SURVEY_LABELS.put("survey_year", "调查年份");
        SURVEY_LABELS.put("comments", "备注");
    }

    @Autowired
    private ShReportMapService mapService;
    @Autowired
    private ShFileObjectService fileService;
    @Autowired
    private AIChatHandler aiChatHandler;
    @Autowired
    private AiragAppMapper airagAppMapper;
    @Autowired
    private AiragModelMapper airagModelMapper;
    @Autowired
    private AiragPromptsMapper airagPromptsMapper;
    @Autowired
    private ShReportPgQueryService pgQueryService;

    private volatile boolean logTableReady;

    public Map<String, Object> analyze(String hazardId) {
        long started = System.nanoTime();
        Ledger ledger = new Ledger();
        ledger.hazardId = hazardId == null ? "" : hazardId.trim();
        fillOperator(ledger);
        try {
            if (ledger.hazardId.isEmpty()) {
                throw new IllegalArgumentException("隐患点不存在");
            }
            Map<String, Object> hazard = mapService.getHazard(ledger.hazardId);
            if (hazard == null) {
                throw new IllegalArgumentException("隐患要素不存在");
            }
            ledger.pid = str(hazard.get("pid"));
            ledger.hazardName = str(hazard.get("name"));
            ledger.hzType = str(hazard.get("hz_type"));
            ledger.rvname = str(hazard.get("rvname"));
            ledger.fnm = str(hazard.get("fnm"));
            ledger.wsnm = str(hazard.get("wsnm"));

            List<PhotoRef> photos = collectPhotos(ledger.pid, str(hazard.get("photos")));
            String packet = buildPacket(hazard, photos);
            boolean usedComment = packet.contains("备注：");
            ledger.inputSummary = packet;
            ledger.photoTotal = photos.size();
            ledger.photoLabels = photos.stream().map(PhotoRef::label).toList();
            ledger.photoRefs = photoRefsJson(photos);
            ledger.usedComment = usedComment;
            if (photos.isEmpty() && !usedComment) {
                throw new IllegalArgumentException("该隐患点没有现场照片，外业备注也为空，无法识别");
            }

            String modelId = visionModelId();
            String modelName = modelName(modelId);
            ledger.modelName = modelName;
            boolean degraded = false;
            String text;
            try {
                text = complete(modelId, packet, photos);
            } catch (RuntimeException e) {
                if (photos.isEmpty()) {
                    throw e;
                }
                log.warn("隐患点带图识别失败，改为仅根据文字 id={}: {}", hazardId, e.getMessage());
                degraded = true;
                text = "（现场照片未能送入模型，以下仅根据调查文字判断）\n\n" + complete(modelId, packet, List.of());
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("模型未返回识别结果，请稍后重试");
            }
            if (!degraded && !photos.isEmpty() && needsRatioRetry(text)) {
                text = appendRatioRetry(modelId, hazard, photos, text);
            }
            ledger.degraded = degraded;
            ledger.photoCount = degraded ? 0 : photos.size();
            ledger.resultText = text.trim();
            ledger.status = "success";

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", ledger.resultText);
            out.put("photoCount", ledger.photoCount);
            out.put("photoTotal", ledger.photoTotal);
            out.put("photoLabels", ledger.photoLabels);
            out.put("usedComment", usedComment);
            out.put("modelName", modelName);
            out.put("degraded", degraded);
            out.put("logId", saveLedger(ledger, started));
            return out;
        } catch (RuntimeException e) {
            ledger.status = "fail";
            ledger.errorMessage = clip(e.getMessage(), 1000);
            saveLedger(ledger, started);
            throw e;
        }
    }

    /** 识别台账分页。列表不带回全文，点开详情再取。 */
    public Map<String, Object> pageLogs(int pageNo, int pageSize, String search, String status) {
        ensureLogTable();
        int pn = Math.max(pageNo, 1);
        int ps = Math.min(Math.max(pageSize, 1), 100);
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (status != null && ("success".equals(status) || "fail".equals(status))) {
            where.append(" AND status = ? ");
            args.add(status);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (hazard_name ILIKE ? OR pid ILIKE ? OR realname ILIKE ? OR username ILIKE ? OR wsnm ILIKE ?) ");
            String q = "%" + search.trim() + "%";
            args.add(q);
            args.add(q);
            args.add(q);
            args.add(q);
            args.add(q);
        }
        Integer total = pgQueryService.queryForObject(
                "SELECT COUNT(*) FROM sh_hazard_ai_risk_log" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(ps);
        pageArgs.add((pn - 1) * ps);
        List<Map<String, Object>> data = pgQueryService.queryForList(
                "SELECT id, hazard_id, pid, hazard_name, hz_type, rvname, fnm, wsnm, username, realname, "
                        + "model_name, photo_count, photo_total, used_comment, degraded, status, error_message, cost_ms, "
                        + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time "
                        + "FROM sh_hazard_ai_risk_log" + where
                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                pageArgs.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("pageNo", pn);
        out.put("pageSize", ps);
        out.put("data", data);
        return out;
    }

    public Map<String, Object> getLog(long id) {
        ensureLogTable();
        List<Map<String, Object>> rows = pgQueryService.queryForList(
                "SELECT id, hazard_id, pid, hazard_name, hz_type, rvname, fnm, wsnm, username, realname, "
                        + "model_name, photo_count, photo_total, photo_labels, photo_refs, used_comment, degraded, status, "
                        + "error_message, input_summary, result_text, cost_ms, "
                        + "to_char(create_time, 'YYYY-MM-DD HH24:MI:SS') AS create_time "
                        + "FROM sh_hazard_ai_risk_log WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initLogTable() {
        try {
            ensureLogTable();
        } catch (Exception e) {
            log.warn("识别台账建表失败，首次写入时会再试: {}", e.getMessage());
        }
    }

    private Long saveLedger(Ledger ledger, long startedNanos) {
        ledger.costMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000L);
        try {
            ensureLogTable();
            return pgQueryService.queryForObject(
                    "INSERT INTO sh_hazard_ai_risk_log (hazard_id, pid, hazard_name, hz_type, rvname, fnm, wsnm, "
                            + "user_id, username, realname, model_name, photo_count, photo_total, photo_labels, photo_refs, "
                            + "used_comment, degraded, status, error_message, input_summary, result_text, cost_ms) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    emptyToNull(ledger.hazardId), emptyToNull(ledger.pid), emptyToNull(ledger.hazardName),
                    emptyToNull(ledger.hzType), emptyToNull(ledger.rvname), emptyToNull(ledger.fnm), emptyToNull(ledger.wsnm),
                    emptyToNull(ledger.userId), emptyToNull(ledger.username), emptyToNull(ledger.realname),
                    emptyToNull(ledger.modelName), ledger.photoCount, ledger.photoTotal,
                    emptyToNull(String.join("\n", ledger.photoLabels)), emptyToNull(ledger.photoRefs),
                    ledger.usedComment ? 1 : 0, ledger.degraded ? 1 : 0,
                    ledger.status == null ? "fail" : ledger.status,
                    emptyToNull(ledger.errorMessage), emptyToNull(ledger.inputSummary), emptyToNull(ledger.resultText),
                    ledger.costMs);
        } catch (Exception e) {
            log.warn("写入识别台账失败: {}", e.getMessage());
            return null;
        }
    }

    private void ensureLogTable() {
        if (logTableReady) {
            return;
        }
        synchronized (this) {
            if (logTableReady) {
                return;
            }
            pgQueryService.execute("""
                    CREATE TABLE IF NOT EXISTS sh_hazard_ai_risk_log (
                        id BIGSERIAL PRIMARY KEY,
                        hazard_id VARCHAR(64),
                        pid VARCHAR(128),
                        hazard_name VARCHAR(255),
                        hz_type VARCHAR(64),
                        rvname VARCHAR(255),
                        fnm VARCHAR(255),
                        wsnm VARCHAR(255),
                        user_id VARCHAR(64),
                        username VARCHAR(64),
                        realname VARCHAR(64),
                        model_name VARCHAR(255),
                        photo_count INT,
                        photo_total INT,
                        photo_labels TEXT,
                        used_comment SMALLINT,
                        degraded SMALLINT,
                        status VARCHAR(16),
                        error_message VARCHAR(1000),
                        input_summary TEXT,
                        result_text TEXT,
                        cost_ms INT,
                        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )""");
            pgQueryService.execute(
                    "CREATE INDEX IF NOT EXISTS idx_sh_hazard_ai_risk_log_time ON sh_hazard_ai_risk_log (create_time DESC)");
            pgQueryService.execute(
                    "ALTER TABLE sh_hazard_ai_risk_log ADD COLUMN IF NOT EXISTS photo_refs TEXT");
            logTableReady = true;
        }
    }

    private static void fillOperator(Ledger ledger) {
        try {
            Object principal = SecurityUtils.getSubject().getPrincipal();
            if (principal instanceof LoginUser user) {
                ledger.userId = user.getId();
                ledger.username = user.getUsername();
                ledger.realname = user.getRealname();
            }
        } catch (Exception ignored) {
            // 无登录上下文时仍保留识别结果，操作人留空
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static final class Ledger {
        String hazardId;
        String pid;
        String hazardName;
        String hzType;
        String rvname;
        String fnm;
        String wsnm;
        String userId;
        String username;
        String realname;
        String modelName;
        int photoCount;
        int photoTotal;
        List<String> photoLabels = List.of();
        String photoRefs;
        boolean usedComment;
        boolean degraded;
        String status;
        String errorMessage;
        String inputSummary;
        String resultText;
        int costMs;
    }

    /** 主回答里没有“估算/约 xx%”或区间时，再单独问一次照片估算 */
    static boolean needsRatioRetry(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return !RATIO_ESTIMATE.matcher(text).find() && !RATIO_RANGE.matcher(text).find();
    }

    private String appendRatioRetry(String modelId, Map<String, Object> hazard, List<PhotoRef> photos, String text) {
        try {
            String follow = complete(modelId, RATIO_RETRY_PROMPT, ratioFollowupPacket(hazard, photos), photos);
            if (follow == null || follow.isBlank()) {
                return text;
            }
            return text.trim() + "\n\n## 阻水面积比（照片估算）\n" + follow.trim();
        } catch (RuntimeException e) {
            log.warn("阻水面积比补问失败，保留首次识别结果: {}", e.getMessage());
            return text;
        }
    }

    private static String ratioFollowupPacket(Map<String, Object> hazard, List<PhotoRef> photos) {
        StringBuilder sb = new StringBuilder();
        sb.append("请只根据随后的现场照片估算阻水面积比。\n");
        Object surveyObj = hazard.get("survey");
        if (surveyObj instanceof Map<?, ?> survey) {
            line(sb, "填报阻水面积比（仅供对照）", survey.get("obstruction_area_ratio"));
            line(sb, "沟宽m（仅作规模参照）", survey.get("channelw"));
            line(sb, "沟深m（仅作规模参照）", survey.get("channeld"));
        }
        sb.append("照片顺序与随后图片一致：\n");
        for (int i = 0; i < photos.size(); i++) {
            sb.append(i + 1).append(". ").append(photos.get(i).label()).append('\n');
        }
        return sb.toString();
    }

    /** 库里的正文优先。空白视为未配置，退回内置文案。 */
    static String resolvePrompt(String stored) {
        if (stored == null || stored.isBlank()) {
            return DEFAULT_SYSTEM_PROMPT;
        }
        return stored.trim();
    }

    /**
     * 每次识别现读提示词库，避免改口径还要发版。
     * 记录不存在、正文为空或查询失败时，退回内置文案，识别仍可进行。
     */
    private String systemPrompt() {
        try {
            AiragPrompts row = airagPromptsMapper.selectById(SYSTEM_PROMPT_ID);
            String stored = row == null ? null : row.getContent();
            if (stored != null && !stored.isBlank()) {
                return resolvePrompt(stored);
            }
            log.debug("隐患识别提示词未配置或为空，使用内置文案 id={}", SYSTEM_PROMPT_ID);
        } catch (Exception e) {
            log.warn("读取隐患识别提示词失败，改用内置文案: {}", e.getMessage());
        }
        return DEFAULT_SYSTEM_PROMPT;
    }

    private String complete(String modelId, String packet, List<PhotoRef> photos) {
        return complete(modelId, systemPrompt(), packet, photos);
    }

    private String complete(String modelId, String system, String packet, List<PhotoRef> photos) {
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(packet));
        for (PhotoRef photo : photos) {
            if (photo.content() != null) {
                contents.add(photo.content());
            }
        }
        List<ChatMessage> messages = List.of(SystemMessage.from(system), UserMessage.from(contents));
        String resp = (modelId == null || modelId.isBlank())
                ? aiChatHandler.completionsByDefaultModel(messages, null)
                : aiChatHandler.completions(modelId, messages, null);
        return resp == null ? "" : resp;
    }

    private String buildPacket(Map<String, Object> hazard, List<PhotoRef> photos) {
        StringBuilder sb = new StringBuilder();
        sb.append("【隐患点】\n");
        line(sb, "名称", hazard.get("name"));
        line(sb, "编码", hazard.get("pid"));
        line(sb, "隐患类型", hazard.get("hz_type"));
        line(sb, "河流", hazard.get("rvname"));
        line(sb, "防护对象", hazard.get("fnm"));
        line(sb, "小流域", hazard.get("wsnm"));

        Object surveyObj = hazard.get("survey");
        if (surveyObj instanceof Map<?, ?> survey) {
            sb.append("\n【外业调查】\n");
            for (Map.Entry<String, String> e : SURVEY_LABELS.entrySet()) {
                if ("comments".equals(e.getKey())) {
                    continue;
                }
                line(sb, e.getValue(), survey.get(e.getKey()));
            }
            String comments = str(survey.get("comments"));
            if (!comments.isEmpty()) {
                if (comments.length() > COMMENT_LIMIT) {
                    comments = comments.substring(0, COMMENT_LIMIT) + "\n（备注已截断）";
                }
                sb.append("备注：\n").append(comments).append('\n');
            }
        }

        sb.append("\n【现场照片】共 ").append(photos.size()).append(" 张，顺序与随后图片一致\n");
        if (photos.isEmpty()) {
            sb.append("无现场照片，请只根据上面的文字判断。\n");
        } else {
            for (int i = 0; i < photos.size(); i++) {
                sb.append(i + 1).append(". ").append(photos.get(i).label()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 磁盘索引照片优先（上游、下游各至少一张），不足时再补人工上传的照片 */
    private List<PhotoRef> collectPhotos(String pid, String manualPhotos) {
        List<PhotoRef> out = new ArrayList<>();
        List<Map<String, Object>> disk = new ArrayList<>();
        if (!pid.isEmpty()) {
            for (Map<String, Object> row : fileService.listByPid(pid)) {
                if (embeddable(row)) {
                    disk.add(row);
                }
            }
        }
        disk.sort((a, b) -> {
            int c = Integer.compare(positionRank(a.get("file_position")), positionRank(b.get("file_position")));
            if (c != 0) {
                return c;
            }
            return str(a.get("file_name")).compareTo(str(b.get("file_name")));
        });
        List<Map<String, Object>> picked = new ArrayList<>();
        takeFirstPosition(disk, picked, "上");
        takeFirstPosition(disk, picked, "下");
        for (Map<String, Object> row : disk) {
            if (picked.size() >= MAX_PHOTOS) {
                break;
            }
            if (!picked.contains(row)) {
                picked.add(row);
            }
        }
        for (Map<String, Object> row : picked) {
            if (out.size() >= MAX_PHOTOS) {
                break;
            }
            PhotoRef ref = fromDisk(row);
            if (ref != null) {
                out.add(ref);
            }
        }
        if (out.size() < MAX_PHOTOS && !manualPhotos.isEmpty()) {
            for (String path : manualPhotos.split(",")) {
                if (out.size() >= MAX_PHOTOS) {
                    break;
                }
                String p = path.trim();
                if (p.isEmpty()) {
                    continue;
                }
                PhotoRef ref = fromUpload(p);
                if (ref != null) {
                    out.add(ref);
                }
            }
        }
        return out;
    }

    private PhotoRef fromDisk(Map<String, Object> row) {
        String rel = str(row.get("relative_path"));
        try {
            Path path = fileService.resolveForPreview(rel);
            byte[] bytes = Files.readAllBytes(path);
            ImageContent content = aiChatHandler.imageContentFromBytes(bytes, fileService.mediaTypeOf(path));
            if (content == null) {
                return null;
            }
            String pos = positionLabel(row.get("file_position"));
            String name = str(row.get("file_name"));
            String label = pos.isEmpty() ? name : pos + " / " + name;
            return new PhotoRef(label, "disk", rel, content);
        } catch (Exception e) {
            log.warn("读取现场照片失败 {}: {}", rel, e.getMessage());
            return null;
        }
    }

    private PhotoRef fromUpload(String path) {
        try {
            List<ImageContent> contents = aiChatHandler.buildImageContents(List.of(path));
            if (contents.isEmpty()) {
                return null;
            }
            String name = path.replace('\\', '/');
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                name = name.substring(slash + 1);
            }
            return new PhotoRef("上传照片 / " + name, "upload", path, contents.get(0));
        } catch (Exception e) {
            log.warn("读取上传照片失败 {}: {}", path, e.getMessage());
            return null;
        }
    }

    private String visionModelId() {
        try {
            AiragApp app = airagAppMapper.getByIdIgnoreTenant(IMGREC_APP_ID);
            if (app != null && app.getModelId() != null && !app.getModelId().isBlank()) {
                return app.getModelId();
            }
        } catch (Exception e) {
            log.warn("读取图像识别应用模型失败: {}", e.getMessage());
        }
        AiragModel fallback = aiChatHandler.getDefaultChatModel();
        return fallback == null ? null : fallback.getId();
    }

    private String modelName(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "默认模型";
        }
        try {
            AiragModel model = airagModelMapper.getByIdIgnoreTenant(modelId);
            if (model != null && model.getName() != null && !model.getName().isBlank()) {
                return model.getName();
            }
        } catch (Exception e) {
            log.warn("读取模型名称失败: {}", e.getMessage());
        }
        return modelId;
    }

    private static void takeFirstPosition(List<Map<String, Object>> rows, List<Map<String, Object>> picked, String position) {
        if (picked.size() >= MAX_PHOTOS) {
            return;
        }
        for (Map<String, Object> row : rows) {
            if (position.equals(str(row.get("file_position")))) {
                picked.add(row);
                return;
            }
        }
    }

    private static boolean embeddable(Map<String, Object> row) {
        if ("video".equalsIgnoreCase(str(row.get("file_type")))) {
            return false;
        }
        String ext = str(row.get("ext")).toLowerCase(Locale.ROOT);
        if (ext.isEmpty()) {
            String name = str(row.get("file_name"));
            int dot = name.lastIndexOf('.');
            ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext) || "gif".equals(ext) || "bmp".equals(ext);
    }

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

    private static String positionLabel(Object position) {
        String p = str(position);
        if ("上".equals(p)) {
            return "上游";
        }
        if ("下".equals(p)) {
            return "下游";
        }
        return "";
    }

    private static void line(StringBuilder sb, String label, Object value) {
        String v = str(value);
        if (!v.isEmpty()) {
            sb.append(label).append("：").append(v).append('\n');
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String photoRefsJson(List<PhotoRef> photos) {
        JSONArray arr = new JSONArray();
        for (PhotoRef photo : photos) {
            if (photo.path() == null || photo.path().isBlank()) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("label", photo.label());
            item.put("kind", photo.kind());
            item.put("path", photo.path());
            arr.add(item);
        }
        return arr.isEmpty() ? null : arr.toJSONString();
    }

    private record PhotoRef(String label, String kind, String path, ImageContent content) {
    }
}
