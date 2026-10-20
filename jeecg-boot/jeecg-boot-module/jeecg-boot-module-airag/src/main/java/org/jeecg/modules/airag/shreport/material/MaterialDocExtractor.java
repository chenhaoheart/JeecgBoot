package org.jeecg.modules.airag.shreport.material;

import dev.langchain4j.data.document.Document;
import org.jeecg.modules.airag.llm.document.TikaDocumentParser;

import java.io.File;

/**
 * PDF/Word/TXT 正文抽取，复用知识库 TikaDocumentParser。
 */
public final class MaterialDocExtractor {

    private static final TikaDocumentParser PARSER = new TikaDocumentParser();

    private MaterialDocExtractor() {
    }

    /**
     * @return 抽取文本；扫描件几乎无字时返回空串（由调用方决定 failed / 带 OCR 提示）
     */
    public static String extract(File file, int maxChars) {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在");
        }
        Document doc = PARSER.parse(file);
        String text = doc == null || doc.text() == null ? "" : doc.text().trim();
        if (text.length() > maxChars) {
            return text.substring(0, maxChars) + "\n\n（内容过长已截断）";
        }
        return text;
    }

    /** 文件不小但几乎抽不出字，视为扫描件 */
    public static boolean likelyScan(String text, long fileSize) {
        int n = text == null ? 0 : text.replaceAll("\\s+", "").length();
        return n < 50 && fileSize > 20 * 1024;
    }

    public static boolean hasUsableText(String text) {
        int n = text == null ? 0 : text.replaceAll("\\s+", "").length();
        return n >= 50;
    }
}
