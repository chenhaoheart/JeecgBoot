package org.jeecg.modules.airag.shreport.util;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.jeecg.common.util.oConvertUtils;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSimpleField;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSettings;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 山洪报告 Word 构建：Markdown 标题/列表/加粗/表格按公文风格渲染为 docx。
 */
public final class ReportWordBuilder {

    private static final Pattern OL = Pattern.compile("^\\d+[\\.、]\\s+.+");
    private static final Pattern UL = Pattern.compile("^[-*•]\\s+.+");
    /** 表格单元格里的现场照片占位，整格只有这一处 */
    private static final Pattern PHOTO_CELL = Pattern.compile("^【照片[:：]\\s*([^】]+?)\\s*】$");
    /** 行内是否出现照片占位（判断整表是否需要横版页） */
    private static final Pattern PHOTO_ANY = Pattern.compile("【照片[:：]\\s*[^】]+?\\s*】");
    /** A4 页面尺寸（twips）：竖版 11906×16838，横版宽高互换 */
    private static final int A4_W = 11906;
    private static final int A4_H = 16838;
    /** 单元格内照片最大宽高（像素，约 3.2cm × 2.4cm） */
    private static final int PHOTO_MAX_W = 120;
    private static final int PHOTO_MAX_H = 90;

    private ReportWordBuilder() {
    }

    /** 嵌入表格单元格的一张现场照片 */
    public record CellPhoto(byte[] bytes, int pictureType, String fileName) {
    }

    /** 按隐患点编码取一张照片；没有则返回 null */
    @FunctionalInterface
    public interface CellPhotoResolver {
        CellPhoto load(String code);
    }

    public static File build(List<Map<String, Object>> chapters, String uploadPath, String fileSuffix) throws Exception {
        return build(chapters, uploadPath, fileSuffix, null);
    }

    public static File build(List<Map<String, Object>> chapters, String uploadPath, String fileSuffix,
                             CellPhotoResolver photos) throws Exception {
        XWPFDocument doc = new XWPFDocument();
        setTitle(doc, "山洪灾害风险隐患调查评价报告", "方正小标宋简体", 22, ParagraphAlignment.CENTER, -1);
        addToc(doc);

        for (Map<String, Object> ch : chapters) {
            String content = oConvertUtils.getString(ch.get("content"), "");
            String[] lines = content.replace("\r\n", "\n").split("\n");
            // 上一段内容是否以「照片表格横版节」收尾：连续照片表格之间不必重复插入竖版节结束符
            boolean afterLandscapeTable = false;
            for (int i = 0; i < lines.length; i++) {
                String raw = lines[i];
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("|---") || line.startsWith("| ---") || line.matches("^\\|?\\s*:?-{3,}.*")) {
                    continue;
                }
                if (line.startsWith("# ")) {
                    afterLandscapeTable = false;
                    setTitle(doc, stripMd(line.substring(2)), "黑体", 16, ParagraphAlignment.LEFT, 0);
                } else if (line.startsWith("## ")) {
                    afterLandscapeTable = false;
                    setTitle(doc, stripMd(line.substring(3)), "黑体", 14, ParagraphAlignment.LEFT, 1);
                } else if (line.startsWith("### ")) {
                    afterLandscapeTable = false;
                    setTitle(doc, stripMd(line.substring(4)), "黑体", 12, ParagraphAlignment.LEFT, 2);
                } else if (line.startsWith("|")) {
                    java.util.List<String> rowLines = new java.util.ArrayList<>();
                    while (i < lines.length && lines[i].trim().startsWith("|")) {
                        String rl = lines[i].trim();
                        if (!(rl.startsWith("|---") || rl.startsWith("| ---") || rl.matches("^\\|?\\s*:?-{3,}.*"))) {
                            rowLines.add(rl);
                        }
                        i++;
                    }
                    i--;
                    boolean photoTable = rowLines.stream().anyMatch(l -> PHOTO_ANY.matcher(l).find());
                    if (photoTable) {
                        // 含照片的表格放独立横版页：先插入竖版节结束符把之前的内容留在竖版，
                        // 表格落在随后的横版节里；表格后的内容由文末竖版节承接。
                        // 节结束符本身带分页，表格自然另起一页
                        if (!afterLandscapeTable) {
                            addSectionEnd(doc, false);
                        }
                        addTable(doc, rowLines, photos);
                        addSectionEnd(doc, true);
                        afterLandscapeTable = true;
                    } else {
                        afterLandscapeTable = false;
                        addTable(doc, rowLines, photos);
                        doc.createParagraph();
                    }
                } else if (OL.matcher(line).matches() || UL.matcher(line).matches()) {
                    afterLandscapeTable = false;
                    String text = line.replaceFirst("^(\\d+[\\.、]|[-*•])\\s+", "");
                    addRichPara(doc, text, "仿宋_GB2312", 12, ParagraphAlignment.LEFT, false, 400);
                } else {
                    afterLandscapeTable = false;
                    boolean tableTitle = line.startsWith("**") && line.endsWith("**") && line.length() > 4;
                    addRichPara(doc, stripOuterBold(line), tableTitle ? "黑体" : "仿宋_GB2312", 12,
                            tableTitle ? ParagraphAlignment.CENTER : ParagraphAlignment.LEFT,
                            !tableTitle, 0);
                }
            }
        }
        // 文末节承载表格之后的所有内容，固定回竖版（横版节只能到表格为止）
        applyBodyPortrait(doc);

