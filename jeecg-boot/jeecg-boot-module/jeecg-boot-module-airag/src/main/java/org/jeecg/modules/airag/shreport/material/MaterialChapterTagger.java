package org.jeecg.modules.airag.shreport.material;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 上传资料章节打标：纯规则，按文件名 + 正文关键词路由，不调 LLM。
 */
public final class MaterialChapterTagger {

    private MaterialChapterTagger() {
    }

    public static final class Tag {
        public final String chapterNos;
        public final String category;
        public final boolean autoTagged;

        public Tag(String chapterNos, String category, boolean autoTagged) {
            this.chapterNos = chapterNos;
            this.category = category;
            this.autoTagged = autoTagged;
        }
    }

    private static final String[][] RULES = {
            {"5", "历史灾害,灾情,受灾,洪水年份,洪痕调查"},
            {"3", "桥涵,隐患点,沟道,沟滩,急弯,泥石流,外业"},
            {"4", "洪峰,流量,比降,糙率,壅水,溃决,洪水计算"},
            {"1,6", "人口,乡镇,社会经济,村庄,面积"},
            {"7", "预案,监测,预警,治理,工程措施,建议"},
            {"2", "准备,队伍,物资,内业"},
    };

    /**
     * @param fileName      原始文件名，可空
     * @param extractedText 解析正文，可空
     * @param fileType      excel / document / image
     */
    public static Tag tag(String fileName, String extractedText, String fileType) {
        String hay = ((fileName == null ? "" : fileName) + "\n" + (extractedText == null ? "" : extractedText))
                .toLowerCase(Locale.ROOT);
        Set<String> chapters = new LinkedHashSet<>();
        for (String[] rule : RULES) {
            for (String kw : rule[1].split(",")) {
                if (hay.contains(kw.toLowerCase(Locale.ROOT))) {
                    for (String no : rule[0].split(",")) {
                        chapters.add(no.trim());
                    }
                    break;
                }
            }
        }
        if (chapters.isEmpty()) {
            chapters.add("1");
        }
        return new Tag(String.join(",", chapters), categoryOf(fileName, fileType), true);
    }

    static String categoryOf(String fileName, String fileType) {
        String name = fileName == null ? "" : fileName;
        if (name.contains("历史") || name.contains("灾害") || name.contains("洪痕")) {
            return "历史山洪调查";
        }
        if ("image".equals(fileType)) {
            return "现场照片图纸";
        }
        if ("excel".equals(fileType)) {
            return "基础数据";
        }
        return "其他";
    }

    /** chapter_nos 是否覆盖指定章（逗号分隔） */
    public static boolean coversChapter(String chapterNos, int chapterNo) {
        if (chapterNos == null || chapterNos.isBlank()) {
            return false;
        }
        String want = String.valueOf(chapterNo);
        for (String p : chapterNos.split(",")) {
            if (want.equals(p.trim())) {
                return true;
            }
        }
        return false;
    }

    public static List<Integer> parseChapters(String chapterNos) {
        List<Integer> out = new ArrayList<>();
        if (chapterNos == null || chapterNos.isBlank()) {
            return out;
        }
        for (String p : chapterNos.split(",")) {
            try {
                int n = Integer.parseInt(p.trim());
                if (n >= 1 && n <= 7 && !out.contains(n)) {
                    out.add(n);
                }
            } catch (NumberFormatException ignore) {
                // skip
            }
        }
        return out;
    }
}
