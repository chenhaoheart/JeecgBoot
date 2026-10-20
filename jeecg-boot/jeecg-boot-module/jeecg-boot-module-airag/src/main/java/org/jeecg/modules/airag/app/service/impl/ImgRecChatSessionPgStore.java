package org.jeecg.modules.airag.app.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.dynamic.datasource.annotation.DS;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「图像识别」独立页（sessionType=imgrec）会话的 PostgreSQL 持久化。
 * 设计参考 service.ShReportAgentSessionDao（山洪报告助手会话按 用户+会话ID 入库、幂等建表、消息JSON存储），
 * 但落库到一张图的 PostgreSQL 数据源（spring.datasource.dynamic.datasource.shreport，shreport_map 库）。
 * <p>
 * Redis 仍是会话的一级存储（读路径全部走 Redis）；本类只在保存时异步双写、
 * 在 Redis 丢失（重启/被清）时提供回灌与列表兜底，任何 PG 异常都不影响聊天主流程。
 * <p>
 * 注意：@DS 基于代理生效，同类内部直调不会切数据源，因此所有落库方法都通过
 * ApplicationContext 取代理 Bean 后调用（事件回调与异步线程同样如此）。
 */
@Slf4j
@Component
public class ImgRecChatSessionPgStore {

    public static final String SESSION_TYPE_IMGREC = "imgrec";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    /** 与 /airag/chat/upload 相同的本地存储根目录（jeecg.path.upload） */
    @Value("${jeecg.path.upload}")
    private String uploadpath;

