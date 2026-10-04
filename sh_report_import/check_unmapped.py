# -*- coding: utf-8 -*-
"""
未映射小流域编码归属核对：对清洗后不在 126 基准编码内的编码，
用其要素点落在基准流域面内的空间包含关系做多数投票，输出映射建议。
只读分析，不改数据。结果写入 mapping_proposal.txt
"""
import json
import os
import sys

import psycopg2
from psycopg2.extras import execute_values

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from import_shmap import DEFAULT_DATA_DIR, norm_type, to_str, xlsx_rows  # noqa: E402

# 与 import_shmap 相同的编码清洗（不含基准映射）
import re


def raw_clean(raw):
    s = to_str(raw)
    return None if s is None else s.strip().upper()


def main():
    data_dir = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DATA_DIR
    conn = psycopg2.connect(host=os.environ.get("PGHOST", "127.0.0.1"), port=5432,
                            dbname=os.environ.get("PGDATABASE", "shreport_map"),
                            user=os.environ.get("PGUSER", "postgres"),
                            password=os.environ.get("PGPASSWORD", ""))
    cur = conn.cursor()
    cur.execute("SELECT wscd, wsnm, ST_AsGeoJSON(geom) FROM sh_map_watershed")
    basins = [(w, n, json.loads(g)) for w, n, g in cur.fetchall()]
    conn.close()

    def point_in_which(lng, lat):
        """返回 (命中流域计数dict)。点在面内的射线法。"""
        hits = {}
        for wscd, _, geom in basins:
            polys = geom["coordinates"] if geom["type"] == "MultiPolygon" else [geom["coordinates"]]
            for rings in polys:
                ring = rings[0]
                inside = False
                for i in range(len(ring) - 1):
                    x1, y1 = ring[i]; x2, y2 = ring[i + 1]
                    if (y1 > lat) != (y2 > lat) and lng < (x2 - x1) * (lat - y1) / (y2 - y1) + x1:
                        inside = not inside
                if inside:
                    hits[wscd] = hits.get(wscd, 0) + 1
                    break
        return hits

    # 收集各图层中每个原始编码的点
    code_points = {}
    gsec = json.load(open(os.path.join(data_dir, "空间数据", "断面平面位置.geojson"), encoding="utf-8"))
    gpre = json.load(open(os.path.join(data_dir, "空间数据", "防治对象.geojson"), encoding="utf-8"))
    ghz = json.load(open(os.path.join(data_dir, "空间数据", "隐患要素分布.geojson"), encoding="utf-8"))

    def add_pts(feats, wscd_field):
        for f in feats:
            code = raw_clean((f.get("properties") or {}).get(wscd_field))
            geom = f.get("geometry") or {}
            if not code or not geom.get("coordinates"):
                continue
            pts = code_points.setdefault(code, [])
            c = geom["coordinates"]
            if geom["type"] == "Point":
                pts.append(c)
            elif geom["type"] == "LineString":
                pts.extend(c[:: max(1, len(c) // 20)])
            elif geom["type"] == "Polygon":
                pts.extend(c[0][:: max(1, len(c[0]) // 20)])

    add_pts(gsec["features"], "wswscd")
    add_pts(gpre["features"], "wswscd")
    add_pts(ghz["features"], "wswscd")

    base_set = {w for w, _, _ in basins}
    lines = []
    lines.append("=" * 90)
    lines.append("未映射编码归属核对（空间多数投票：要素点落入基准流域面内的比例）")
    lines.append("=" * 90)
    mappings = {}
    for code in sorted(code_points):
        if code in base_set:
            continue
        # GS/SW 前缀剥离后再判断
        stripped = None
        for p in ("GS", "SW"):
            if code.startswith(p) and code[len(p):] in base_set:
                stripped = code[len(p):]
        pts = code_points[code]
        hits = {}
        for lng, lat in pts[:: max(1, len(pts) // 200)]:
            for w, n in point_in_which(lng, lat).items():
                hits[w] = hits.get(w, 0) + n
        total = sum(hits.values())
        ranked = sorted(hits.items(), key=lambda kv: -kv[1])
        if stripped:
            lines.append(f"\n{code}  (前缀剥离 -> {stripped})  点数 {len(pts)}")
            mappings[code] = stripped
        elif ranked and ranked[0][1] / max(total, 1) >= 0.9:
            w, n = ranked[0]
            pct = n / max(total, 1) * 100
            name = next(nm for wc, nm, _ in basins if wc == w)
            lines.append(f"\n{code}  点数 {len(pts)}  -> {w} ({pct:.0f}% 落入) {name}")
            mappings[code] = w
        else:
            top = ", ".join(f"{w}:{n}" for w, n in ranked[:3]) or "全部落在基准面之外"
            lines.append(f"\n{code}  点数 {len(pts)}  -> 无确定归属 [{top}]")

    lines.append("\n" + "=" * 90)
    lines.append(f"\n建议映射 {len(mappings)} 条：")
    for k, v in sorted(mappings.items()):
        lines.append(f'    "{k}": "{v}",')

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mapping_proposal.txt")
    open(out, "w", encoding="utf-8").write("\n".join(lines))
    print("\n".join(lines[-(len(mappings) + 3):]))
    print("\n完整报告:", out)


if __name__ == "__main__":
    main()
