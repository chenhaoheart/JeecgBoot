package org.jeecg.modules.airag.shreport;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 章节质检问题。level=BLOCK 阻断（占位/过短/坏表格，触发自动重生成），其余为 HINT 提示。
 * <p>
 * chapterNos 为结构化关联章节号，供生成图决定回写哪些章。
 * 不要从 message 里正则反解章节号——文案格式一变就会静默失效。
 */
public class ShReportQcIssue implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String LEVEL_BLOCK = "BLOCK";
    public static final String LEVEL_HINT = "HINT";

    public static final String TYPE_PLACEHOLDER = "PLACEHOLDER";
    public static final String TYPE_SHORT = "SHORT";
    public static final String TYPE_TABLE = "TABLE";
    public static final String TYPE_NUMBER = "NUMBER";
    public static final String TYPE_WATERSHED = "WATERSHED";
    public static final String TYPE_REQUIRED_TABLE = "REQUIRED_TABLE";
    public static final String TYPE_STYLE = "STYLE";
    public static final String TYPE_CONSISTENCY = "CONSISTENCY";

    private String level;
    private String type;
    private String message;
    /** 关联的章节号（跨章一致性问题用于图内回写，不落库） */
    private List<Integer> chapterNos;

    public ShReportQcIssue() {
    }

    public ShReportQcIssue(String level, String type, String message) {
        this.level = level;
        this.type = type;
        this.message = message;
    }

    public static ShReportQcIssue block(String type, String message) {
        return new ShReportQcIssue(LEVEL_BLOCK, type, message);
    }

    public static ShReportQcIssue hint(String type, String message) {
        return new ShReportQcIssue(LEVEL_HINT, type, message);
    }

    /** 绑定关联章节号，返回自身便于链式调用 */
    public ShReportQcIssue withChapters(Collection<Integer> chapterNos) {
        List<Integer> copy = new ArrayList<>();
        if (chapterNos != null) {
            for (Integer no : chapterNos) {
                if (no != null && !copy.contains(no)) {
                    copy.add(no);
                }
            }
        }
        Collections.sort(copy);
        this.chapterNos = copy;
        return this;
    }

    /** 关联章节号，永远非 null */
    public List<Integer> chapterNos() {
        return chapterNos == null ? Collections.emptyList() : Collections.unmodifiableList(chapterNos);
    }

    public boolean isBlock() {
        return LEVEL_BLOCK.equals(level);
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<Integer> getChapterNos() {
        return chapterNos;
    }

    public void setChapterNos(List<Integer> chapterNos) {
        this.chapterNos = chapterNos;
    }
}
