package org.jeecg.modules.airag.onemap.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一张图几何工具：解析经纬度、政区前缀、凸包（Andrew 单调链）。不依赖 JTS。
 */
public final class ShReportMapGeometry {

    /** 单流域参与凸包的最大点数，超出则等距取样 */
    public static final int MAX_HULL_POINTS = 200;

    /** 单点流域外包半边长（度），约 500m 量级，保证可点选 */
    public static final double POINT_PAD = 0.004;

    private ShReportMapGeometry() {
    }

    public static Double parseCoord(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s) || "nan".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            double v = Double.parseDouble(s);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean validLngLat(Double lng, Double lat) {
        return lng != null && lat != null && lng >= -180 && lng <= 180 && lat >= -90 && lat <= 90;
    }

    /** 政区代码前缀匹配：空前缀视为不过滤 */
    public static boolean matchesAdcdPrefix(String adcd, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return true;
        }
        if (adcd == null) {
            return false;
        }
        return adcd.trim().startsWith(prefix.trim());
    }

    public static boolean rowMatchesAdcd(Map<String, Object> row, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return true;
        }
        String county = str(row.get("county_adcd"));
        String town = str(row.get("town_adcd"));
        String adcd = str(row.get("adcd"));
        return matchesAdcdPrefix(county, prefix)
                || matchesAdcdPrefix(town, prefix)
                || matchesAdcdPrefix(adcd, prefix);
    }

    public static List<double[]> sample(List<double[]> points) {
        if (points == null || points.size() <= MAX_HULL_POINTS) {
            return points == null ? List.of() : points;
        }
        List<double[]> out = new ArrayList<>(MAX_HULL_POINTS);
        int n = points.size();
        for (int i = 0; i < MAX_HULL_POINTS; i++) {
            out.add(points.get(i * (n - 1) / (MAX_HULL_POINTS - 1)));
        }
        return out;
    }

    /**
     * 计算凸包。不足 3 个独立点时退化为包围盒多边形。
     *
     * @return GeoJSON Polygon 坐标环 [ [lng,lat], ... ]（已闭合）
     */
    public static List<List<double[]>> hullOrBbox(List<double[]> rawPoints) {
        List<double[]> pts = sample(rawPoints == null ? List.of() : rawPoints);
        if (pts.isEmpty()) {
            return List.of();
        }
        List<double[]> hull = convexHull(pts);
        if (hull.size() >= 3) {
            List<double[]> ring = new ArrayList<>(hull);
            double[] first = ring.get(0);
            double[] last = ring.get(ring.size() - 1);
            if (first[0] != last[0] || first[1] != last[1]) {
                ring.add(new double[]{first[0], first[1]});
            }
            return List.of(ring);
        }
        return List.of(bboxRing(pts));
    }

    /** Andrew monotone chain。返回未闭合的凸包顶点（逆时针）。 */
    public static List<double[]> convexHull(List<double[]> points) {
        if (points == null || points.isEmpty()) {
            return List.of();
        }
        List<double[]> uniq = new ArrayList<>();
        for (double[] p : points) {
            boolean seen = false;
            for (double[] u : uniq) {
                if (Math.abs(u[0] - p[0]) < 1e-10 && Math.abs(u[1] - p[1]) < 1e-10) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                uniq.add(p);
            }
        }
        if (uniq.size() <= 2) {
            return uniq;
        }
        uniq.sort(Comparator.<double[]>comparingDouble(a -> a[0]).thenComparingDouble(a -> a[1]));
        List<double[]> lower = new ArrayList<>();
        for (double[] p : uniq) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0) {
                lower.remove(lower.size() - 1);
            }
            lower.add(p);
        }
        List<double[]> upper = new ArrayList<>();
        for (int i = uniq.size() - 1; i >= 0; i--) {
            double[] p = uniq.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0) {
                upper.remove(upper.size() - 1);
            }
            upper.add(p);
        }
        lower.remove(lower.size() - 1);
        upper.remove(upper.size() - 1);
        List<double[]> hull = new ArrayList<>(lower.size() + upper.size());
        hull.addAll(lower);
        hull.addAll(upper);
        return hull;
    }

    public static List<double[]> bboxRing(List<double[]> pts) {
        double minX = pts.get(0)[0], maxX = pts.get(0)[0];
        double minY = pts.get(0)[1], maxY = pts.get(0)[1];
        for (double[] p : pts) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        if (Math.abs(maxX - minX) < 1e-8) {
            minX -= POINT_PAD;
            maxX += POINT_PAD;
        }
        if (Math.abs(maxY - minY) < 1e-8) {
            minY -= POINT_PAD;
            maxY += POINT_PAD;
        }
        List<double[]> ring = new ArrayList<>(5);
        ring.add(new double[]{minX, minY});
        ring.add(new double[]{maxX, minY});
        ring.add(new double[]{maxX, maxY});
        ring.add(new double[]{minX, maxY});
        ring.add(new double[]{minX, minY});
        return ring;
    }

    public static Map<String, Object> bboxOf(List<double[]> pts) {
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        double minX = pts.get(0)[0], maxX = pts.get(0)[0];
        double minY = pts.get(0)[1], maxY = pts.get(0)[1];
        for (double[] p : pts) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("minLng", minX);
        box.put("minLat", minY);
        box.put("maxLng", maxX);
        box.put("maxLat", maxY);
        return box;
    }

    public static Map<String, Object> featureCollection(List<Map<String, Object>> features) {
        Map<String, Object> fc = new LinkedHashMap<>();
        fc.put("type", "FeatureCollection");
        fc.put("features", features == null ? Collections.emptyList() : features);
        return fc;
    }

    public static Map<String, Object> pointFeature(Map<String, Object> props, double lng, double lat) {
        Map<String, Object> geom = new LinkedHashMap<>();
        geom.put("type", "Point");
        geom.put("coordinates", List.of(lng, lat));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("properties", props);
        f.put("geometry", geom);
        return f;
    }

    public static Map<String, Object> polygonFeature(Map<String, Object> props, List<List<double[]>> rings) {
        List<List<List<Double>>> coords = new ArrayList<>();
        for (List<double[]> ring : rings) {
            List<List<Double>> r = new ArrayList<>();
            for (double[] p : ring) {
                r.add(List.of(p[0], p[1]));
            }
            coords.add(r);
        }
        Map<String, Object> geom = new LinkedHashMap<>();
        geom.put("type", "Polygon");
        geom.put("coordinates", coords);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("properties", props);
        f.put("geometry", geom);
        return f;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    public static String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    public static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
