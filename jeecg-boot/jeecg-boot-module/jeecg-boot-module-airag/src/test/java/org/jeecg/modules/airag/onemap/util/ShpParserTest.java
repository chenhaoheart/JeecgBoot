package org.jeecg.modules.airag.onemap.util;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShpParser 固件由 sh_report_import/gen_shp_test_fixture.py 生成（pyshp，dbf 为 GBK）。
 */
class ShpParserTest {

    private byte[] res(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/shp/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    private static void assertNotNull(Object v, String what) {
        assertTrue(v != null, what + " 不能为空");
    }

    @Test
    void polygonSingleRingAndMultiRing() throws Exception {
        List<ShpParser.Feature> fs = ShpParser.parse(res("test_polygon.shp"), res("test_polygon.dbf"));
        assertEquals(2, fs.size());

        ShpParser.Feature f1 = fs.get(0);
        assertEquals("Polygon", f1.geometry().get("type"));
        List<?> ring = (List<?>) ((List<?>) f1.geometry().get("coordinates")).get(0);
        assertEquals(4, ring.size()); // 3 顶点 + 闭合点
        List<?> first = (List<?>) ring.get(0);
        List<?> last = (List<?>) ring.get(ring.size() - 1);
        assertEquals(first.get(0), last.get(0));
        assertEquals(first.get(1), last.get(1));
        // GBK 中文属性与数值字段
        assertEquals("WDA380012X200000", f1.props().get("WSWSCD"));
        assertEquals("杨家河", f1.props().get("WKMM"));
        assertEquals(123.4567, (Double) f1.props().get("WSAREA"), 1e-9);
        assertEquals("巴家咀水库", f1.props().get("涉及水库"));

        // 双环 -> MultiPolygon（每环独立成面，同 import_shmap.py 口径）
        ShpParser.Feature f2 = fs.get(1);
        assertEquals("MultiPolygon", f2.geometry().get("type"));
        assertEquals(2, ((List<?>) f2.geometry().get("coordinates")).size());
        assertTrue(String.valueOf(f2.props().get("WKMM")).startsWith("瓜子沟上游"));
        assertNull(f2.props().get("涉及水库"));
    }

    @Test
    void polylineSingleAndMultiPart() throws Exception {
        List<ShpParser.Feature> fs = ShpParser.parse(res("test_line.shp"), res("test_line.dbf"));
        assertEquals(2, fs.size());
        assertEquals("LineString", fs.get(0).geometry().get("type"));
        assertEquals(3, ((List<?>) fs.get(0).geometry().get("coordinates")).size());
        assertEquals("SEC001", fs.get(0).props().get("PID"));
        assertEquals("MultiLineString", fs.get(1).geometry().get("type"));
        assertEquals(2, ((List<?>) fs.get(1).geometry().get("coordinates")).size());
    }

    @Test
    void pointFeature() throws Exception {
        List<ShpParser.Feature> fs = ShpParser.parse(res("test_point.shp"), res("test_point.dbf"));
        assertEquals(1, fs.size());
        Map<String, Object> g = fs.get(0).geometry();
        assertEquals("Point", g.get("type"));
        List<?> c = assertInstanceOf(List.class, g.get("coordinates"));
        assertEquals(104.05, (Double) c.get(0), 1e-9);
        assertEquals(35.05, (Double) c.get(1), 1e-9);
        assertEquals("跨沟桥梁", fs.get(0).props().get("NAME"));
    }

    @Test
    void dbfWithoutShpPairingStillSafe() throws Exception {
        // dbf 传 null：几何照常解析，属性为空 Map
        List<ShpParser.Feature> fs = ShpParser.parse(res("test_point.shp"), null);
        assertEquals(1, fs.size());
        assertTrue(fs.get(0).props().isEmpty());
    }
}
