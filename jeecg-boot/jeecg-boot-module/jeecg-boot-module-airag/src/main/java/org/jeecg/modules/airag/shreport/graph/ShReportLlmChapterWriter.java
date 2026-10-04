package org.jeecg.modules.airag.shreport.graph;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.airag.shreport.service.ShReportGenerateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 章节写作：Graph 的 write 节点调用此处，统一走 AI 流程设计里的单章流程
 * （数据包/提纲/补充资料由流程入参注入，见 ShReportGenerateService#buildChapterFlowInput；知识库范文不注入生成）。
 * 流程返回占位时原样上抛，由 persist 节点按占位/阻断落「失败」状态。
 */
@Slf4j
@Component
public class ShReportLlmChapterWriter implements ShReportGraphNodes.ChapterWriter {

    @Autowired
    @Lazy
    private ShReportGenerateService generateService;

    /** 最近一次写作实际用到的范文来源，按 batch|chapter 记录，供生成链路向用户展示（取后即删） */
    private final java.util.concurrent.ConcurrentMap<String, List<String>> kbSourcesByChapter =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 取出并清除某章本次写作参考的一期成果范文文档名 */
    public List<String> consumeKbSources(String batchNo, int chapterNo) {
        List<String> sources = kbSourcesByChapter.remove((batchNo == null ? "" : batchNo) + "|" + chapterNo);
        return sources == null ? List.of() : sources;
    }

    public String write(ShReportGraphState state, int chapterNo, String requirement) {
        return write(state, chapterNo, requirement, null);
    }

    /** usageSink 非空时回传流程 LLM 节点解析到的 token 用量（scene/chapterNo 在此补充） */
    public String write(ShReportGraphState state, int chapterNo, String requirement,
                        java.util.function.Consumer<com.alibaba.fastjson.JSONObject> usageSink) {
        String writingReq = sanitizeRequirement(requirement);
        ShReportGenerateService.FlowWriteContext ctx = new ShReportGenerateService.FlowWriteContext();
        // 质检回写（retryCount>0）时把上一稿传给流程，做「修订重写」而不是推倒重来
        if (state.retryCount() > 0) {
            String prev = state.chapters().get(chapterNo);
            if (prev != null && !prev.isBlank()
                    && !ShReportGenerateService.isPlaceholder(prev)) {
                ctx.prevDraft = prev;
            }
        }
        if (usageSink != null) {
            ctx.usageSink = u -> {
                u.put("scene", "chapter");
                u.put("chapterNo", chapterNo);
                usageSink.accept(u);
            };
        }
        String content = generateService.writeViaFlow(state.batchNo(), state.lybm(), state.lymc(),
                chapterNo, writingReq, emptyToNull(state.outline()), emptyToNull(state.modelId()), ctx);
        if (!ctx.kbSourcesOut.isEmpty()) {
            kbSourcesByChapter.put((state.batchNo() == null ? "" : state.batchNo()) + "|" + chapterNo,
                    ctx.kbSourcesOut);
        }
        return content;
    }

    /** 占位失败后的质检清单不要再塞进提示词，避免模型对着失败文案空转。 */
    static String sanitizeRequirement(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return requirement;
        }
        if (requirement.contains("存在系统占位") || requirement.contains("上一版存在以下问题")) {
            int cut = requirement.indexOf("上一版存在以下问题");
            if (cut > 0) {
                return requirement.substring(0, cut).trim();
            }
            return null;
        }
        return requirement;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
