package org.jeecg.modules.airag.onemap.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shapefile 只读解析（.shp + .dbf，按 ESRI 白皮书字节序实现，不依赖 GeoTools）。
 * 几何转 GeoJSON geometry（Map 结构，Z/M 量测忽略），属性转字段名-值 Map；
 * 与 import_shmap.py 的 shp_to_geojson_geom 保持同一多环处理口径：每环独立成面。
 * dbf 文本编码自动识别：UTF-8 严格解码失败时回退 GBK（源数据 dbf 为 GBK）。
 */
public final class ShpParser {

    /** 一个要素：GeoJSON geometry（无几何时为 null）+ dbf 属性 */
    public record Feature(Map<String, Object> geometry, Map<String, Object> props) {
    }

    private ShpParser() {
    }

    /** .shp 与 .dbf 按记录序号一一配对（dbf 为空时属性为空 Map） */
    public static List<Feature> parse(byte[] shp, byte[] dbf) {
        List<Map<String, Object>> geoms = parseShp(shp);
        List<Map<String, Object>> props = dbf == null ? List.of() : parseDbf(dbf);
        List<Feature> out = new ArrayList<>(geoms.size());
        for (int i = 0; i < geoms.size(); i++) {
            Map<String, Object> p = i < props.size() ? props.get(i) : new LinkedHashMap<>();
            out.add(new Feature(geoms.get(i), p));
        }
        return out;
    }

    // ---------------------------------------------------------------- .shp