    /** 单线程串行写入：保证同一会话的消息按发生顺序落库，且不阻塞聊天主流程 */
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "imgrec-session-pg-persist");
        t.setDaemon(true);
        return t;
    });

    @EventListener(ApplicationReadyEvent.class)
    public void initTable() {
        try {
            // 事件回调不走代理，必须经代理调用才能切到 shreport 数据源
            applicationContext.getBean(ImgRecChatSessionPgStore.class).createTableIfNotExists();
        } catch (Exception e) {
            log.error("[imgrec]创建会话持久化表失败（PG不可用时聊天仍可正常使用，仅无持久化）", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        persistExecutor.shutdown();
    }

    /**
     * 异步持久化整个会话（在调用线程完成序列化快照，落库在单线程池执行）。
     * <p>
     * base64 图片不落 PG：落库前把历史消息里的 base64 解码保存为文件
     * （{jeecg.path.upload}/airag/imgrec/），历史中改存可访问的绝对 URL。
     * 否则一张 10M 的照片会让会话 JSON 膨胀到 13M+，且每次保存整份重写。
     * PG 异常只记日志，不影响聊天。
     */
    public void persistAsync(String userId, String conversationId, String title, String appId,
                             String conversationJson, String baseUrl) {
        if (userId == null || userId.isEmpty() || conversationId == null || conversationId.isEmpty()) {
            return;
        }
        final long updateTime = System.currentTimeMillis();
        persistExecutor.execute(() -> {
            try {
                String slimJson = slimImages(conversationJson, baseUrl);
                // 异步线程上同样要经代理调用 @DS 方法
                applicationContext.getBean(ImgRecChatSessionPgStore.class)
                        .upsert(userId, conversationId, title, appId, slimJson, updateTime);
            } catch (Exception e) {
                log.error("[imgrec]会话持久化到PG失败: conversationId={}", conversationId, e);
            }
        });
    }

    @DS("shreport")
    public void createTableIfNotExists() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS imgrec_chat_session ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "user_id VARCHAR(64) NOT NULL, "
                + "conversation_id VARCHAR(64) NOT NULL, "
                + "title VARCHAR(500), "
                + "app_id VARCHAR(64), "
                + "conversation JSONB NOT NULL, "
                + "update_time BIGINT NOT NULL DEFAULT 0, "
                + "create_time TIMESTAMPTZ NOT NULL DEFAULT now(), "
                + "CONSTRAINT uk_imgrec_chat_session UNIQUE (user_id, conversation_id))");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_imgrec_chat_session_user "
                + "ON imgrec_chat_session (user_id, update_time DESC)");
    }

    /** 会话摘要列表（按最近更新倒序），供 Redis 丢失时兜底 */
    @DS("shreport")
    public List<Map<String, Object>> listSummaries(String userId) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT conversation_id, title, "
                            + "(EXTRACT(EPOCH FROM create_time) * 1000)::bigint AS create_ms, update_time "
                            + "FROM imgrec_chat_session WHERE user_id=? ORDER BY update_time DESC", userId);
        } catch (Exception e) {
            log.error("[imgrec]查询PG会话列表失败", e);
            return new ArrayList<>();
        }
    }

    /** 读取完整会话JSON，Redis 丢失时用于回灌 */
    @DS("shreport")
    public String loadConversation(String userId, String conversationId) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT conversation FROM imgrec_chat_session WHERE user_id=? AND conversation_id=?",
                    userId, conversationId);
            if (rows.isEmpty() || rows.get(0).get("conversation") == null) {
                return null;
            }
            return String.valueOf(rows.get(0).get("conversation"));
        } catch (Exception e) {
            log.error("[imgrec]从PG读取会话失败: conversationId={}", conversationId, e);
            return null;
        }
    }

    @DS("shreport")
    public void delete(String userId, String conversationId) {
        try {
            jdbcTemplate.update("DELETE FROM imgrec_chat_session WHERE user_id=? AND conversation_id=?",
                    userId, conversationId);
        } catch (Exception e) {
            log.error("[imgrec]删除PG会话失败: conversationId={}", conversationId, e);
        }
    }

    @DS("shreport")
    public void upsert(String userId, String conversationId, String title, String appId,
                       String conversationJson, long updateTime) {
        jdbcTemplate.update(
                "INSERT INTO imgrec_chat_session (user_id, conversation_id, title, app_id, conversation, update_time) "
                        + "VALUES (?,?,?,?,?::jsonb,?) "
                        + "ON CONFLICT (user_id, conversation_id) DO UPDATE SET "
                        + "title=EXCLUDED.title, app_id=EXCLUDED.app_id, "
                        + "conversation=EXCLUDED.conversation, update_time=EXCLUDED.update_time",
                userId, conversationId, title, appId, conversationJson, updateTime);
    }

    /**
     * 把会话JSON里消息图片的 base64 替换为文件URL（返回瘦身后的JSON）。
     * 已有 url 的只删 base64；没有 url 且拿不到 baseUrl 时保留 base64 原样（宁可变大也不丢图）。
     */
    private String slimImages(String json, String baseUrl) {
        if (json == null || !json.contains("base64Data")) {
            return json;
        }
        JSONObject root = JSON.parseObject(json);
        JSONArray messages = root.getJSONArray("messages");
        if (messages == null) {
            return json;
        }
        for (int i = 0; i < messages.size(); i++) {
            JSONObject message = messages.getJSONObject(i);
            if (message == null) {
                continue;
            }
            JSONArray images = message.getJSONArray("images");
            if (images == null) {
                continue;
            }
            for (int j = 0; j < images.size(); j++) {
                JSONObject image = images.getJSONObject(j);
                if (image == null) {
                    continue;
                }
                String base64Data = image.getString("base64Data");
                if (base64Data == null || base64Data.isEmpty()) {
                    continue;
                }
                String url = image.getString("url");
                if (url == null || url.isEmpty()) {
                    if (baseUrl == null) {
                        continue;
                    }
                    String relative = saveBase64AsFile(base64Data, image.getString("mimeType"));
                    if (relative != null) {
                        image.put("url", baseUrl + "/sys/common/static/" + relative);
                    }
                }
                image.remove("base64Data");
            }
        }
        return root.toJSONString();
    }

    /**
     * base64 解码保存为文件，返回相对于静态访问根目录的路径（airag/imgrec/xxx.jpg）。
     * 失败返回 null（调用方保留 base64 原样）。
     */
    private String saveBase64AsFile(String base64Data, String mimeType) {
        try {
            String data = base64Data;
            if (data.startsWith("data:")) {
                int comma = data.indexOf(',');
                if (comma > 0) {
                    if (mimeType == null || mimeType.isEmpty()) {
                        // data:image/png;base64 → image/png
                        String head = data.substring(5, comma);
                        int semi = head.indexOf(';');
                        mimeType = semi > 0 ? head.substring(0, semi) : head;
                    }
                    data = data.substring(comma + 1);
                }
            }
            byte[] bytes = Base64.getDecoder().decode(data);
            String ext = extFromMimeType(mimeType);
            String filename = UUID.randomUUID().toString().replace("-", "") + ext;
            Path dir = Paths.get(uploadpath, "airag", "imgrec");
            Files.createDirectories(dir);
            Files.write(dir.resolve(filename), bytes);
            return "airag/imgrec/" + filename;
        } catch (Exception e) {
            log.error("[imgrec]base64图片转存文件失败", e);
            return null;
        }
    }

    private static String extFromMimeType(String mimeType) {
        if (mimeType == null) {
            return ".jpg";
        }
        switch (mimeType) {
            case "image/png": return ".png";
            case "image/gif": return ".gif";
            case "image/webp": return ".webp";
            case "image/bmp": return ".bmp";
            default: return ".jpg";
        }
    }
}
