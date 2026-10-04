# -*- coding: utf-8 -*-
"""一流域一报告重构：
- 章节子流程 start 增加 lybm(流域编码,必填)/lymc(流域名称,必填)；
- 所有 SQL 增加 WHERE wscd='{{lybm}}' 流域过滤（第1章数据源改为该流域防治对象聚合）；
- LLM 提示词注入流域名称；
- 总调度 start 增加 lybm/lymc 并传给各 subflow。
SQL 变量语法：'{{变量名}}'（带单引号，引擎正则 (?i:[NE])?'(%?)\\{\\{(.*?)}}(%?)'）。
"""
import json, pymysql, uuid, datetime

W, H = 332, 92
# 山洪报告数据源：sys_data_source id 不变，JDBC 已切到 PostgreSQL shreport_map（见 fix_pg_volc.py）
DS = {"dataSourceId": "1999990000000000001", "dataSourceCode": "local_mysql2", "dataSourceName": "山洪报告数据源"}
VOLC_ID = "2099900548148649986"
VOLC_MODEL = "ark-code-latest"

def node(id, t, x, y, text, options=None, ip=None, op=None):
    return {"id": id, "type": t, "x": x, "y": y,
            "properties": {"text": text, "remarks": "", "options": options or {},
                           "inputParams": ip or [], "outputParams": op or [], "width": W, "height": H}}

def sql(id, x, y, text, sqltext):
    o = {"sql": {"outputSql": sqltext, "dataSourceId": DS["dataSourceId"],
                 "dataSourceCode": DS["dataSourceCode"], "dataSourceName": DS["dataSourceName"]}}
    return node(id, "sql", x, y, text, options=o, op=[{"field": "body", "name": text, "type": "string"}])

SYS = ("你是山洪灾害风险隐患调查评价报告的编写专家，按照水利行业公文风格撰写报告章节：客观严谨、以数据为依据，使用简体中文。"
       "所有数字必须来自给定的统计数据，严禁编造或修改任何数值；表格使用 Markdown 表格语法输出；"
       "涉及具体隐患对象时保留其编码，并在该对象描述后附图片占位符【照片:编码】。"
       "报告针对单一小流域编写，行文中始终使用给定的流域名称。"
       "章标题必须使用一级 Markdown 标题（行首只有一个 #），例如「# 一、流域概况」；其下小节用二级（##）。不要把章标题写成 ##。")
# 第三章：照片只进表3-2单元格，不写在正文段落后
CH3_SYS = SYS.replace(
    "涉及具体隐患对象时保留其编码，并在该对象描述后附图片占位符【照片:编码】。",
    "涉及具体隐患对象时保留其编码。表3-2「高阻水桥涵坝调查表」必须增加「现场照片」列，"
    "该列每个数据单元格只写【照片:编码】，编码与该行编码列完全一致，每个编码只写一次。"
    "不要把占位符写在表格外的正文里。")

def meta(inputs):
    return json.dumps({"inputs": inputs,
                       "outputs": [{"field": "outputText", "type": "string", "nodeId": "end-node", "name": "章节内容"}]},
                      ensure_ascii=False)

