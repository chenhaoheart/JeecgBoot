# -*- coding: utf-8 -*-
"""把山洪报告流程的 SQL 数据源切到 PostgreSQL，LLM 全部改为火山方舟-agent。

幂等：可重复执行。改完后需重启后端（动态数据源 Redis 缓存 + LiteFlow 链）。
"""
import json
import pymysql

MYSQL = dict(host="127.0.0.1", port=3306, user="root", password="123456",
             database="jeecg-boot", charset="utf8mb4")

DS_ID = "1999990000000000001"
DS_CODE = "local_mysql2"
DS_NAME = "山洪报告数据源"
VOLC_ID = "2099900548148649986"
VOLC_MODEL = "ark-code-latest"

DS = {"dataSourceId": DS_ID, "dataSourceCode": DS_CODE, "dataSourceName": DS_NAME}

# 节点 id → PostgreSQL SQL（与 ShReportPgMapper 表/字段对齐）
PG_SQL = {
    "山洪报告_01流域概况": {
        "sql1": """SELECT COUNT(*) AS "防治对象总数", COALESCE(SUM(pcount),0) AS "总人口",
       COUNT(DISTINCT NULLIF(TRIM(town_name),'')) AS "乡镇数",
       COUNT(DISTINCT NULLIF(TRIM(rvnm),'')) AS "涉及河流数"
FROM sh_survey_object WHERE wscd='{{lybm}}'""",
        "sql2": """SELECT COALESCE(NULLIF(TRIM(ftp),''),'未分类') AS "类型", COUNT(*) AS "数量",
       COALESCE(SUM(pcount),0) AS "人口"
FROM sh_survey_object WHERE wscd='{{lybm}}' GROUP BY 1 ORDER BY 2 DESC""",
        "sql3": """SELECT COALESCE(MAX(county_name),'') AS "县区",
       COALESCE(NULLIF(TRIM(town_name),''),'未知') AS "乡镇",
       COUNT(*) AS "对象数", COALESCE(SUM(pcount),0) AS "人口"
FROM sh_survey_object WHERE wscd='{{lybm}}' GROUP BY 2 ORDER BY 4 DESC LIMIT 15""",
    },
    "山洪报告_02前期准备": {
        "sql1": """SELECT county_name AS "县区", town_name AS "乡镇", fnm AS "防治对象",
       COALESCE(NULLIF(TRIM(ftp),''),'未分类') AS "类型", pcount AS "人口",
       rvnm AS "所在河流", array_to_string(risk_flags, '、') AS "风险隐患要素",
       comments AS "隐患描述"
FROM sh_survey_object
WHERE wscd='{{lybm}}' AND risk_flags IS NOT NULL AND array_length(risk_flags,1) > 0
ORDER BY town_name LIMIT 100""",
    },
    "山洪报告_03外业调查成果": {
        "sql1": """SELECT stype AS "类型", COUNT(*) AS "数量",
       ROUND(AVG(r1),2) AS "平均R1", MAX(r1) AS "最大R1",
       SUM(CASE WHEN r1<=10 THEN 1 ELSE 0 END) AS "低阻水",
       SUM(CASE WHEN r1>10 AND r1<=30 THEN 1 ELSE 0 END) AS "中阻水",
       SUM(CASE WHEN r1>30 THEN 1 ELSE 0 END) AS "高阻水",
       SUM(CASE WHEN r1>80 THEN 1 ELSE 0 END) AS "极端阻水"
FROM (
  SELECT stype,
         CASE WHEN obstruction_area_ratio ~ '^[0-9]+([.][0-9]+)?$'
              THEN obstruction_area_ratio::numeric ELSE NULL END AS r1
  FROM sh_survey_road WHERE wscd='{{lybm}}'
) t GROUP BY stype""",
        "sql2": """SELECT name AS "名称", code AS "编码", stype AS "类型",
       channelw AS "沟宽", channeld AS "沟深", obstruction_area_ratio AS "阻水比R1",
       obstruction_storage_capacity AS "阻水库容",
       backwater_objname AS "壅水影响对象", overflow_objname AS "漫流影响对象",
       comments AS "备注"
FROM (
  SELECT name, code, stype, channelw, channeld, obstruction_area_ratio,
         obstruction_storage_capacity, backwater_objname, overflow_objname, comments,
         CASE WHEN obstruction_area_ratio ~ '^[0-9]+([.][0-9]+)?$'
              THEN obstruction_area_ratio::numeric ELSE NULL END AS r1
  FROM sh_survey_road WHERE wscd='{{lybm}}'
) t WHERE r1 > 30 ORDER BY r1 DESC LIMIT 20""",
        "sql3": """SELECT name AS "名称", code AS "编码", stype AS "类型",
       width AS "沟宽", deep AS "沟深", zsmj AS "占压面积",
       rvnm AS "所在河流", comments AS "备注"
FROM (
  SELECT name, code, stype, width, deep, zsmj, rvnm, comments,
         CASE WHEN zsmj ~ '^[0-9]+([.][0-9]+)?$' THEN zsmj::numeric ELSE NULL END AS z
  FROM sh_survey_gully WHERE wscd='{{lybm}}'
) t WHERE z > 0 ORDER BY z DESC LIMIT 20""",
    },
    "山洪报告_04影响分析": {
        "sql1": """SELECT name AS "名称", code AS "编码", bw AS "沟宽b", bd AS "沟深d", r1 AS "阻水比R1",
       ROUND(POWER(30*0.035/(NULLIF(bw,0)*SQRT(0.01)),0.6),2) AS "壅水高度m",
       ROUND(0.9*bw*POWER(bd,1.5),1) AS "溃决洪峰",
       backwater_objname AS "壅水影响对象", burst_objname AS "溃决影响对象",
       overflow_objname AS "漫流影响对象"
FROM (
  SELECT name, code, backwater_objname, burst_objname, overflow_objname,
         CASE WHEN channelw ~ '^[0-9]+([.][0-9]+)?$' THEN channelw::numeric ELSE NULL END AS bw,
         CASE WHEN channeld ~ '^[0-9]+([.][0-9]+)?$' THEN channeld::numeric ELSE NULL END AS bd,
         CASE WHEN obstruction_area_ratio ~ '^[0-9]+([.][0-9]+)?$'
              THEN obstruction_area_ratio::numeric ELSE NULL END AS r1
  FROM sh_survey_road WHERE wscd='{{lybm}}'
) t WHERE r1 > 10 AND bw > 0 AND bd > 0 ORDER BY r1 DESC LIMIT 50""",
        "sql2": """SELECT COUNT(*) AS "计算对象数",
       SUM(CASE WHEN backwater_objname IS NOT NULL AND backwater_objname<>'' THEN 1 ELSE 0 END) AS "有壅水影响",
       SUM(CASE WHEN burst_objname IS NOT NULL AND burst_objname<>'' THEN 1 ELSE 0 END) AS "有溃决影响",
       SUM(CASE WHEN overflow_objname IS NOT NULL AND overflow_objname<>'' THEN 1 ELSE 0 END) AS "有漫流影响",
       MAX(ROUND(POWER(30*0.035/(NULLIF(bw,0)*SQRT(0.01)),0.6),2)) AS "最大壅水高度"
FROM (
  SELECT backwater_objname, burst_objname, overflow_objname,
         CASE WHEN channelw ~ '^[0-9]+([.][0-9]+)?$' THEN channelw::numeric ELSE NULL END AS bw,
         CASE WHEN channeld ~ '^[0-9]+([.][0-9]+)?$' THEN channeld::numeric ELSE NULL END AS bd,
         CASE WHEN obstruction_area_ratio ~ '^[0-9]+([.][0-9]+)?$'
              THEN obstruction_area_ratio::numeric ELSE NULL END AS r1
  FROM sh_survey_road WHERE wscd='{{lybm}}'
) t WHERE r1 > 10 AND bw > 0 AND bd > 0""",
    },
    "山洪报告_06防治区分析": {
        "sql1": """SELECT COALESCE(MAX(county_name),'') AS "县区",
       COALESCE(NULLIF(TRIM(town_name),''),'未知') AS "乡镇",
       COUNT(*) AS "对象数", COALESCE(SUM(pcount),0) AS "人口"
FROM sh_survey_object WHERE wscd='{{lybm}}' GROUP BY 2 ORDER BY 4 DESC""",
        "sql2": """SELECT fnm AS "对象名称", county_name AS "县区", town_name AS "乡镇",
       COALESCE(NULLIF(TRIM(ftp),''),'未分类') AS "类型", pcount AS "人口", rvnm AS "所在河流",
       CASE WHEN risk_flags @> ARRAY['壅水']::text[] THEN '是' ELSE '否' END AS "壅水",
       CASE WHEN risk_flags @> ARRAY['漫流']::text[] THEN '是' ELSE '否' END AS "漫流"
FROM sh_survey_object
WHERE wscd='{{lybm}}' AND COALESCE(pcount,0) > 300
ORDER BY pcount DESC LIMIT 30""",
    },
}


