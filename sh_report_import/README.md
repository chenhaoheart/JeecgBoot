# 山洪一张图空间数据导入（PostgreSQL，JSONB 存 GeoJSON）

将 126 个小流域的真实空间数据（shp 4 个图层 + 表格 6 张）导入本地
PostgreSQL，供"山洪一张图"页面使用。**无需 PostGIS**：几何以 GeoJSON
文本存 JSONB 列，外包矩形预算为 bbox_* 四列，供视野范围过滤。

## 数据 → 表对照

| 源文件 | 目标表 | 行数 |
|---|---|---|
| 126个小流域/126个小流域.shp | sh_map_watershed（流域面） | 126 |
| 空间数据/断面平面位置.geojson | sh_map_section（A 横断面/B 纵断面线） | ~7222 |
| 空间数据/防治对象.geojson | sh_map_prevention_area（防治对象面） | 1418 |
| 空间数据/隐患要素分布.geojson | sh_map_hazard_point（隐患点） | ~2326 |
| 风险隐患/1.山洪灾害防治对象名录.xlsx | sh_survey_object | 2056 |
| 风险隐患/2.跨沟道路、桥涵、塘坝调查成果表.xlsx | sh_survey_road | 1077 |
| 风险隐患/3.沟滩占地情况调查成果表.xlsx | sh_survey_gully | 153 |
| 河道横纵断面成果表/沟道横断面成果表.xlsx | sh_section_cross_point | ~250276 |
| 河道横纵断面成果表/沟道纵断面成果表.xlsx | sh_section_long_point | ~154261 |

## 使用步骤

1. 安装 PostgreSQL（任意版本，无需扩展）
2. 建库：`CREATE DATABASE shreport_map;`
3. 干跑检查（不连库，输出 import_report.txt）：
   ```
   python import_shmap.py --dry-run
   ```
4. 正式导入（幂等；`--rebuild` 先清空 9 张表）：
   ```
   python import_shmap.py --host 127.0.0.1 --port 5432 --db shreport_map --user postgres --password xxx
   ```
5. 后端 `application-dev.yml` 的 `shreport.map.pg.*` 改成对应连接信息，重启后端

依赖：`pip install pyshp openpyxl psycopg2-binary`

## 数据清洗规则

- 小流域编码统一大写，`GS`/`SW` 前缀剥离后匹配 126 基准编码；
  另有 68 条人工核定映射（MANUAL_WSCD_MAP，由 check_unmapped.py 空间包含核对
  生成，详见 mapping_proposal.txt）：杨家河流域 X2000xx 子编码、阿坞河
  WFD160000281EB00、瓜子沟 WFD1630W000000xx 系列等，全部 100% 落入基准流域面；
  重导后游离编码为 0（断面覆盖 124、防治覆盖 122、隐患覆盖 124 / 125 流域）
- 类型去空白/换行，半角括号转全角，同义归并：跨沟桥梁→跨沟桥涵、
  跨沟路涵/路涵道路→跨沟道路、临河滑坡体→临河滑坡、`企业\n企业`→企业
- 隐患点经纬度从 Point 几何提取；经纬度颠倒/重复的脏点置空坐标保留属性

## 注意

- 源坐标为 CGCS2000 经纬度，按 EPSG:4326 经纬度直接存 GeoJSON（Web 展示无需投影转换）
- `126个小流域.dbf` 为 GBK 编码，脚本已按 gbk 读取，其余图层用 UTF-8
- 断面线 `pid` ↔ 成果表 `断面编码` 关联；约 309 条断面只有成果无线要素（报告中已列出）
- MySQL 侧原 7 张调查表与 AI 报告流程不受影响
- 如日后安装 PostGIS，可将 geom 列升级为 geometry 类型获得空间索引与 ST_* 函数，
  表结构与页面无需变动