def build(conn, name, descr, nodes, chain, inputs):
    nm = {n["id"]: n for n in nodes}
    edges = []
    # 顺序边：start→sqlN→llm1→end-node；end-fb 悬空（CATCH 目标）
    seq = ["start-node"] + [n["id"] for n in nodes if n["type"] == "sql"] + ["llm1", "end-node"]
    for a, b in zip(seq, seq[1:]):
        s, t = nm[a], nm[b]
        x1, y1 = s["x"] + W, s["y"] - 15
        x2, y2 = t["x"], t["y"] - 15
        mx = (x1 + x2) // 2
        edges.append({"id": f"e_{a}_{b}", "type": "base-edge", "sourceNodeId": a, "targetNodeId": b,
                      "sourceAnchorId": f"{a}_output", "targetAnchorId": f"{b}_input",
                      "pointsList": [{"x": x1, "y": y1}, {"x": mx, "y": y1}, {"x": mx, "y": y2}, {"x": x2, "y": y2}]})
    design = json.dumps({"nodes": nodes, "edges": edges}, ensure_ascii=False)
    cur = conn.cursor()
    cur.execute("SELECT id FROM airag_flow WHERE name=%s", (name,))
    row = cur.fetchone()
    if row:
        cur.execute("UPDATE airag_flow SET chain=%s, design=%s, metadata=%s, descr=%s WHERE id=%s",
                    (chain, design, meta(inputs), descr, row[0]))
        print("updated", name)
    else:
        fid = "19" + str(uuid.uuid4().int)[:17]
        cur.execute("""INSERT INTO airag_flow (id,create_by,create_time,sys_org_code,application_name,name,descr,chain,design,status,metadata)
          VALUES (%s,'admin',%s,'A04','jeecg',%s,%s,%s,%s,'enable',%s)""",
                    (fid, datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"), name, descr, chain, design, meta(inputs)))
        print("inserted", name)

conn = pymysql.connect(host="127.0.0.1", user="root", password="123456", database="jeecg-boot", charset="utf8mb4")

# 流域维度公共输入（每章都有）
def base_inputs(extra):
    return [{"field": "lybm", "name": "流域编码", "type": "string", "required": True},
            {"field": "lymc", "name": "流域名称", "type": "string", "required": True}] + extra + \
           [{"field": "batch_no", "name": "批次号", "type": "string", "required": False},
            {"field": "buochong", "name": "补充资料", "type": "string", "required": False}]

def llm_inputs(sql_names, extra_start=[]):
    ips = [{"field": "body", "name": n, "nodeId": sid, "type": "string"} for n, sid in sql_names]
    for f, n in extra_start:
        ips.append({"field": f, "name": n, "nodeId": "start-node", "type": "string"})
    ips.append({"field": "lymc", "name": "流域名称", "nodeId": "start-node", "type": "string"})
    ips.append({"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"})
    return ips

def end_nodes(default_text):
    return [
        node("end-node", "end", 1600, 380, "结束",
             options={"outputType": "text", "outputContent": "{{章节内容}}"},
             op=[{"field": "text", "name": "章节内容", "nodeId": "llm1", "type": "string"}]),
        node("end-fb", "end", 1600, 560, "默认内容(降级)",
             options={"outputType": "text", "outputContent": default_text}),
    ]

FB = "## 【系统默认内容】模型未配置或调用失败\n\n请配置大模型后重新生成本章节。"

# ============ 01 流域概况（数据源改为本流域防治对象聚合） ============
cfg = CH1 = dict(
    name="山洪报告_01流域概况",
    descr="按流域编码过滤防治对象数据，AI生成《流域概况》章节",
    start_ip=[{"field": "wsmc", "name": "沟道名称(可选)", "type": "string", "required": False}],
    sqls=[
        ("流域基本统计", """SELECT COUNT(*) AS "防治对象总数", COALESCE(SUM(pcount),0) AS "总人口", COUNT(DISTINCT NULLIF(TRIM(town_name),'')) AS "乡镇数", COUNT(DISTINCT NULLIF(TRIM(rvnm),'')) AS "涉及河流数" FROM sh_survey_object WHERE wscd='{{lybm}}'"""),
        ("对象类型统计", """SELECT COALESCE(NULLIF(TRIM(ftp),''),'未分类') AS "类型", COUNT(*) AS "数量", COALESCE(SUM(pcount),0) AS "人口" FROM sh_survey_object WHERE wscd='{{lybm}}' GROUP BY 1 ORDER BY 2 DESC"""),
        ("乡镇分布", """SELECT COALESCE(MAX(county_name),'') AS "县区", COALESCE(NULLIF(TRIM(town_name),''),'未知') AS "乡镇", COUNT(*) AS "对象数", COALESCE(SUM(pcount),0) AS "人口" FROM sh_survey_object WHERE wscd='{{lybm}}' GROUP BY 2 ORDER BY 4 DESC LIMIT 15"""),
    ],
    user=("请撰写流域《{{流域名称}}》山洪灾害风险隐患调查评价报告的章节《一、流域概况》：该流域防治对象总量与人口分布概述、"
          "乡镇与河流分布特征、防治对象类型构成分析。以 Markdown 表格嵌入 表1-1 流域基本统计表、表1-2 对象类型统计表、"
          "表1-3 乡镇分布表（数据与给定完全一致）。\n\n"
          "【流域基本统计】{{流域基本统计}}\n\n【对象类型统计】{{对象类型统计}}\n\n【乡镇分布】{{乡镇分布}}\n\n"
          "用户补充资料：{{补充资料}}"),
    llm_ips=[("流域基本统计", "sql1"), ("对象类型统计", "sql2"), ("乡镇分布", "sql3")],
    fb=FB + "\n\n一、流域概况（应包含：防治对象总量与人口、乡镇与河流分布、类型构成分析及表1-1~1-3）",
)
nodes = [node("start-node", "start", 200, 450, "开始", ip=base_inputs(cfg["start_ip"]))]
for i, (text, q) in enumerate(cfg["sqls"], 1):
    nodes.append(sql(f"sql{i}", 560, 300 + (i - 1) * 150, text, q))
nodes.append(node("llm1", "llm", 1200, 450, "LLM撰写",
                  options={"model": {"modeId": VOLC_ID, "modelId": VOLC_ID, "params": {"model": VOLC_MODEL, "temperature": 0.3, "timeout": 120}},
                           "history": 0,
                           "messages": [{"role": "system", "content": cfg.get("system", SYS)}, {"role": "user", "content": cfg["user"]}]},
                  ip=llm_inputs(cfg["llm_ips"]), op=[{"field": "text", "name": "章节内容", "type": "string"}]))
nodes += end_nodes(cfg["fb"])
types = {n["id"]: n["type"] for n in nodes}
head = ["start.tag('start-node')"] + [f"sql.tag('sql{i}')" for i in range(1, len(cfg["sqls"]) + 1)]
chain = f"THEN(\n    {', '.join(head)},\n    CATCH(THEN(llm.tag('llm1'),end.tag('end-node'))).DO(end.tag('end-fb'))\n).tag(\"start-node\")"
build(conn, cfg["name"], cfg["descr"], nodes, chain, base_inputs(cfg["start_ip"]))

# ============ 02 前期准备 ============
cfg = dict(
    name="山洪报告_02前期准备",
    descr="按流域过滤风险隐患排查数据，AI生成《前期准备》章节",
    start_ip=[],
    sqls=[("风险隐患排查清单", """SELECT county_name AS "县区", town_name AS "乡镇", fnm AS "防治对象", COALESCE(NULLIF(TRIM(ftp),''),'未分类') AS "类型", pcount AS "人口", rvnm AS "所在河流", array_to_string(risk_flags, '、') AS "风险隐患要素", comments AS "隐患描述" FROM sh_survey_object WHERE wscd='{{lybm}}' AND risk_flags IS NOT NULL AND array_length(risk_flags,1) > 0 ORDER BY town_name LIMIT 100""")],
    user=("请撰写流域《{{流域名称}}》报告的章节《二、前期准备》：该流域内业排查工作组织与技术路线概述、风险隐患要素排查情况统计（按乡镇分布）、主要隐患类型特征；"
          "以 Markdown 表格嵌入 表2-1 风险隐患要素内业排查清单（可精简列，数据保持一致）。\n\n"
          "【排查清单】{{风险隐患排查清单}}\n\n用户补充资料：{{补充资料}}"),
    llm_ips=[("风险隐患排查清单", "sql1")],
    fb=FB + "\n\n二、前期准备（应包含：排查组织与技术路线、隐患要素排查统计及表2-1）",
)
nodes = [node("start-node", "start", 200, 450, "开始", ip=base_inputs(cfg["start_ip"]))]
nodes.append(sql("sql1", 560, 450, cfg["sqls"][0][0], cfg["sqls"][0][1]))
nodes.append(node("llm1", "llm", 900, 450, "LLM撰写",
                  options={"model": {"modeId": VOLC_ID, "modelId": VOLC_ID, "params": {"model": VOLC_MODEL, "temperature": 0.3, "timeout": 120}},
                           "history": 0,
                           "messages": [{"role": "system", "content": cfg.get("system", SYS)}, {"role": "user", "content": cfg["user"]}]},
                  ip=llm_inputs(cfg["llm_ips"]), op=[{"field": "text", "name": "章节内容", "type": "string"}]))
nodes += end_nodes(cfg["fb"])
chain = "THEN(\n    start.tag('start-node'),\n    sql.tag('sql1'),\n    CATCH(THEN(llm.tag('llm1'),end.tag('end-node'))).DO(end.tag('end-fb'))\n).tag(\"start-node\")"
build(conn, cfg["name"], cfg["descr"], nodes, chain, base_inputs(cfg["start_ip"]))

# ============ 03 外业调查成果 ============
cfg = dict(
    name="山洪报告_03外业调查成果",
    descr="按流域过滤桥涵坝R1/沟滩占地数据，AI生成《外业调查成果》章节（含照片占位）",
    start_ip=[],
    sqls=[("桥涵坝R1分级统计", """SELECT stype AS "类型", COUNT(*) AS "数量", ROUND(AVG(r1),2) AS "平均R1", MAX(r1) AS "最大R1", SUM(CASE WHEN r1<=10 THEN 1 ELSE 0 END) AS "低阻水", SUM(CASE WHEN r1>10 AND r1<=30 THEN 1 ELSE 0 END) AS "中阻水", SUM(CASE WHEN r1>30 THEN 1 ELSE 0 END) AS "高阻水", SUM(CASE WHEN r1>80 THEN 1 ELSE 0 END) AS "极端阻水" FROM (SELECT stype, CASE WHEN obstruction_area_ratio ~ '^[0-9]+([.][0-9]+)?$' THEN obstruction_area_ratio::numeric ELSE NULL END AS r1 FROM sh_survey_road WHERE wscd='{{lybm}}') t GROUP BY stype"""),
          ("高阻水桥涵坝清单", """SELECT name AS "名称", code AS "编码", stype AS "类型", channelw AS "沟宽", channeld AS "沟深", obstruction_area_ratio AS "阻水比R1", obstruction_storage_capacity AS "阻水库容", backwater_objname AS "壅水影响对象", overflow_objname AS "漫流影响对象", comments AS "备注" FROM (SELECT name, code, stype, channelw, channeld, obstruction_area_ratio, obstruction_storage_capacity, backwater_objname, overflow_objname, comments, CASE WHEN obstruction_area_ratio ~ '^[0-9]+([.][0-9]+)?$' THEN obstruction_area_ratio::numeric ELSE NULL END AS r1 FROM sh_survey_road WHERE wscd='{{lybm}}') t WHERE r1>30 ORDER BY r1 DESC LIMIT 20"""),
          ("沟滩占地清单", """SELECT name AS "名称", code AS "编码", stype AS "类型", width AS "沟宽", deep AS "沟深", zsmj AS "占压面积", rvnm AS "所在河流", comments AS "备注" FROM (SELECT name, code, stype, width, deep, zsmj, rvnm, comments, CASE WHEN zsmj ~ '^[0-9]+([.][0-9]+)?$' THEN zsmj::numeric ELSE NULL END AS z FROM sh_survey_gully WHERE wscd='{{lybm}}') t WHERE z>0 ORDER BY z DESC LIMIT 20""")],
    user=("请撰写流域《{{流域名称}}》报告的章节《三、外业调查成果》：3.1 涉水工程调查成果（R1阻水面积比分级：低≤10%、中10-30%、高>30%、极端>80%）；"
          "3.2 沟滩占地调查成果（占压面积大于70平方米为严重占压）；3.3 重点关注对象分析。"
          "以 Markdown 表格嵌入 表3-1 桥涵坝阻水分级统计表、表3-2 高阻水桥涵坝调查表、表3-3 沟滩占地调查表（数据与给定一致）。"
          "表3-2 必须包含列「现场照片」，每个数据单元格只写【照片:编码】，编码取该行「编码」列，每个编码只写一次，不要写在表格外面。\n\n"
          "【R1分级统计】{{桥涵坝R1分级统计}}\n\n【高阻水桥涵坝清单】{{高阻水桥涵坝清单}}\n\n【沟滩占地清单】{{沟滩占地清单}}\n\n用户补充资料：{{补充资料}}"),
    system=CH3_SYS,
    llm_ips=[("桥涵坝R1分级统计", "sql1"), ("高阻水桥涵坝清单", "sql2"), ("沟滩占地清单", "sql3")],
    fb=FB + "\n\n三、外业调查成果（应包含：桥涵坝R1分级、沟滩占地、重点关注对象分析及表3-1~3-3）",
)
nodes = [node("start-node", "start", 200, 450, "开始", ip=base_inputs(cfg["start_ip"]))]
for i, (text, q) in enumerate(cfg["sqls"], 1):
    nodes.append(sql(f"sql{i}", 560, 300 + (i - 1) * 150, text, q))
nodes.append(node("llm1", "llm", 1200, 450, "LLM撰写",
                  options={"model": {"modeId": VOLC_ID, "modelId": VOLC_ID, "params": {"model": VOLC_MODEL, "temperature": 0.3, "timeout": 120}},
                           "history": 0,
                           "messages": [{"role": "system", "content": cfg.get("system", SYS)}, {"role": "user", "content": cfg["user"]}]},
                  ip=llm_inputs(cfg["llm_ips"]), op=[{"field": "text", "name": "章节内容", "type": "string"}]))
nodes += end_nodes(cfg["fb"])
types = {n["id"]: n["type"] for n in nodes}
head = ["start.tag('start-node')"] + [f"sql.tag('sql{i}')" for i in range(1, len(cfg["sqls"]) + 1)]
chain = f"THEN(\n    {', '.join(head)},\n    CATCH(THEN(llm.tag('llm1'),end.tag('end-node'))).DO(end.tag('end-fb'))\n).tag(\"start-node\")"
build(conn, cfg["name"], cfg["descr"], nodes, chain, base_inputs(cfg["start_ip"]))

# ============ 04 影响分析 ============
cfg = dict(
    name="山洪报告_04影响分析",
    descr="按流域过滤桥涵坝参数并SQL内完成水文计算，AI生成《影响分析》章节",
    start_ip=[{"field": "flow_q", "name": "设计洪峰流量", "type": "string", "required": False},
              {"field": "cv", "name": "泥石流体积浓度", "type": "string", "required": False}],
    sqls=[("壅水溃决计算成果", """SELECT name AS "名称", code AS "编码", bw AS "沟宽b", bd AS "沟深d", r1 AS "阻水比R1", ROUND(POWER(30*0.035/(NULLIF(bw,0)*SQRT(0.01)),0.6),2) AS "壅水高度m", ROUND(0.9*bw*POWER(bd,1.5),1) AS "溃决洪峰", backwater_objname AS "壅水影响对象", burst_objname AS "溃决影响对象", overflow_objname AS "漫流影响对象" FROM (SELECT name, code, backwater_objname, burst_objname, overflow_objname, CASE WHEN channelw ~ '^[0-9]+([.][0-9]+)?$' THEN channelw::numeric ELSE NULL END AS bw, CASE WHEN channeld ~ '^[0-9]+([.][0-9]+)?$' THEN channeld::numeric ELSE NULL END AS bd, CASE WHEN obstruction_area_ratio ~ '^[0-9]+([.][0-9]+)?$' THEN obstruction_area_ratio::numeric ELSE NULL END AS r1 FROM sh_survey_road WHERE wscd='{{lybm}}') t WHERE r1>10 AND bw>0 AND bd>0 ORDER BY r1 DESC LIMIT 50"""),
          ("影响对象统计", """SELECT COUNT(*) AS "计算对象数", SUM(CASE WHEN backwater_objname IS NOT NULL AND backwater_objname<>'' THEN 1 ELSE 0 END) AS "有壅水影响", SUM(CASE WHEN burst_objname IS NOT NULL AND burst_objname<>'' THEN 1 ELSE 0 END) AS "有溃决影响", SUM(CASE WHEN overflow_objname IS NOT NULL AND overflow_objname<>'' THEN 1 ELSE 0 END) AS "有漫流影响", MAX(ROUND(POWER(30*0.035/(NULLIF(bw,0)*SQRT(0.01)),0.6),2)) AS "最大壅水高度" FROM (SELECT backwater_objname, burst_objname, overflow_objname, CASE WHEN channelw ~ '^[0-9]+([.][0-9]+)?$' THEN channelw::numeric ELSE NULL END AS bw, CASE WHEN channeld ~ '^[0-9]+([.][0-9]+)?$' THEN channeld::numeric ELSE NULL END AS bd, CASE WHEN obstruction_area_ratio ~ '^[0-9]+([.][0-9]+)?$' THEN obstruction_area_ratio::numeric ELSE NULL END AS r1 FROM sh_survey_road WHERE wscd='{{lybm}}') t WHERE r1>10 AND bw>0 AND bd>0""")],
    user=("请撰写流域《{{流域名称}}》报告的章节《四、影响分析》：4.1 分析方法概述（说明假设：设计洪峰流量Q取30立方米每秒、糙率n=0.035、比降S=0.01；"
          "壅水高度公式h=(Q·n/(b·√S))^0.6；溃坝洪峰公式Qmax=0.9·b·d^1.5）；4.2 壅水影响分析；4.3 溃决影响分析；4.4 改道漫流影响分析；"
          "4.5 综合风险评价。以 Markdown 表格嵌入 表4-1 影响分析计算成果表（数据与给定一致），重点对象保留编码并附【照片:编码】占位符。\n\n"
          "【计算成果】{{壅水溃决计算成果}}\n\n【影响对象统计】{{影响对象统计}}\n\n用户补充资料：{{补充资料}}"),
    llm_ips=[("壅水溃决计算成果", "sql1"), ("影响对象统计", "sql2")],
    fb=FB + "\n\n四、影响分析（应包含：方法概述、壅水/溃决/漫流分析及表4-1）",
)
nodes = [node("start-node", "start", 200, 450, "开始", ip=base_inputs(cfg["start_ip"]))]
for i, (text, q) in enumerate(cfg["sqls"], 1):
    nodes.append(sql(f"sql{i}", 560, 330 + (i - 1) * 150, text, q))
nodes.append(node("llm1", "llm", 1200, 450, "LLM撰写",
                  options={"model": {"modeId": VOLC_ID, "modelId": VOLC_ID, "params": {"model": VOLC_MODEL, "temperature": 0.3, "timeout": 120}},
                           "history": 0,
                           "messages": [{"role": "system", "content": cfg.get("system", SYS)}, {"role": "user", "content": cfg["user"]}]},
                  ip=llm_inputs(cfg["llm_ips"], [("flow_q", "设计洪峰流量"), ("cv", "泥石流体积浓度")]), op=[{"field": "text", "name": "章节内容", "type": "string"}]))
nodes += end_nodes(cfg["fb"])
head = ["start.tag('start-node')"] + [f"sql.tag('sql{i}')" for i in range(1, len(cfg["sqls"]) + 1)]
chain = f"THEN(\n    {', '.join(head)},\n    CATCH(THEN(llm.tag('llm1'),end.tag('end-node'))).DO(end.tag('end-fb'))\n).tag(\"start-node\")"
build(conn, cfg["name"], cfg["descr"], nodes, chain, base_inputs(cfg["start_ip"]))

# ============ 05 历史灾害（纯LLM+流域名） ============
cfg = dict(
    name="山洪报告_05历史灾害",
    descr="基于输入的历史灾害/洪痕入户资料，AI生成流域《历史山洪灾害情况》章节",
    start_ip=[{"field": "lishijilv", "name": "历史灾害记录", "type": "string", "required": False},
              {"field": "honghen", "name": "洪痕入户调查", "type": "string", "required": False}],
    sqls=[],
    user=("请撰写流域《{{流域名称}}》报告的章节《五、历史山洪灾害情况》。若下方提供了历史灾害记录或洪痕入户调查资料，依据资料撰写："
          "5.1 灾害记录统计（按年代时间序列归纳趋势）；5.2 严重程度分级（特大型≥10人死亡或≥1亿元；大型3-9人死亡或1000万-1亿元；"
          "中型1-2人死亡或100-1000万元；小型其余）；5.3 历史洪痕与入户调查情况；5.4 灾害演变规律分析。"
          "若资料为空，则撰写章节框架并说明需补充历史灾害调查资料。不得虚构具体数据。\n\n"
          "历史灾害记录：{{历史灾害记录}}\n\n洪痕入户调查：{{洪痕入户调查}}\n\n用户补充资料：{{补充资料}}"),
    llm_ips=[],
    fb=FB + "\n\n五、历史山洪灾害情况（应包含：灾害记录统计、严重程度分级、洪痕与入户调查、演变规律）",
)
nodes = [node("start-node", "start", 200, 450, "开始", ip=base_inputs(cfg["start_ip"]))]
ips = [{"field": "lishijilv", "name": "历史灾害记录", "nodeId": "start-node", "type": "string"},
       {"field": "honghen", "name": "洪痕入户调查", "nodeId": "start-node", "type": "string"},
       {"field": "lymc", "name": "流域名称", "nodeId": "start-node", "type": "string"},
       {"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"}]
nodes.append(node("llm1", "llm", 800, 450, "LLM撰写",
                  options={"model": {"modeId": VOLC_ID, "modelId": VOLC_ID, "params": {"model": VOLC_MODEL, "temperature": 0.3, "timeout": 120}},
                           "history": 0,
                           "messages": [{"role": "system", "content": cfg.get("system", SYS)}, {"role": "user", "content": cfg["user"]}]},
                  ip=ips, op=[{"field": "text", "name": "章节内容", "type": "string"}]))
nodes += end_nodes(cfg["fb"])
chain = "THEN(\n    start.tag('start-node'),\n    CATCH(THEN(llm.tag('llm1'),end.tag('end-node'))).DO(end.tag('end-fb'))\n).tag(\"start-node\")"
build(conn, cfg["name"], cfg["descr"], nodes, chain, base_inputs(cfg["start_ip"]))

# ============ 06 防治区分析 ============
cfg = dict(
    name="山洪报告_06防治区分析",
    descr="按流域过滤防治对象统计数据，AI生成《防治区分析》章节",
    start_ip=[],
    sqls=[("乡镇防治对象统计", """SELECT COALESCE(MAX(county_name),'') AS "县区", COALESCE(NULLIF(TRIM(town_name),''),'未知') AS "乡镇", COUNT(*) AS "对象数", COALESCE(SUM(pcount),0) AS "人口" FROM sh_survey_object WHERE wscd='{{lybm}}' GROUP BY 2 ORDER BY 4 DESC"""),
          ("重点防治对象清单", """SELECT fnm AS "对象名称", county_name AS "县区", town_name AS "乡镇", COALESCE(NULLIF(TRIM(ftp),''),'未分类') AS "类型", pcount AS "人口", rvnm AS "所在河流", CASE WHEN risk_flags @> ARRAY['壅水']::text[] THEN '是' ELSE '否' END AS "壅水", CASE WHEN risk_flags @> ARRAY['漫流']::text[] THEN '是' ELSE '否' END AS "漫流" FROM sh_survey_object WHERE wscd='{{lybm}}' AND COALESCE(pcount,0)>300 ORDER BY pcount DESC LIMIT 30""")],
    user=("请撰写流域《{{流域名称}}》报告的章节《六、防治区分析》：6.1 防治区基本情况；6.2 重点防治对象分析（人口>300人，超500人为极重点）；"
          "6.3 防治能力评估（结合壅水/漫流隐患分布定性评价监测预警与避险转移能力薄弱环节）；6.4 防治对策建议。"
          "以 Markdown 表格嵌入 表6-1 乡镇防治对象统计表、表6-2 重点防治对象表（数据与给定一致），重大隐患对象附【照片:对象名称】占位符。\n\n"
          "【乡镇统计】{{乡镇防治对象统计}}\n\n【重点对象清单】{{重点防治对象清单}}\n\n用户补充资料：{{补充资料}}"),
    llm_ips=[("乡镇防治对象统计", "sql1"), ("重点防治对象清单", "sql2")],
    fb=FB + "\n\n六、防治区分析（应包含：基本情况、重点对象分析、能力评估及表6-1/6-2）",
)
nodes = [node("start-node", "start", 200, 450, "开始", ip=base_inputs(cfg["start_ip"]))]
for i, (text, q) in enumerate(cfg["sqls"], 1):
    nodes.append(sql(f"sql{i}", 560, 330 + (i - 1) * 150, text, q))
nodes.append(node("llm1", "llm", 1200, 450, "LLM撰写",
                  options={"model": {"modeId": VOLC_ID, "modelId": VOLC_ID, "params": {"model": VOLC_MODEL, "temperature": 0.3, "timeout": 120}},
                           "history": 0,
                           "messages": [{"role": "system", "content": cfg.get("system", SYS)}, {"role": "user", "content": cfg["user"]}]},
                  ip=llm_inputs(cfg["llm_ips"]), op=[{"field": "text", "name": "章节内容", "type": "string"}]))
nodes += end_nodes(cfg["fb"])
head = ["start.tag('start-node')"] + [f"sql.tag('sql{i}')" for i in range(1, len(cfg["sqls"]) + 1)]
chain = f"THEN(\n    {', '.join(head)},\n    CATCH(THEN(llm.tag('llm1'),end.tag('end-node'))).DO(end.tag('end-fb'))\n).tag(\"start-node\")"
build(conn, cfg["name"], cfg["descr"], nodes, chain, base_inputs(cfg["start_ip"]))

# ============ 07 成果与建议 ============
cfg = dict(
    name="山洪报告_07成果与建议",
    descr="汇总前六章内容，AI生成流域《成果与建议》章节",
    start_ip=[],
    sqls=[],
    user=("以下是流域《{{流域名称}}》山洪灾害风险隐患调查报告前六章的内容。请据此撰写《七、成果与建议》："
          "7.1 主要成果；7.2 隐患整治优先序（急需整治、近期整治、远期整治三类清单）；"
          "7.3 工程措施建议（桥涵坝：R1>50%拆除重建、30-50%拓宽改造、10-30%中期计划；沟滩占地：拆违-清障-疏浚-护岸）；"
          "7.4 监测预警能力提升建议；7.5 群测群防体系建设建议；7.6 分期实施路线图（Markdown 表格）。"
          "建议必须与前六章反映的隐患情况对应，可引用对象名称与编码。\n\n"
          "一、流域概况：{{ch1}}\n\n二、前期准备：{{ch2}}\n\n三、外业调查成果：{{ch3}}\n\n"
          "四、影响分析：{{ch4}}\n\n五、历史灾害：{{ch5}}\n\n六、防治区分析：{{ch6}}\n\n"
          "用户补充资料：{{补充资料}}"),
    llm_ips=[],
    fb=FB + "\n\n七、成果与建议（应包含：主要成果、整治优先序、工程措施、监测预警、群测群防、分期路线图）",
)
nodes = [node("start-node", "start", 200, 450, "开始", ip=base_inputs(cfg["start_ip"]) +
              [{"field": "ch1", "name": "流域概况内容", "type": "string", "required": False},
               {"field": "ch2", "name": "前期准备内容", "type": "string", "required": False},
               {"field": "ch3", "name": "外业调查内容", "type": "string", "required": False},
               {"field": "ch4", "name": "影响分析内容", "type": "string", "required": False},
               {"field": "ch5", "name": "历史灾害内容", "type": "string", "required": False},
               {"field": "ch6", "name": "防治区分析内容", "type": "string", "required": False}])]
ips = [{"field": f, "name": n, "nodeId": "start-node", "type": "string"} for f, n in
       [("ch1", "ch1"), ("ch2", "ch2"), ("ch3", "ch3"), ("ch4", "ch4"), ("ch5", "ch5"), ("ch6", "ch6")]]
ips += [{"field": "lymc", "name": "流域名称", "nodeId": "start-node", "type": "string"},
        {"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"}]
nodes.append(node("llm1", "llm", 800, 450, "LLM撰写",
                  options={"model": {"modeId": VOLC_ID, "modelId": VOLC_ID, "params": {"model": VOLC_MODEL, "temperature": 0.3, "timeout": 120}},
                           "history": 0,
                           "messages": [{"role": "system", "content": cfg.get("system", SYS)}, {"role": "user", "content": cfg["user"]}]},
                  ip=ips, op=[{"field": "text", "name": "章节内容", "type": "string"}]))
nodes += end_nodes(cfg["fb"])
chain = "THEN(\n    start.tag('start-node'),\n    CATCH(THEN(llm.tag('llm1'),end.tag('end-node'))).DO(end.tag('end-fb'))\n).tag(\"start-node\")"
inputs7 = base_inputs(cfg["start_ip"]) + [{"field": f"ch{i}", "name": f"第{i}章内容", "type": "string", "required": False} for i in range(1, 7)]
build(conn, cfg["name"], cfg["descr"], nodes, chain, inputs7)

conn.commit()

# ============ 总调度：lybm/lymc 必填并传递 ============
cur = conn.cursor()
cur.execute("SELECT id, design, metadata FROM airag_flow WHERE name='山洪灾害风险隐患调查报告自动生成'")
row = cur.fetchone()
if row:
    fid, design, metadata = row
    d = json.loads(design)
    for n in d["nodes"]:
        if n["type"] == "start":
            ips = n["properties"]["inputParams"]
            ips.insert(0, {"field": "lybm", "name": "流域编码", "type": "string", "required": True})
            ips.insert(1, {"field": "lymc", "name": "流域名称", "type": "string", "required": True})
        if n["type"] == "subflow":
            ips = n["properties"]["inputParams"]
            if not any(p["field"] == "lybm" for p in ips):
                ips.insert(0, {"field": "lybm", "name": "lybm", "nodeId": "start-node", "type": "string"})
            if not any(p["field"] == "lymc" for p in ips):
                ips.insert(1, {"field": "lymc", "name": "lymc", "nodeId": "start-node", "type": "string"})
    m = json.loads(metadata)
    if not any(i["field"] == "lybm" for i in m["inputs"]):
        m["inputs"].insert(0, {"field": "lybm", "name": "流域编码", "required": True, "type": "string"})
        m["inputs"].insert(1, {"field": "lymc", "name": "流域名称", "required": True, "type": "string"})
    cur.execute("UPDATE airag_flow SET design=%s, metadata=%s WHERE id=%s",
                (json.dumps(d, ensure_ascii=False), json.dumps(m, ensure_ascii=False), fid))
    print("master watershed params added")

# ============ 表加流域字段 ============
cur.execute("ALTER TABLE sh_report_chapter ADD COLUMN IF NOT EXISTS lybm varchar(64) DEFAULT NULL COMMENT '流域编码' AFTER chapter_no")
cur.execute("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='jeecg-boot' AND table_name='sh_report_chapter' AND column_name='lymc'")
if cur.fetchone()[0] == 0:
    cur.execute("ALTER TABLE sh_report_chapter ADD COLUMN lymc varchar(200) DEFAULT NULL COMMENT '流域名称' AFTER lybm")
conn.commit()
cur.execute("SHOW COLUMNS FROM sh_report_chapter")
print([r[0] for r in cur.fetchall()])
conn.close()
print("all done")