def patch_sql_node(node, flow_name):
    opts = node.setdefault("properties", {}).setdefault("options", {})
    sql = opts.get("sql")
    if not isinstance(sql, dict):
        sql = {}
        opts["sql"] = sql
    sql["dataSourceId"] = DS_ID
    sql["dataSourceCode"] = DS_CODE
    sql["dataSourceName"] = DS_NAME
    nid = node.get("id")
    repl = PG_SQL.get(flow_name, {}).get(nid)
    if repl:
        sql["outputSql"] = repl.strip()
        return "sql-rewritten"
    return "sql-ds-only"


def patch_llm_node(node):
    opts = node.setdefault("properties", {}).setdefault("options", {})
    model = opts.get("model")
    if not isinstance(model, dict):
        model = {}
        opts["model"] = model
    model["modeId"] = VOLC_ID
    model["modelId"] = VOLC_ID
    params = model.get("params")
    if not isinstance(params, dict):
        params = {}
        model["params"] = params
    params["model"] = VOLC_MODEL
    params.setdefault("temperature", 0.3)
    params.setdefault("timeout", 120)
    opts["modelId"] = VOLC_ID
    opts["modeId"] = VOLC_ID
    return True


def rebuild_catch_chain(design):
    """01/06/07 被设计器写成 WHEN(end-fb, THEN(...))，end-fb 会并行抢跑。统一 CATCH。"""
    nodes = design.get("nodes") or []
    types = {n.get("id"): n.get("type") for n in nodes}
    if "llm1" not in types or "end-fb" not in types or "end-node" not in types:
        return None
    sql_ids = [n.get("id") for n in nodes if n.get("type") == "sql"]
    sql_ids = sorted(sql_ids, key=lambda i: (len(i), i))
    parts = ["start.tag('start-node')"]
    parts += [f"sql.tag('{sid}')" for sid in sql_ids]
    inner = ", ".join(parts)
    return (
        "THEN(\n    "
        + inner
        + ",\n    CATCH(THEN(llm.tag('llm1'),end.tag('end-node'))).DO(end.tag('end-fb'))\n"
        + ").tag(\"start-node\")"
    )


