package org.jeecg.modules.airag.shreport;

import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.jeecg.modules.airag.shreport.file.ShFileObjectService;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现场照片嵌入表格单元格：有图插图，无图写「暂无」，正文里的占位符保持原文。
 */
class ReportWordBuilderPhotoTest {

    @Test
    void embedsOnePhotoIntoTableCell() throws Exception {
        byte[] png = pngBytes();
        Path dir = Files.createTempDirectory("sh-word");
        String md = """
                # 三、外业调查成果

                正文保留占位不插图【照片:BR001】。

                | 名称 | 编码 | 现场照片 |
                | --- | --- | --- |
                | 某桥 | BR001 | 【照片:BR001】 |
                | 无图 | BR002 | 【照片:BR002】 |
                """;
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("content", md);
        File docx = ReportWordBuilder.build(List.of(ch), dir.toString(), "t",
                code -> "BR001".equals(code)
                        ? new ReportWordBuilder.CellPhoto(png, Document.PICTURE_TYPE_PNG, "a.png")
                        : null);
        try (XWPFDocument doc = new XWPFDocument(new FileInputStream(docx))) {
            XWPFTable table = doc.getTables().get(0);
            XWPFTableCell withPhoto = table.getRow(1).getCell(2);
            assertEquals(1, withPhoto.getParagraphs().get(0).getRuns().get(0).getEmbeddedPictures().size());
            assertEquals("暂无", table.getRow(2).getCell(2).getText());
            String body = doc.getParagraphs().stream().map(XWPFParagraph::getText).reduce("", String::concat);
            assertTrue(body.contains("【照片:BR001】"));
        }
    }

    @Test
    void prefersUpstreamJpegOverDownstreamAndSkipsVideo() {
        ShFileObjectService svc = new ShFileObjectService();
        Map<String, Object> chosen = svc.choosePhotoRow(List.of(
                row("下01.jpg", "jpg", "下", "photo"),
                row("上01.webp", "webp", "上", "photo"),
                row("上.mp4", "mp4", "上", "video"),
                row("上02.jpg", "jpg", "上", "photo")));
        assertEquals("上02.jpg", chosen.get("file_name"));
        assertNull(svc.choosePhotoRow(List.of(row("a.webp", "webp", "上", "photo"))));
    }

    private static Map<String, Object> row(String name, String ext, String position, String type) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("file_name", name);
        row.put("ext", ext);
        row.put("file_position", position);
        row.put("file_type", type);
        return row;
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage img = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 6; y++) {
                img.setRGB(x, y, Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
