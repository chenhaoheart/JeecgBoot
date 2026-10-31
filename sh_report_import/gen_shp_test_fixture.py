# -*- coding: utf-8 -*-
"""生成 ShpParser 单测固件：polygon(单环/双环)、polyline(单段/双段)、point，dbf 为 GBK。"""
import os
import zipfile

import shapefile

OUT = r"D:\github\JeecgBoot\jeecg-boot\jeecg-boot-module\jeecg-boot-module-airag\src\test\resources\shp"
os.makedirs(OUT, exist_ok=True)

# 1) 面：单环 Polygon / 双环（两部件）-> 解析为 MultiPolygon
w = shapefile.Writer(os.path.join(OUT, "test_polygon"), encoding="gbk", shapeType=shapefile.POLYGON)
w.field("WSWSCD", "C", 16)
w.field("WKMM", "C", 24)
w.field("WSAREA", "N", 12, 4)
w.field("涉及水库", "C", 20)
w.poly([[(104.0, 35.0), (104.2, 35.0), (104.2, 35.2), (104.0, 35.0)]])
w.record("WDA380012X200000", "杨家河", 123.4567, "巴家咀水库")
w.poly([[(105.0, 36.0), (105.2, 36.0), (105.2, 36.2), (105.0, 36.0)],
        [(105.5, 36.5), (105.6, 36.5), (105.6, 36.6), (105.5, 36.5)]])
w.record("WFD16307G0000000", "瓜子沟上游\n瓜子沟上游", 45.5, "")
w.close()

# 2) 线：单段 LineString / 双段 MultiLineString
w = shapefile.Writer(os.path.join(OUT, "test_line"), encoding="gbk", shapeType=shapefile.POLYLINE)
w.field("PID", "C", 16)
w.field("TYPES", "C", 4)
w.line([[(104.1, 35.1), (104.15, 35.12), (104.2, 35.15)]])
w.record("SEC001", "A")
w.line([[(105.1, 36.1), (105.12, 36.11)], [(105.2, 36.2), (105.25, 36.22)]])
w.record("SEC002", "B")
w.close()

# 3) 点
w = shapefile.Writer(os.path.join(OUT, "test_point"), encoding="gbk", shapeType=shapefile.POINT)
w.field("PID", "C", 16)
w.field("NAME", "C", 24)
w.point(104.05, 35.05)
w.record("HZ001", "跨沟桥梁")
w.close()

# 4) zip 包（shp + shx + dbf）
with zipfile.ZipFile(os.path.join(OUT, "test_polygon.zip"), "w") as z:
    for ext in (".shp", ".shx", ".dbf"):
        z.write(os.path.join(OUT, "test_polygon" + ext), "test_polygon" + ext)

print("fixtures written to", OUT)