def update_datasource(cur):
    cur.execute("SELECT db_password FROM sys_data_source WHERE id=%s", (DS_ID,))
    row = cur.fetchone()
    if not row:
        raise SystemExit("missing sys_data_source " + DS_ID)
    pwd = row[0]
    cur.execute(
        """UPDATE sys_data_source SET
             db_type=%s, db_driver=%s, db_url=%s, db_name=%s, db_username=%s,
             db_password=%s, remark=%s, update_by=%s, update_time=NOW()
           WHERE id=%s""",
        (
            "6",
            "org.postgresql.Driver",
            "jdbc:postgresql://127.0.0.1:5432/shreport_map",
            "shreport_map",
            "postgres",
            pwd,
            "山洪报告 PostgreSQL（shreport_map），流程 SQL 节点专用",
            "admin",
            DS_ID,
        ),
    )
    print("updated sys_data_source", DS_ID, "-> postgresql shreport_map")


def patch_flow(cur, name, design_str, chain):
    design = json.loads(design_str)
    notes = []
    for n in design.get("nodes") or []:
        t = n.get("type")
        if t == "sql":
            notes.append(patch_sql_node(n, name))
        elif t == "llm":
            patch_llm_node(n)
            notes.append("llm-volc")
    new_chain = chain
    if "llm1" in {n.get("id") for n in design.get("nodes") or []}:
        rebuilt = rebuild_catch_chain(design)
        if rebuilt and (not chain or "WHEN(" in chain or "CATCH(" not in (chain or "")):
            new_chain = rebuilt
            notes.append("chain-catch")
        elif rebuilt and "WHEN(" in (chain or ""):
            new_chain = rebuilt
            notes.append("chain-catch")
    cur.execute(
        "UPDATE airag_flow SET design=%s, chain=%s WHERE name=%s",
        (json.dumps(design, ensure_ascii=False), new_chain, name),
    )
    print(name, ", ".join(notes) or "no-op")


def verify(cur):
    print("\n=== verify datasource ===")
    cur.execute(
        "SELECT code, db_type, db_driver, db_url, db_name, db_username FROM sys_data_source WHERE id=%s",
        (DS_ID,),
    )
    print(cur.fetchone())
    print("\n=== verify flows ===")
    cur.execute(
        "SELECT name, chain, design FROM airag_flow "
        "WHERE name LIKE '山洪报告_%' OR name LIKE '山洪灾害%' ORDER BY name"
    )
    for name, chain, design in cur.fetchall():
        d = json.loads(design)
        models = []
        dss = []
        tables = []
        for n in d.get("nodes") or []:
            if n.get("type") == "llm":
                m = ((n.get("properties") or {}).get("options") or {}).get("model") or {}
                models.append((m.get("modeId"), m.get("modelId"), (m.get("params") or {}).get("model")))
            if n.get("type") == "sql":
                sql = ((n.get("properties") or {}).get("options") or {}).get("sql") or {}
                dss.append(sql.get("dataSourceCode"))
                txt = sql.get("outputSql") or ""
                for t in ("prevention_object", "road_culvert_bridge_dam", "gully_occupy_area",
                          "sh_survey_object", "sh_survey_road", "sh_survey_gully"):
                    if t in txt:
                        tables.append(t)
        print(name)
        print("  chain CATCH" if "CATCH(" in (chain or "") else "  chain", (chain or "")[:80].replace("\n", " "))
        print("  llm", models)
        print("  ds", set(dss), "tables", set(tables))


def main():
    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    update_datasource(cur)
    cur.execute(
        "SELECT name, design, chain FROM airag_flow "
        "WHERE name LIKE '山洪报告_%' OR name LIKE '山洪灾害%'"
    )
    rows = cur.fetchall()
    for name, design, chain in rows:
        patch_flow(cur, name, design, chain)
    conn.commit()
    verify(cur)
    conn.close()
    print("\ndone. restart backend to drop Redis prefix_dynamic_db / LiteFlow cache.")


if __name__ == "__main__":
    main()