        File dir = new File(oConvertUtils.isEmpty(uploadPath) ? "./upFiles" : uploadPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File out = new File(dir, "山洪灾害风险隐患调查评价报告_" + fileSuffix + ".docx");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            doc.write(fos);
        }
        doc.close();
        return out;
    }

    /**
     * 选出可写入 Word 的章节：有正文且非「生成中」。
     * {@code chapterNosCsv} 为空则导出全部可选章；否则只导出指定章号（1-7，逗号分隔）。
     */
    public static List<Map<String, Object>> selectExportChapters(List<Map<String, Object>> rows, String chapterNosCsv) {
        Set<Integer> wanted = parseChapterNos(chapterNosCsv);
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Map<String, Object> r : rows) {
            if (r == null || !(r.get("chapter_no") instanceof Number)) {
                continue;
            }
            int no = ((Number) r.get("chapter_no")).intValue();
            if (!wanted.isEmpty() && !wanted.contains(no)) {
                continue;
            }
            String content = r.get("content") == null ? "" : String.valueOf(r.get("content")).trim();
            if (content.isEmpty()) {
                continue;
            }
            if ("生成中".equals(String.valueOf(r.get("status")))) {
                continue;
            }
            out.add(r);
        }
        out.sort((a, b) -> Integer.compare(
                ((Number) a.get("chapter_no")).intValue(),
                ((Number) b.get("chapter_no")).intValue()));
        return out;
    }

    public static Set<Integer> parseChapterNos(String csv) {
        Set<Integer> set = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return set;
        }
        for (String p : csv.split("[,，\\s]+")) {
            if (p.isBlank()) {
                continue;
            }
            try {
                int n = Integer.parseInt(p.trim());
                if (n >= 1 && n <= 7) {
                    set.add(n);
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return set;
    }

    /**
     * 标题段落。outlineLvl 为 0 起算的大纲级别（0=一级），负数表示不参与目录。
     * 目录域靠大纲级别收集条目，标题必须带 outlineLvl 才能进目录。
     */
    private static void setTitle(XWPFDocument doc, String text, String font, int size, ParagraphAlignment align, int outlineLvl) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(align);
        if (outlineLvl >= 0) {
            CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
            ppr.addNewOutlineLvl().setVal(BigInteger.valueOf(outlineLvl));
        }
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontSize(size);
        r.setFontFamily("Times New Roman");
        setEastAsia(r, font);
    }

    /**
     * 目录页：封面标题后插入「目 录」+ Word 目录域（TOC \o "1-3"），并在 settings.xml
     * 写入 updateFields，让 Word 打开文档时自动刷新目录（否则目录是空占位）。
     */
    private static void addToc(XWPFDocument doc) {
        setTitle(doc, "目  录", "黑体", 16, ParagraphAlignment.CENTER, -1);
        XWPFParagraph p = doc.createParagraph();
        CTSimpleField fld = p.getCTP().addNewFldSimple();
        fld.setInstr("TOC \\o \"1-3\" \\h \\z \\u");
        CTR run = fld.addNewR();
        run.addNewT().setStringValue("（目录将在打开文档时自动更新；若未更新，请全选后按 F9）");
        // 目录后分页，正文另起一页
        XWPFParagraph br = doc.createParagraph();
        br.createRun().addBreak(BreakType.PAGE);
        enableUpdateFieldsOnOpen(doc);
    }

    /**
     * 插入一个空段落作为「节结束符」，并设定它所收尾的这一节的页面方向。
     * Word 中段落的 sectPr 管辖的是它之前的内容：竖版结束符把此前内容留在竖版页，
     * 横版结束符让表格独占横版页；节结束符默认 nextPage 分页，表格自然另起一页。
     */
    private static void addSectionEnd(XWPFDocument doc, boolean landscape) {
        XWPFParagraph p = doc.createParagraph();
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        CTSectPr sect = ppr.isSetSectPr() ? ppr.getSectPr() : ppr.addNewSectPr();
        applyOrientation(sect, landscape);
    }

    /** 文末节（body 级 sectPr）固定回 A4 竖版，承接表格之后的所有内容 */
    private static void applyBodyPortrait(XWPFDocument doc) {
        CTBody body = doc.getDocument().getBody();
        CTSectPr sect = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        applyOrientation(sect, false);
    }

    private static void applyOrientation(CTSectPr sect, boolean landscape) {
        CTPageSz sz = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        sz.setW(BigInteger.valueOf(landscape ? A4_H : A4_W));
        sz.setH(BigInteger.valueOf(landscape ? A4_W : A4_H));
        sz.setOrient(landscape ? STPageOrientation.LANDSCAPE : STPageOrientation.PORTRAIT);
    }

    private static void enableUpdateFieldsOnOpen(XWPFDocument doc) {
        try {
            XWPFSettings settings = doc.getSettings();
            if (settings == null) {
                settings = (XWPFSettings) doc.createRelationship(XWPFRelation.SETTINGS, XWPFFactory.getInstance(), 1);
            }
            // POI 未暴露空 settings 的初始化，手工创建 part 时 ctSettings 为 null，
            // setUpdateFields/commit 会 NPE，这里补一个默认 CTSettings
            Field f = XWPFSettings.class.getDeclaredField("ctSettings");
            f.setAccessible(true);
            if (f.get(settings) == null) {
                f.set(settings, CTSettings.Factory.newInstance());
            }
            settings.setUpdateFields();
        } catch (Exception e) {
            // 设置失败只影响打开时自动刷新目录，目录域仍在，用户可手动更新（Ctrl+A 后 F9）
        }
    }

    private static void addRichPara(XWPFDocument doc, String text, String font, int size,
                                    ParagraphAlignment align, boolean firstIndent, int leftIndent) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(align);
        p.setSpacingBetween(28, LineSpacingRule.EXACT);
        if (firstIndent) {
            p.setFirstLineIndent(size * 20 * 2);
        }
        if (leftIndent > 0) {
            p.setIndentationLeft(leftIndent);
        }
        String[] parts = text.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            XWPFRun r = p.createRun();
            r.setText(parts[i]);
            r.setFontSize(size);
            r.setFontFamily("Times New Roman");
            setEastAsia(r, font);
            if (i % 2 == 1) {
                r.setBold(true);
            }
        }
    }

    private static void setEastAsia(XWPFRun r, String font) {
        r.setFontFamily(font, XWPFRun.FontCharRange.eastAsia);
    }

    private static String stripMd(String s) {
        return s.replaceAll("\\*\\*", "").trim();
    }

    private static String stripOuterBold(String line) {
        if (line.startsWith("**") && line.endsWith("**") && line.length() > 4) {
            return line.substring(2, line.length() - 2);
        }
        return line;
    }

    private static void addTable(XWPFDocument doc, java.util.List<String> rowLines, CellPhotoResolver photos) {
        if (rowLines.size() < 1) {
            return;
        }
        java.util.List<String> header = splitRow(rowLines.get(0));
        java.util.List<java.util.List<String>> body = new java.util.ArrayList<>();
        int start = rowLines.size() > 1 && isSeparator(rowLines.get(1)) ? 2 : 1;
        for (int i = start; i < rowLines.size(); i++) {
            if (!isSeparator(rowLines.get(i))) {
                body.add(splitRow(rowLines.get(i)));
            }
        }
        XWPFTable table = doc.createTable(1 + body.size(), Math.max(header.size(), 1));
        table.setWidth("100%");
        table.setTableAlignment(TableRowAlign.CENTER);
        setBorders(table);
        for (int j = 0; j < header.size(); j++) {
            fillCell(table.getRow(0).getCell(j), header.get(j), "黑体", null);
        }
        for (int ri = 0; ri < body.size(); ri++) {
            java.util.List<String> row = body.get(ri);
            for (int j = 0; j < header.size(); j++) {
                String v = j < row.size() ? row.get(j) : "";
                fillCell(table.getRow(ri + 1).getCell(j), v, "宋体", photos);
            }
        }
    }

    private static boolean isSeparator(String line) {
        return line.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty();
    }

    private static java.util.List<String> splitRow(String line) {
        String s = line;
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        String[] cells = s.split("\\|", -1);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String c : cells) {
            out.add(c.trim());
        }
        return out;
    }

    private static void fillCell(XWPFTableCell cell, String text, String eastAsiaFont, CellPhotoResolver photos) {
        XWPFParagraph p = cell.getParagraphs().isEmpty() ? cell.addParagraph() : cell.getParagraphs().get(0);
        p.setAlignment(ParagraphAlignment.CENTER);
        clearRuns(p);
        String normalized = text == null ? "" : text.replace("**", "").trim();
        Matcher photo = PHOTO_CELL.matcher(normalized);
        if (photo.matches() && photos != null) {
            if (!embedPhoto(cell, p, photo.group(1).trim(), photos)) {
                clearRuns(p);
                writeCellText(p, "暂无", eastAsiaFont);
            }
            return;
        }
        writeCellText(p, text == null ? "" : text, eastAsiaFont);
    }

    /** 把一张照片写入单元格。没有图或写入失败时返回 false，由调用方改写「暂无」。 */
    private static boolean embedPhoto(XWPFTableCell cell, XWPFParagraph p, String code, CellPhotoResolver photos) {
        CellPhoto photo;
        try {
            photo = photos.load(code);
        } catch (RuntimeException e) {
            return false;
        }
        if (photo == null || photo.bytes() == null || photo.bytes().length == 0) {
            return false;
        }
        int[] px = fitPixels(photo.bytes());
        String name = oConvertUtils.isEmpty(photo.fileName()) ? "photo.jpg" : photo.fileName();
        try (ByteArrayInputStream in = new ByteArrayInputStream(photo.bytes())) {
            XWPFRun r = p.createRun();
            r.addPicture(in, photo.pictureType(), name, Units.pixelToEMU(px[0]), Units.pixelToEMU(px[1]));
        } catch (Exception e) {
            return false;
        }
        cell.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        XWPFTableRow row = cell.getTableRow();
        if (row != null) {
            int twips = px[1] * 15 + 120;
            if (row.getHeight() < twips) {
                row.setHeight(twips);
            }
        }
        return true;
    }

    /** 按原图比例缩进单元格，避免撑破表格 */
    private static int[] fitPixels(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                double scale = Math.min(PHOTO_MAX_W / (double) img.getWidth(), PHOTO_MAX_H / (double) img.getHeight());
                return new int[]{
                        Math.max(1, (int) Math.round(img.getWidth() * scale)),
                        Math.max(1, (int) Math.round(img.getHeight() * scale))
                };
            }
        } catch (Exception ignored) {
            // 读不出尺寸时用固定框
        }
        return new int[]{PHOTO_MAX_W, PHOTO_MAX_H};
    }

    private static void clearRuns(XWPFParagraph p) {
        for (int i = p.getRuns().size() - 1; i >= 0; i--) {
            p.removeRun(i);
        }
    }

    private static void writeCellText(XWPFParagraph p, String text, String eastAsiaFont) {
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontSize(10);
        r.setFontFamily("Times New Roman");
        setEastAsia(r, eastAsiaFont);
    }

    private static void setBorders(XWPFTable table) {
        table.setTopBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setBottomBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setLeftBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setRightBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setInsideHBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
        table.setInsideVBorder(XWPFTable.XWPFBorderType.SINGLE, 4, 0, "000000");
    }
}
