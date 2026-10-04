-- =====================================================================
-- 山洪一张图 空间数据表（PostgreSQL + PostGIS）
-- 由 sh_report_import/import_shmap.py 幂等执行，本文件仅作参考备份。
-- 坐标系：源数据 CGCS2000 经纬度，统一按 EPSG:4326 入库。
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS postgis;

-- 1. 小流域面（126个，基准编码表）
CREATE TABLE IF NOT EXISTS sh_map_watershed (
  id            bigserial PRIMARY KEY,
  wscd          VARCHAR(32)  NOT NULL,          -- 小流域编码 WSWSCD
  wsnm          VARCHAR(255),                   -- 名称 WKMM/MC
  wsarea        NUMERIC,                        -- 面积 km2
  wsperi        NUMERIC,                        -- 周长 km
  wsslp         NUMERIC,                        -- 坡度
  center_lng    NUMERIC, center_lat NUMERIC, center_elv NUMERIC,
  outlet_lng    NUMERIC, outlet_lat NUMERIC, outlet_elv NUMERIC,
  max_elv       NUMERIC,
  river_name    VARCHAR(255),                   -- NAME
  alias_name    VARCHAR(255),                   -- 重命名(沟名)
  jizhen_count  INTEGER,                        -- 集镇个数
  village_count INTEGER,                        -- 防治村个数
  gully_count   INTEGER,                        -- 山洪沟道条数
  reservoirs    VARCHAR(255),                   -- 涉及水库
  attrs         JSONB,                          -- 全部原始属性
  geom          GEOMETRY(MultiPolygon, 4326)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sh_map_watershed_wscd ON sh_map_watershed (wscd);
CREATE INDEX IF NOT EXISTS idx_sh_map_watershed_geom ON sh_map_watershed USING GIST (geom);

-- 2. 断面平面位置线（横断面 A / 纵断面 B）
CREATE TABLE IF NOT EXISTS sh_map_section (
  id       bigserial PRIMARY KEY,
  pid      VARCHAR(64),                         -- 断面编码，关联两张断面成果表
  sec_type VARCHAR(4),                          -- A 横断面 / B 纵断面
  sec_name VARCHAR(255),
  rvname   VARCHAR(255),
  rvcd     VARCHAR(64),
  wscd     VARCHAR(32),                         -- 清洗后小流域编码（wswscd）
  geom     GEOMETRY(MultiLineString, 4326)
);
CREATE INDEX IF NOT EXISTS idx_sh_map_section_pid  ON sh_map_section (pid);
CREATE INDEX IF NOT EXISTS idx_sh_map_section_wscd ON sh_map_section (wscd);
CREATE INDEX IF NOT EXISTS idx_sh_map_section_type ON sh_map_section (sec_type);
CREATE INDEX IF NOT EXISTS idx_sh_map_section_geom ON sh_map_section USING GIST (geom);

-- 3. 防治对象面（村落/企业/集镇等）
CREATE TABLE IF NOT EXISTS sh_map_prevention_area (
  id       bigserial PRIMARY KEY,
  obj_pid  VARCHAR(64),                         -- 即名录中的防护对象代码 fcd
  obj_name VARCHAR(255),
  obj_type VARCHAR(32),                         -- 清洗后类型
  pcount   INTEGER,
  rvname   VARCHAR(255),
  rvcd     VARCHAR(64),
  wscd     VARCHAR(32),
  geom     GEOMETRY(MultiPolygon, 4326)
);
CREATE INDEX IF NOT EXISTS idx_sh_map_prevention_area_pid   ON sh_map_prevention_area (obj_pid);
CREATE INDEX IF NOT EXISTS idx_sh_map_prevention_area_wscd  ON sh_map_prevention_area (wscd);
CREATE INDEX IF NOT EXISTS idx_sh_map_prevention_area_type  ON sh_map_prevention_area (obj_type);
CREATE INDEX IF NOT EXISTS idx_sh_map_prevention_area_geom  ON sh_map_prevention_area USING GIST (geom);

-- 4. 隐患要素分布点（跨沟桥涵/泥石流/低洼地等 13 类）
CREATE TABLE IF NOT EXISTS sh_map_hazard_point (
  id     bigserial PRIMARY KEY,
  pid    VARCHAR(64),
  name   VARCHAR(255),
  hz_type VARCHAR(32),                          -- 清洗后类型
  rvname VARCHAR(255),
  rvcd   VARCHAR(64),
  wscd   VARCHAR(32),
  fcd    VARCHAR(64),                           -- 关联防治对象代码
  fnm    VARCHAR(255),                          -- 关联防护对象名称
  lgtd   NUMERIC,
  lttd   NUMERIC,
  geom   GEOMETRY(Point, 4326)
);
CREATE INDEX IF NOT EXISTS idx_sh_map_hazard_point_pid  ON sh_map_hazard_point (pid);
CREATE INDEX IF NOT EXISTS idx_sh_map_hazard_point_wscd ON sh_map_hazard_point (wscd);
CREATE INDEX IF NOT EXISTS idx_sh_map_hazard_point_type ON sh_map_hazard_point (hz_type);
CREATE INDEX IF NOT EXISTS idx_sh_map_hazard_point_fcd  ON sh_map_hazard_point (fcd);
CREATE INDEX IF NOT EXISTS idx_sh_map_hazard_point_geom ON sh_map_hazard_point USING GIST (geom);

-- 5. 山洪灾害防治对象名录（xlsx，供详情风险标志与政区筛选）
CREATE TABLE IF NOT EXISTS sh_survey_object (
  id          bigserial PRIMARY KEY,
  src_id      INTEGER,
  county_name VARCHAR(100), county_adcd VARCHAR(20),
  town_name   VARCHAR(100), town_adcd   VARCHAR(20),
  fnm         VARCHAR(255), fcd  VARCHAR(64), ftp VARCHAR(32),
  pcount      INTEGER,
  rvnm        VARCHAR(255), rvcd VARCHAR(64),
  wsnm        VARCHAR(255), wscd VARCHAR(32),
  lgtd NUMERIC, lttd NUMERIC,
  base_lgtd NUMERIC, base_lttd NUMERIC, elevation NUMERIC,
  river_section_name VARCHAR(255),
  comments    TEXT,
  risk_flags  TEXT[],                           -- 命中的风险类型中文标签
  attrs       JSONB
);
CREATE INDEX IF NOT EXISTS idx_sh_survey_object_fcd ON sh_survey_object (fcd);
CREATE INDEX IF NOT EXISTS idx_sh_survey_object_wscd ON sh_survey_object (wscd);

-- 6. 跨沟道路、桥涵、塘坝调查成果表
CREATE TABLE IF NOT EXISTS sh_survey_road (
  id          bigserial PRIMARY KEY,
  src_id      INTEGER,
  county_name VARCHAR(100), county_adcd VARCHAR(20),
  town_name   VARCHAR(100), town_adcd   VARCHAR(20),
  name        VARCHAR(255), code VARCHAR(64),
  lgtd NUMERIC, lttd NUMERIC,
  stype       VARCHAR(8),                       -- A/B/C
  channelw VARCHAR(50), channeld VARCHAR(50), csshpt VARCHAR(50),
  obstruction_area_ratio VARCHAR(50), obstruction_storage_capacity NUMERIC,
  rvnm VARCHAR(255), rv_code VARCHAR(64),
  backwater_objname VARCHAR(500), backwater_objcode VARCHAR(500),
  burst_objname VARCHAR(500), burst_objcode VARCHAR(500),
  overflow_objname VARCHAR(500), overflow_objcode VARCHAR(500),
  comments    TEXT,
  wsnm VARCHAR(255), wscd VARCHAR(32),
  attrs JSONB
);
CREATE INDEX IF NOT EXISTS idx_sh_survey_road_code ON sh_survey_road (code);
CREATE INDEX IF NOT EXISTS idx_sh_survey_road_wscd ON sh_survey_road (wscd);

-- 7. 沟滩占地情况调查成果表
CREATE TABLE IF NOT EXISTS sh_survey_gully (
  id          bigserial PRIMARY KEY,
  src_id      INTEGER,
  county_name VARCHAR(100), county_adcd VARCHAR(20),
  town_name   VARCHAR(100), town_adcd   VARCHAR(20),
  name        VARCHAR(255), code VARCHAR(64),
  lgtd NUMERIC, lttd NUMERIC,
  stype    VARCHAR(8),
  width  VARCHAR(50), deep VARCHAR(50), dmform VARCHAR(50), zsmj VARCHAR(50),
  rvnm   VARCHAR(255), rvcd VARCHAR(64),
  comments TEXT,
  wsnm VARCHAR(255), wscd VARCHAR(32),
  attrs JSONB
);
CREATE INDEX IF NOT EXISTS idx_sh_survey_gully_code ON sh_survey_gully (code);
CREATE INDEX IF NOT EXISTS idx_sh_survey_gully_wscd ON sh_survey_gully (wscd);

-- 8. 沟道横断面成果点（~25万行）
CREATE TABLE IF NOT EXISTS sh_section_cross_point (
  id       bigserial PRIMARY KEY,
  pid      VARCHAR(64) NOT NULL,                -- 断面编码
  point_no INTEGER,                             -- 垂线号
  point_desc VARCHAR(100),                     -- 断面特征点描述
  dist     NUMERIC,                             -- 起点距(m)
  elev     NUMERIC,                             -- 高程(m)
  lgtd     NUMERIC, lttd NUMERIC,
  roughness NUMERIC,                            -- 糙率
  rvcd     VARCHAR(64), adcd VARCHAR(32),
  wscd     VARCHAR(32), wsnm VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_sh_section_cross_pid  ON sh_section_cross_point (pid, point_no);
CREATE INDEX IF NOT EXISTS idx_sh_section_cross_wscd ON sh_section_cross_point (wscd);

-- 9. 沟道纵断面成果点（~15万行）
CREATE TABLE IF NOT EXISTS sh_section_long_point (
  id       bigserial PRIMARY KEY,
  pid      VARCHAR(64) NOT NULL,                -- 断面编码
  point_no INTEGER,
  point_desc VARCHAR(100),                     -- 测量点
  dist     NUMERIC,                             -- 距离(m)
  dist_dir NUMERIC,                             -- 量距方向(°)
  bed_elev   NUMERIC,                           -- 河底高程(m)
  water_elev NUMERIC,                           -- 水面高程(m)
  lgtd     NUMERIC, lttd NUMERIC,
  rvcd     VARCHAR(64), adcd VARCHAR(32),
  wscd     VARCHAR(32), wsnm VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_sh_section_long_pid  ON sh_section_long_point (pid, point_no);
CREATE INDEX IF NOT EXISTS idx_sh_section_long_wscd ON sh_section_long_point (wscd);
