package org.jeecg.modules.airag.onemap.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jeecg.modules.airag.shreport.service.ShReportPgQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一张图数据 Excel 导入：按调查年份（survey_year）入库。
 * 表头按「列名或中文名」匹配白名单列；覆盖模式先清空该年份的存量数据。
 * Excel 无法携带空间几何，流域面/断面线/防治对象面的 geom 保持为空（不出现在地图上，
 * 仅进入表格与统计）；隐患点按经纬度自动生成点几何。
 */
@Slf4j
@Service
public class ShReportMapImportService {

    @Autowired
    private ShReportMapAdminService adminService;

    @Autowired
    private ShReportPgQueryService pgQueryService;

    /** 导入模式：append 追加 / replace 覆盖（先清空该年份） */
    public enum Mode {
        APPEND, REPLACE
    }

    /**
     * @return [导入行数, 跳过空行数]
     */
    public int[] importExcel(String ds, int year, String mode, MultipartFile file) throws Exception {
        ShReportMapAdminService.DsConf d = adminService.dataset(ds);
        Mode m;
        try {
            m = Mode.valueOf((mode == null ? "APPEND" : mode).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("导入模式仅支持 append / replace");
        }
        if (year <= 0) {
            throw new IllegalArgumentException("必须指定调查年份");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的 Excel 文件(.xlsx)");
        }
        List<Map<String, Object>> rows = parseExcel(d, file);
        if (rows.isEmpty()) {
            return new int[]{0, 0};
        }
        // 几何列：隐患点由经纬度生成，其余数据集 Excel 导入无几何
        boolean hazardPoint = "hazard".equals(d.key());

        if (m == Mode.REPLACE) {
            int removed = pgQueryService.update("DELETE FROM " + d.table() + " WHERE survey_year = ?", year);
            log.info("覆盖导入 {} 年份 {}：先清除 {} 行", d.table(), year, removed);
        }
        int inserted = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            List<String> colSql = new ArrayList<>();
            List<String> ph = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            boolean empty = true;
            for (ShReportMapAdminService.ColConf c : d.cols()) {
                Object v = row.get(c.col());
                if (v != null && !String.valueOf(v).isEmpty()) {
                    empty = false;
                }
                colSql.add(c.col());
                ph.add("?");
                args.add(v);
            }
            if (empty) {
                skipped++;
                continue;
            }
            colSql.add("survey_year");
            ph.add("?");
            args.add(year);
            if (hazardPoint) {
                Double lng = toD(row.get("lgtd"));
                Double lat = toD(row.get("lttd"));
                if (lng != null && lat != null) {
                    colSql.add("geom");
                    ph.add("ST_SetSRID(ST_MakePoint(?, ?), 4326)");
                    args.add(lng);
                    args.add(lat);
                }
            }
            inserted += pgQueryService.update("INSERT INTO " + d.table() + " ("
                    + String.join(", ", colSql) + ") VALUES (" + String.join(", ", ph) + ")", args.toArray());
        }
        return new int[]{inserted, skipped};
    }

    /** 解析 xlsx：首行表头按（列名或中文名）映射白名单列，后续行转数据 */
    List<Map<String, Object>> parseExcel(ShReportMapAdminService.DsConf d, MultipartFile file) throws Exception {
        Map<String, String> headerMap = new LinkedHashMap<>();
        for (ShReportMapAdminService.ColConf c : d.cols()) {
            headerMap.put(c.col().toLowerCase(Locale.ROOT), c.col());
            headerMap.put(c.label(), c.col());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (InputStream in = file.getInputStream(); XSSFWorkbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null || sheet.getRow(0) == null) {
                throw new IllegalArgumentException("Excel 为空或缺少表头行");
            }
            // 表头 -> 列下标
            Map<Integer, String> colIdx = new LinkedHashMap<>();
            Row header = sheet.getRow(0);
            for (Cell cell : header) {
                String text = fmt.formatCellValue(cell).trim();
                if (text.isEmpty()) {
                    continue;
                }
                String col = headerMap.get(text);
                if (col == null) {
                    col = headerMap.get(text.toLowerCase(Locale.ROOT));
                }
                if (col == null) {
                    // 去掉常见单位后缀再试：面积(km²) -> 面积
                    int p = text.indexOf('(');
                    if (p > 0) {
                        col = headerMap.get(text.substring(0, p).trim());
                    }
                }
                if (col != null) {
                    colIdx.put(cell.getColumnIndex(), col);
                }
            }
            if (colIdx.isEmpty()) {
                throw new IllegalArgumentException("表头无法识别，请使用列名或中文名：" + d.cols().stream()
                        .map(ShReportMapAdminService.ColConf::label).toList());
            }
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, Object> data = new LinkedHashMap<>();
                for (Map.Entry<Integer, String> e : colIdx.entrySet()) {
                    Cell cell = row.getCell(e.getKey());
                    if (cell == null || cell.getCellType() == CellType.BLANK) {
                        continue;
                    }
                    String col = e.getValue();
                    ShReportMapAdminService.ColConf conf = d.cols().stream()
                            .filter(c -> c.col().equals(col)).findFirst().orElse(null);
                    if (conf != null && conf.numeric()) {
                        data.put(col, numericCell(cell, fmt));
                    } else {
                        String text = fmt.formatCellValue(cell).trim();
                        data.put(col, text.isEmpty() ? null : text);
                    }
                }
                rows.add(data);
            }
        }
        return rows;
    }

    /** 数值单元格：数字取原值；文本剔除空白与全角字符后尝试解析，失败按 NULL */
    private Object numericCell(Cell cell, DataFormatter fmt) {
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        String s = fmt.formatCellValue(cell).trim().replace('（', '(');
        int p = s.indexOf('(');
        if (p > 0) {
            s = s.substring(0, p).trim();
        }
        if (s.isEmpty() || "-".equals(s) || "--".equals(s)) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double toD(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
