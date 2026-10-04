package org.jeecg.modules.airag.shreport.map;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一张图几何与政区前缀过滤。
 */
class ShReportMapGeometryTest {

    @Test
    void parseCoordRejectsBlankAndNan() {
        assertNull(ShReportMapGeometry.parseCoord(null));
        assertNull(ShReportMapGeometry.parseCoord(""));
        assertNull(ShReportMapGeometry.parseCoord("nan"));
        assertEquals(105.93, ShReportMapGeometry.parseCoord("105.93"), 1e-6);
        assertTrue(ShReportMapGeometry.validLngLat(105.9, 33.7));
        assertFalse(ShReportMapGeometry.validLngLat(200.0, 33.7));
    }

    @Test
    void adcdPrefixMatchesCountyAndTown() {
        assertTrue(ShReportMapGeometry.matchesAdcdPrefix("621227101", "621227"));
        assertFalse(ShReportMapGeometry.matchesAdcdPrefix("621221101", "621227"));
        assertTrue(ShReportMapGeometry.matchesAdcdPrefix("621227", null));

        Map<String, Object> row = new HashMap<>();
        row.put("county_adcd", "621227");
        row.put("town_adcd", "621227101");
        assertTrue(ShReportMapGeometry.rowMatchesAdcd(row, "621227"));
        assertTrue(ShReportMapGeometry.rowMatchesAdcd(row, "621227101"));
        assertFalse(ShReportMapGeometry.rowMatchesAdcd(row, "621221"));
    }

    @Test
    void convexHullSquareKeepsFourCorners() {
        List<double[]> pts = List.of(
                new double[]{0, 0},
                new double[]{1, 0},
                new double[]{1, 1},
                new double[]{0, 1},
                new double[]{0.5, 0.5}
        );
        List<double[]> hull = ShReportMapGeometry.convexHull(pts);
        assertEquals(4, hull.size());
        List<List<double[]>> rings = ShReportMapGeometry.hullOrBbox(pts);
        assertEquals(1, rings.size());
        assertTrue(rings.get(0).size() >= 5);
        double[] first = rings.get(0).get(0);
        double[] last = rings.get(0).get(rings.get(0).size() - 1);
        assertEquals(first[0], last[0], 1e-9);
        assertEquals(first[1], last[1], 1e-9);
    }

    @Test
    void singlePointFallsBackToClickableBbox() {
        List<List<double[]>> rings = ShReportMapGeometry.hullOrBbox(List.of(new double[]{105.0, 33.0}));
        assertEquals(1, rings.size());
        assertEquals(5, rings.get(0).size());
        double minLng = rings.get(0).stream().mapToDouble(p -> p[0]).min().orElse(0);
        assertTrue(minLng < 105.0);
    }

    @Test
    void emptyPointsYieldEmptyHull() {
        assertTrue(ShReportMapGeometry.hullOrBbox(List.of()).isEmpty());
        assertTrue(ShReportMapGeometry.convexHull(List.of()).isEmpty());
        assertNull(ShReportMapGeometry.bboxOf(List.of()));
    }

    @Test
    void sampleCapsAtMaxHullPoints() {
        List<double[]> pts = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            pts.add(new double[]{i, i});
        }
        assertEquals(ShReportMapGeometry.MAX_HULL_POINTS, ShReportMapGeometry.sample(pts).size());
    }
}