    static List<Map<String, Object>> parseShp(byte[] b) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (b == null || b.length < 100) {
            throw new IllegalArgumentException(".shp 文件头不完整（不足 100 字节）");
        }
        int pos = 100;
        while (pos + 8 <= b.length) {
            int contentWords = readIntBE(b, pos + 4);
            pos += 8;
            int contentLen = contentWords * 2;
            if (contentLen < 4 || pos + contentLen > b.length) {
                break;
            }
            Map<String, Object> geom = parseGeometry(readIntLE(b, pos), b, pos + 4, contentLen - 4);
            if (geom != null) {
                out.add(geom);
            }
            pos += contentLen;
        }
        return out;
    }

    /** 按要素形状类型转 GeoJSON；Null Shape 与空部件返回 null */
    private static Map<String, Object> parseGeometry(int shapeType, byte[] b, int off, int len) {
        switch (shapeType) {
            case 1, 11, 21: // Point / PointZ / PointM：XY 后的 Z/M 忽略
                if (len < 16) {
                    return null;
                }
                return geometry("Point", List.of(readDoubleLE(b, off), readDoubleLE(b, off + 8)));
            case 8, 18, 28: { // MultiPoint / Z / M
                int n = readIntLE(b, off + 32);
                List<Object> pts = new ArrayList<>(Math.max(n, 0));
                for (int i = 0; i < n && off + 36 + i * 16 + 16 <= b.length; i++) {
                    pts.add(List.of(readDoubleLE(b, off + 36 + i * 16), readDoubleLE(b, off + 36 + i * 16 + 8)));
                }
                if (pts.isEmpty()) {
                    return null;
                }
                if (pts.size() == 1) {
                    return geometry("Point", pts.get(0));
                }
                return geometry("MultiPoint", pts);
            }
            case 3, 13, 23: // PolyLine / Z / M
                return parseParts(b, off, false);
            case 5, 15, 35: // Polygon / Z / M
                return parseParts(b, off, true);
            case 0: // Null Shape
            default:
                return null;
        }
    }

    /** PolyLine / Polygon 通用：Box(32B) + NumParts + NumPoints + Parts[] + Points[] */
    private static Map<String, Object> parseParts(byte[] b, int off, boolean polygon) {
        if (off + 40 > b.length) {
            return null;
        }
        int numParts = readIntLE(b, off + 32);
        int numPoints = readIntLE(b, off + 36);
        if (numParts <= 0 || numPoints <= 0) {
            return null;
        }
        int partsOff = off + 40;
        int pointsOff = partsOff + numParts * 4;
        List<Object> paths = new ArrayList<>(numParts);
        for (int i = 0; i < numParts; i++) {
            int start = readIntLE(b, partsOff + i * 4);
            int end = i + 1 < numParts ? readIntLE(b, partsOff + (i + 1) * 4) : numPoints;
            if (start < 0 || end <= start || end > numPoints) {
                continue;
            }
            List<Object> path = new ArrayList<>(end - start + 1);
            for (int j = start; j < end; j++) {
                int p = pointsOff + j * 16;
                if (p + 16 > b.length) {
                    break;
                }
                path.add(List.of(readDoubleLE(b, p), readDoubleLE(b, p + 8)));
            }
            if (polygon) {
                // 环闭合（GeoJSON 线性环要求首尾同点）
                if (path.size() >= 3 && !path.get(0).equals(path.get(path.size() - 1))) {
                    path.add(path.get(0));
                }
                if (path.size() >= 4) {
                    paths.add(path);
                }
            } else if (path.size() >= 2) {
                paths.add(path);
            }
        }
        if (paths.isEmpty()) {
            return null;
        }
        if (polygon) {
            // 与 import_shmap.py 同口径：单环 Polygon，多环按每环独立面合成 MultiPolygon
            if (paths.size() == 1) {
                return geometry("Polygon", List.of(paths.get(0)));
            }
            List<Object> polys = new ArrayList<>(paths.size());
            for (Object ring : paths) {
                polys.add(List.of(ring));
            }
            return geometry("MultiPolygon", polys);
        }
        if (paths.size() == 1) {
            return geometry("LineString", paths.get(0));
        }
        return geometry("MultiLineString", paths);
    }

    private static Map<String, Object> geometry(String type, Object coordinates) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", type);
        g.put("coordinates", coordinates);
        return g;
    }

    // ---------------------------------------------------------------- .dbf

    static List<Map<String, Object>> parseDbf(byte[] b) {
        if (b == null || b.length < 33) {
            throw new IllegalArgumentException(".dbf 文件头不完整");
        }
        int numRecords = readIntLE(b, 4);
        int headerSize = readUShortLE(b, 8);
        int recordSize = readUShortLE(b, 10);
        if (numRecords < 0 || headerSize <= 32 || recordSize <= 1 || headerSize + (long) numRecords * recordSize > b.length) {
            throw new IllegalArgumentException(".dbf 文件头异常：记录数 " + numRecords + " 与文件长度不符");
        }
        // 字段描述符：自 32 起每 32 字节，0x0D 结束
        List<String> names = new ArrayList<>();
        List<Character> types = new ArrayList<>();
        List<Integer> lengths = new ArrayList<>();
        int p = 32;
        while (p + 32 <= headerSize && p < b.length) {
            if (b[p] == 0x0D) {
                break;
            }
            names.add(dbfName(b, p));
            types.add((char) b[p + 11]);
            lengths.add(b[p + 16] & 0xFF);
            p += 32;
        }
        List<Map<String, Object>> rows = new ArrayList<>(numRecords);
        int pos = headerSize;
        for (int i = 0; i < numRecords && pos + recordSize <= b.length; i++, pos += recordSize) {
            if (b[pos] == 0x2A) { // 已删除记录
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            int f = pos + 1;
            for (int c = 0; c < names.size(); c++) {
                int len = lengths.get(c);
                row.put(names.get(c), dbfValue(types.get(c), b, f, len));
                f += len;
            }
            rows.add(row);
        }
        return rows;
    }

    /** 字段名与值同样走编码探测（GBK dbf 的中文字段名，如「涉及水库」） */
    private static String dbfName(byte[] b, int off) {
        String s = decodeDbfText(b, off, 11);
        return (s == null || s.isEmpty()) ? ("F" + off) : s;
    }

    private static Object dbfValue(char type, byte[] b, int off, int len) {
        if (off + len > b.length) {
            return null;
        }
        switch (type) {
            case 'C': // 字符
                return decodeDbfText(b, off, len);
            case 'N', 'F', 'O': // 数值/浮点
                String s = decodeDbfText(b, off, len);
                if (s == null) {
                    return null;
                }
                try {
                    return Double.parseDouble(s.replace(",", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
            case 'D': // 日期 YYYYMMDD
                String d = decodeDbfText(b, off, len);
                return d == null || d.length() != 8 ? d : d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
            case 'L': // 布尔 Y/N/T/F
                return decodeDbfText(b, off, len);
            default: // M（备注，需 .fpt）等不支持，置空
                return null;
        }
    }

    /** 字节文本解码：先按纯 ASCII 快速路径，否则 UTF-8 严格解码，失败回退 GBK */
    private static String decodeDbfText(byte[] b, int off, int len) {
        int end = off + len;
        while (end > off && (b[end - 1] == 0 || b[end - 1] == ' ')) {
            end--;
        }
        int start = off;
        while (start < end && (b[start] == ' ' || b[start] == 0)) {
            start++;
        }
        if (start >= end) {
            return null;
        }
        byte[] raw = Arrays.copyOfRange(b, start, end);
        boolean ascii = true;
        for (byte x : raw) {
            if (x < 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return new String(raw, StandardCharsets.ISO_8859_1).trim();
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString().trim();
        } catch (CharacterCodingException e) {
            return new String(raw, Charset.forName("GBK")).trim();
        }
    }

    // ---------------------------------------------------------------- 字节读取

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int readUShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static double readDoubleLE(byte[] b, int off) {
        return Double.longBitsToDouble((long) readIntLE(b, off) & 0xFFFFFFFFL | ((long) readIntLE(b, off + 4) & 0xFFFFFFFFL) << 32);
    }
}
