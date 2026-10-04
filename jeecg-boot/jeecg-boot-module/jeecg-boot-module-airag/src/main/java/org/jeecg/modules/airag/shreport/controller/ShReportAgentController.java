package org.jeecg.modules.airag.shreport.controller;

import com.alibaba.fastjson.JSONObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.modules.airag.shreport.ShReportAgentSessionDao;
import org.jeecg.modules.airag.shreport.service.ShReportModelVariantService;
import org.jeecg.modules.airag.shreport.ShReportSessionStore;
import org.jeecg.modules.airag.shreport.service.ShReportAgentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 山洪报告 AI 助手：对话式驱动报告生成。
 * 前端 SSE 事件：text / refresh / progress / choose / watershed / download / error / done
 */
@Slf4j
@Tag(name = "山洪报告-AI助手")
@RestController
@RequestMapping("/sh/report/agent")
public class ShReportAgentController {

    @Autowired
    private ShReportAgentService agentService;

    @Autowired
    private ShReportSessionStore sessionStore;

    @Autowired
    private ShReportModelVariantService modelVariantService;

    @Autowired
    private ShReportAgentSessionDao agentSessionDao;

    /** 会话列表持久化的用户维度：取登录用户名，取不到降级为 sessionId（与通用聊天的会话缓存策略一致） */
    private String currentUserKey() {
        try {
            String username = JwtUtil.getUserNameByToken(
                    org.jeecg.common.util.SpringContextUtils.getHttpServletRequest());
            if (username != null && !username.isEmpty()) {
                return username;
            }
            return org.jeecg.common.util.SpringContextUtils.getHttpServletRequest().getSession().getId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 跨浏览器会话恢复：当前用户的会话列表（含消息记录） */
    @GetMapping("/sessions")
    public Result<List<JSONObject>> sessions() {
        String user = currentUserKey();
        if (user == null) {
            return Result.error("未登录");
        }
        return Result.OK(agentSessionDao.list(user));
    }

    /** 幂等保存一个会话（含消息记录） */
    @PostMapping("/sessions/save")
    public Result<String> saveSession(@RequestBody JSONObject body) {
        String user = currentUserKey();
        if (user == null) {
            return Result.error("未登录");
        }
        String conversationId = body.getString("conversationId");
        if (conversationId == null || conversationId.isEmpty()) {
            return Result.error("conversationId 不能为空");
        }
        // 已删除的会话不再被陈旧窗口的补推写回（否则删除后会「复活」）
        if (sessionStore.isSessionDeleted(user, conversationId)) {
            return Result.OK("ok");
        }
        agentSessionDao.save(user, body);
        return Result.OK("ok");
    }

    /** 删除一个持久化会话 */
    @PostMapping("/sessions/delete")
    public Result<String> deleteSession(@RequestBody JSONObject body) {
        String user = currentUserKey();
        if (user == null) {
            return Result.error("未登录");
        }
        String conversationId = body.getString("conversationId");
        if (conversationId != null && !conversationId.isEmpty()) {
            // 先打墓碑：删除瞬间若有正在跑的对话轮次，结束时也不会把它 upsert 回来
            sessionStore.markSessionDeleted(user, conversationId);
            agentSessionDao.delete(user, conversationId);
        }
        return Result.OK("ok");
    }

    /** 可选模型列表（仅已激活，不含密钥），供页面模型选择下拉 */
    @GetMapping("/models")
    public Result<List<Map<String, Object>>> models() {
        return Result.OK(modelVariantService.listActiveModels());
    }

    @PostMapping("/conversation")
    public Result<Map<String, String>> createConversation() {
        String conversationId = UUID.randomUUID().toString().replace("-", "");
        String batchNo = "B" + System.currentTimeMillis();
        sessionStore.getOrCreate(conversationId, batchNo);
        return Result.OK(Map.of("conversationId", conversationId, "batchNo", batchNo));
    }

    @GetMapping("/context")
    public Result<JSONObject> context(@RequestParam String conversationId) {
        return Result.OK(agentService.contextSnapshot(conversationId));
    }

    @PostMapping("/bindWatershed")
    public Result<JSONObject> bindWatershed(@RequestBody Map<String, String> body) {
        String conversationId = body.get("conversationId");
        String lybm = body.get("lybm");
        String lymc = body.get("lymc");
        if (conversationId == null || conversationId.isEmpty() || lybm == null || lybm.isEmpty()) {
            return Result.error("conversationId / lybm 不能为空");
        }
        return Result.OK(agentService.bindWatershed(conversationId, lybm, lymc));
    }

    /** 页面模型选择：modelId 传空串表示恢复系统默认模型 */
    @PostMapping("/bindModel")
    public Result<JSONObject> bindModel(@RequestBody Map<String, String> body) {
        String conversationId = body.get("conversationId");
        if (conversationId == null || conversationId.isEmpty()) {
            return Result.error("conversationId 不能为空");
        }
        try {
            return Result.OK(agentService.bindModel(conversationId, body.get("modelId"), body.get("modelLabel")));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/cancel")
    public Result<String> cancel(@RequestParam(required = false) String conversationId,
                                 @RequestBody(required = false) Map<String, String> body) {
        if (conversationId == null || conversationId.isEmpty()) {
            conversationId = body == null ? null : body.get("conversationId");
        }
        if (conversationId == null || conversationId.isEmpty()) {
            return Result.error("conversationId 不能为空");
        }
        agentService.cancel(conversationId);
        return Result.OK("已请求停止");
    }

    /** 大纲确认后放行暂存的生成动作（SSE，事件协议与 /chat/send 一致） */
    @PostMapping(value = "/outline/confirm")
    public SseEmitter confirmOutline(@RequestParam String conversationId,
                                     @RequestParam(required = false) String requestId,
                                     @RequestParam(required = false) String userMessage,
                                     @RequestBody Map<String, Object> body) {
        com.alibaba.fastjson.JSONArray outline = body.get("outline") == null
                ? new com.alibaba.fastjson.JSONArray()
                : com.alibaba.fastjson.JSON.parseArray(com.alibaba.fastjson.JSON.toJSONString(body.get("outline")));
        SseEmitter emitter = new SseEmitter(1800_000L);
        emitter.onTimeout(() -> log.warn("山洪报告助手大纲确认 SSE 超时 conv={}", conversationId));
        emitter.onError(e -> log.warn("山洪报告助手大纲确认 SSE 异常 conv={}: {}", conversationId, e.getMessage()));
        boolean asyncFlow = agentService.confirmOutline(conversationId, outline, userMessage, requestId, emitter);
        if (!asyncFlow) {
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
        }
        return emitter;
    }

    /** 断线重连：按 requestId 重新接入同一轮 SSE，按序补发错过的消息后继续实时推送 */
    @GetMapping(value = "/receive/{requestId}", produces = "text/event-stream")
    public SseEmitter receive(@PathVariable String requestId,
                              jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setContentType("text/event-stream;charset=UTF-8");
        return agentService.reconnect(requestId);
    }

    @PostMapping(value = "/chat/send", produces = "text/event-stream")
    public SseEmitter send(@RequestParam String conversationId,
                           @RequestParam(required = false) String batchNo,
                           @RequestParam(required = false) String requestId,
                           @RequestBody Map<String, String> body,
                           jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        response.setContentType("text/event-stream;charset=UTF-8");
        String content = body.getOrDefault("content", "");
        SseEmitter emitter = new SseEmitter(1800_000L);
        emitter.onTimeout(() -> log.warn("山洪报告助手 SSE 超时 conv={}", conversationId));
        emitter.onError(e -> log.warn("山洪报告助手 SSE 异常 conv={}: {}", conversationId, e.getMessage()));
        boolean asyncFlow = agentService.handleChat(conversationId, content, batchNo, requestId, emitter);
        if (!asyncFlow) {
            try {
                emitter.complete();
            } catch (Exception ignore) {
            }
        }
        return emitter;
    }
}
