# -*- coding: utf-8 -*-
# 历史脚本：早期用 SQL 聚合替代 code 节点。线上已切 PostgreSQL + 火山方舟，勿直接重跑覆盖 airag_flow。
import json, pymysql, uuid, datetime

W, H = 332, 92
DS = {"sql.dataSourceId": "1209779538310004737", "sql.dataSourceCode": "local_mysql", "sql.dataSourceName": "本地测试库"}

def node(id, t, x, y, text, options=None, ip=None, op=None):
    return {"id": id, "type": t, "x": x, "y": y,
            "properties": {"text": text, "remarks": "", "options": options or {},
                           "inputParams": ip or [], "outputParams": op or [], "width": W, "height": H}}

_sql_seq = [0]
def sql(x, y, text, sqltext):
    _sql_seq[0] += 1
    id = f"sql{_sql_seq[0]}"
    o = dict(DS); o["sql.outputSql"] = sqltext
    return node(id, "sql", x, y, text, options=o, op=[{"field": "out_" + id, "name": text, "type": "string"}])

def start(x, y, inputs):
    return node("start-node", "start", x, y, "开始", ip=inputs)

def end(x, y):
    return node("end-node", "end", x, y, "结束",
                options={"outputType": "text", "outputContent": "{{章节内容}}"},
                op=[{"field": "text", "name": "章节内容", "nodeId": "llm1", "type": "string"}])

def llm(id, x, y, text, ip, system, user):
    return node(id, "llm", x, y, text,
                options={"model": {"modeId": "", "params": {"model": "", "temperature": 0.3, "timeout": 60}},
                         "history": 0,
                         "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]},
                ip=ip, op=[{"field": "text", "name": "章节内容", "type": "string"}])

SYS = ("你是山洪灾害风险隐患调查评价报告的编写专家，按照水利行业公文风格撰写报告章节：客观严谨、以数据为依据，使用简体中文。"
       "所有数字必须来自给定的统计数据，严禁编造或修改任何数值；表格使用 Markdown 表格语法输出；"
       "涉及具体隐患对象时保留其编码，并在该对象描述后附图片占位符【照片:编码】。")
CH3_SYS = SYS.replace(
    "并在该对象描述后附图片占位符【照片:编码】。",
    "表3-2「高阻水桥涵坝调查表」增加「现场照片」列，单元格只写【照片:编码】，与该行编码一致；不要把占位符写在表格外。")

def meta(inputs):
    return json.dumps({"inputs": inputs,
                       "outputs": [{"field": "outputText", "type": "string", "nodeId": "end-node", "name": "章节内容"}]},
                      ensure_ascii=False)

def build(conn, name, descr, nodes, ids, inputs):
    nm = {n["id"]: n for n in nodes}
    edges = []
    for a, b in zip(ids, ids[1:]):
        s, t = nm[a], nm[b]
        x1, y1 = s["x"] + W, s["y"] + H // 2
        x2, y2 = t["x"], t["y"] + H // 2
        mx = (x1 + x2) // 2
        edges.append({"id": f"e_{a}_{b}", "type": "base-edge", "sourceNodeId": a, "targetNodeId": b,
                      "sourceAnchorId": f"{a}_output", "targetAnchorId": f"{b}_input",
                      "pointsList": [{"x": x1, "y": y1}, {"x": mx, "y": y1}, {"x": mx, "y": y2}, {"x": x2, "y": y2}]})
    design = json.dumps({"nodes": nodes, "edges": edges}, ensure_ascii=False)
    parts = ",\n    ".join(f"{i}.tag('{i}')" for i in ids)
    chain = f"THEN(\n    {parts}\n).tag(\"start-node\")"
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

BUO = [{"field": "buochong", "name": "补充资料", "type": "string", "required": False}]
conn = pymysql.connect(host="127.0.0.1", user="root", password="123456", database="jeecg-boot", charset="utf8mb4")

# ===== 01 流域概况 =====
_sql_seq[0] = 0
ids = ["start-node", "sql1", "sql2", "sql3", "llm1", "end-node"]
nodes = [
    start(200, 450, [{"field": "wsmc", "name": "流域名称", "type": "string", "required": False}] + BUO),
    sql(560, 380, "小流域基本信息",
        "SELECT name AS 流域名称, hecd AS 流域编码, channel AS 所在沟道, address AS 所在位置, baseele AS 沟口高程, "
        "baselgtd AS 经度, baselttd AS 纬度 FROM ia_m_hsurface ORDER BY name"),
    sql(560, 530, "防治对象类型统计",
        "SELECT ftp AS 类型, COUNT(*) AS 数量, SUM(pcount) AS 人口 FROM prevention_object GROUP BY ftp"),
    sql(560, 680, "县区防治对象分布",
        "SELECT county_name AS 县区, COUNT(*) AS 防治对象数, SUM(pcount) AS 人口 FROM prevention_object "
        "GROUP BY county_name ORDER BY 防治对象数 DESC"),
    llm("llm1", 1200, 450, "撰写流域概况",
        [{"field": "out_sql1", "name": "流域基本信息", "nodeId": "sql1", "type": "string"},
         {"field": "out_sql2", "name": "防治对象类型统计", "nodeId": "sql2", "type": "string"},
         {"field": "out_sql3", "name": "县区防治对象分布", "nodeId": "sql3", "type": "string"},
         {"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"}],
        SYS,
        "请撰写报告章节《一、流域概况》：小流域数量与分布概述、地形地貌与沟道特征（利用高程数据）、防治对象类型与人口分布分析；"
        "以 Markdown 表格嵌入 表1-1 小流域基本信息表、表1-2 防治对象类型统计表、表1-3 县区防治对象分布表（数据与给定一致）。\n\n"
        "【小流域基本信息】{{流域基本信息}}\n\n【防治对象类型统计】{{防治对象类型统计}}\n\n【县区防治对象分布】{{县区防治对象分布}}\n\n"
        "用户补充资料：{{补充资料}}"),
    end(1500, 450),
]
build(conn, "山洪报告_01流域概况", "读取小流域基本信息与防治对象名录，AI生成《一、流域概况》章节", nodes, ids,
      [{"field": "wsmc", "name": "流域名称", "required": False, "type": "string"},
       {"field": "buochong", "name": "补充资料", "required": False, "type": "string"}])

# ===== 02 前期准备 =====
_sql_seq[0] = 0
ids = ["start-node", "sql1", "llm1", "end-node"]
nodes = [
    start(200, 450, BUO),
    sql(560, 450, "风险隐患排查清单",
        "SELECT county_name AS 县区, town_name AS 乡镇, fnm AS 防治对象, ftp AS 类型, pcount AS 人口, rvnm AS 所在河流, "
        "risk_backwater AS 壅水, risk_overflow AS 漫流, risk_debris_flow AS 泥石流, risk_landslide AS 滑坡, comments AS 隐患描述 "
        "FROM prevention_object WHERE risk_backwater='是' OR risk_overflow='是' OR risk_debris_flow='是' OR risk_landslide='是' "
        "ORDER BY county_name, town_name LIMIT 100"),
    llm("llm1", 900, 450, "撰写前期准备",
        [{"field": "out_sql1", "name": "风险隐患排查清单", "nodeId": "sql1", "type": "string"},
         {"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"}],
        SYS,
        "请撰写报告章节《二、前期准备》：内业排查工作组织与技术路线概述、风险隐患要素排查情况统计（按乡镇分布分析）、主要隐患类型特征；"
        "以 Markdown 表格嵌入 表2-1 风险隐患要素内业排查清单（可精简列，数据保持一致）。\n\n"
        "【排查清单】{{风险隐患排查清单}}\n\n用户补充资料：{{补充资料}}"),
    end(1240, 450),
]
build(conn, "山洪报告_02前期准备", "读取风险隐患排查数据，AI生成《二、前期准备》章节", nodes, ids, BUO)

# ===== 03 外业调查成果 =====
_sql_seq[0] = 0
ids = ["start-node", "sql1", "sql2", "sql3", "llm1", "end-node"]
nodes = [
    start(200, 450, BUO),
    sql(560, 360, "桥涵坝R1分级统计",
        "SELECT type AS 类型, COUNT(*) AS 数量, ROUND(AVG(obstruction_area_ratio),2) AS 平均R1, MAX(obstruction_area_ratio) AS 最大R1, "
        "SUM(CASE WHEN obstruction_area_ratio<=10 THEN 1 ELSE 0 END) AS 低阻水, "
        "SUM(CASE WHEN obstruction_area_ratio>10 AND obstruction_area_ratio<=30 THEN 1 ELSE 0 END) AS 中阻水, "
        "SUM(CASE WHEN obstruction_area_ratio>30 THEN 1 ELSE 0 END) AS 高阻水, "
        "SUM(CASE WHEN obstruction_area_ratio>80 THEN 1 ELSE 0 END) AS 极端阻水 "
        "FROM road_culvert_bridge_dam GROUP BY type"),
    sql(560, 510, "高阻水桥涵坝清单",
        "SELECT name AS 名称, code AS 编码, type AS 类型, channelw AS 沟宽, channeld AS 沟深, obstruction_area_ratio AS 阻水比R1, "
        "obstruction_storage_capacity AS 阻水库容, backwater_objname AS 壅水影响对象, overflow_objname AS 漫流影响对象, comments AS 备注 "
        "FROM road_culvert_bridge_dam WHERE obstruction_area_ratio>30 ORDER BY obstruction_area_ratio DESC LIMIT 20"),
    sql(560, 660, "沟滩占地清单",
        "SELECT name AS 名称, code AS 编码, type AS 类型, width AS 沟宽, deep AS 沟深, zsmj AS 占压面积, rvnm AS 所在河流, comments AS 备注 "
        "FROM gully_occupy_area WHERE zsmj>0 ORDER BY zsmj DESC LIMIT 20"),
    llm("llm1", 1200, 450, "撰写外业调查成果",
        [{"field": "out_sql1", "name": "桥涵坝R1分级统计", "nodeId": "sql1", "type": "string"},
         {"field": "out_sql2", "name": "高阻水桥涵坝清单", "nodeId": "sql2", "type": "string"},
         {"field": "out_sql3", "name": "沟滩占地清单", "nodeId": "sql3", "type": "string"},
         {"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"}],
        CH3_SYS,
        "请撰写报告章节《三、外业调查成果》：3.1 涉水工程调查成果（R1阻水面积比分级：低≤10%、中10-30%、高>30%、极端>80%）；"
        "3.2 沟滩占地调查成果（占压面积大于70平方米为严重占压）；3.3 重点关注对象分析。"
        "以 Markdown 表格嵌入 表3-1 桥涵坝阻水分级统计表、表3-2 高阻水桥涵坝调查表、表3-3 沟滩占地调查表（数据与给定一致）。"
        "表3-2 必须包含列「现场照片」，每个数据单元格只写【照片:编码】，编码取该行「编码」列，不要写在表格外面。\n\n"
        "【R1分级统计】{{桥涵坝R1分级统计}}\n\n【高阻水桥涵坝清单】{{高阻水桥涵坝清单}}\n\n【沟滩占地清单】{{沟滩占地清单}}\n\n"
        "用户补充资料：{{补充资料}}"),
    end(1500, 450),
]
build(conn, "山洪报告_03外业调查成果", "读取桥涵坝R1/沟滩占地R2数据，AI生成《三、外业调查成果》章节（含照片占位）", nodes, ids, BUO)

# ===== 04 影响分析（公式在SQL内计算）=====
_sql_seq[0] = 0
ids = ["start-node", "sql1", "sql2", "llm1", "end-node"]
nodes = [
    start(200, 450, [{"field": "flow_q", "name": "设计洪峰流量", "type": "string", "required": False},
                     {"field": "cv", "name": "泥石流体积浓度", "type": "string", "required": False}] + BUO),
    sql(560, 380, "壅水溃决计算成果",
        "SELECT name AS 名称, code AS 编码, channelw AS 沟宽b, channeld AS 沟深d, obstruction_area_ratio AS 阻水比R1, "
        "ROUND(POW(30*0.035/(channelw*SQRT(0.01)),0.6),2) AS 壅水高度m, "
        "ROUND(0.9*channelw*POW(channeld,1.5),1) AS 溃决洪峰, "
        "backwater_objname AS 壅水影响对象, burst_objname AS 溃决影响对象, overflow_objname AS 漫流影响对象 "
        "FROM road_culvert_bridge_dam WHERE obstruction_area_ratio>10 AND channelw>0 AND channeld>0 "
        "ORDER BY obstruction_area_ratio DESC LIMIT 50"),
    sql(560, 530, "影响对象统计",
        "SELECT COUNT(*) AS 计算对象数, "
        "SUM(CASE WHEN backwater_objname IS NOT NULL AND backwater_objname<>'' THEN 1 ELSE 0 END) AS 有壅水影响, "
        "SUM(CASE WHEN burst_objname IS NOT NULL AND burst_objname<>'' THEN 1 ELSE 0 END) AS 有溃决影响, "
        "SUM(CASE WHEN overflow_objname IS NOT NULL AND overflow_objname<>'' THEN 1 ELSE 0 END) AS 有漫流影响, "
        "MAX(ROUND(POW(30*0.035/(channelw*SQRT(0.01)),0.6),2)) AS 最大壅水高度 "
        "FROM road_culvert_bridge_dam WHERE obstruction_area_ratio>10 AND channelw>0 AND channeld>0"),
    llm("llm1", 1200, 450, "撰写影响分析",
        [{"field": "out_sql1", "name": "壅水溃决计算成果", "nodeId": "sql1", "type": "string"},
         {"field": "out_sql2", "name": "影响对象统计", "nodeId": "sql2", "type": "string"},
         {"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"}],
        SYS,
        "请撰写报告章节《四、影响分析》：4.1 分析方法概述（说明假设：设计洪峰流量Q取30立方米每秒、糙率n=0.035、比降S=0.01；"
        "壅水高度公式h=(Q·n/(b·√S))^0.6；溃坝洪峰公式Qmax=0.9·b·d^1.5）；4.2 壅水影响分析；4.3 溃决影响分析；4.4 改道漫流影响分析；"
        "4.5 综合风险评价（结合R1与影响对象）。以 Markdown 表格嵌入 表4-1 影响分析计算成果表（数据与给定一致），重点对象保留编码并附【照片:编码】占位符。\n\n"
        "【计算成果】{{壅水溃决计算成果}}\n\n【影响对象统计】{{影响对象统计}}\n\n用户补充资料：{{补充资料}}"),
    end(1500, 450),
]
build(conn, "山洪报告_04影响分析", "SQL内完成壅水/溃决水文计算，AI生成《四、影响分析》章节", nodes, ids,
      [{"field": "flow_q", "name": "设计洪峰流量", "required": False, "type": "string"},
       {"field": "cv", "name": "泥石流体积浓度", "required": False, "type": "string"},
       {"field": "buochong", "name": "补充资料", "required": False, "type": "string"}])

# ===== 06 防治区分析 =====
_sql_seq[0] = 0
ids = ["start-node", "sql1", "sql2", "llm1", "end-node"]
nodes = [
    start(200, 450, BUO),
    sql(560, 380, "乡镇防治对象统计",
        "SELECT county_name AS 县区, town_name AS 乡镇, COUNT(*) AS 对象数, SUM(pcount) AS 人口 FROM prevention_object "
        "GROUP BY county_name, town_name ORDER BY 人口 DESC"),
    sql(560, 530, "重点防治对象清单",
        "SELECT fnm AS 对象名称, county_name AS 县区, town_name AS 乡镇, ftp AS 类型, pcount AS 人口, rvnm AS 所在河流, "
        "risk_backwater AS 壅水, risk_overflow AS 漫流 FROM prevention_object WHERE pcount>300 ORDER BY pcount DESC LIMIT 30"),
    llm("llm1", 1200, 450, "撰写防治区分析",
        [{"field": "out_sql1", "name": "乡镇防治对象统计", "nodeId": "sql1", "type": "string"},
         {"field": "out_sql2", "name": "重点防治对象清单", "nodeId": "sql2", "type": "string"},
         {"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"}],
        SYS,
        "请撰写报告章节《六、防治区分析》：6.1 防治区基本情况；6.2 重点防治对象分析（人口>300人，超500人为极重点）；"
        "6.3 防治能力评估（结合壅水/漫流隐患分布定性评价监测预警与避险转移能力薄弱环节）；6.4 防治对策建议。"
        "以 Markdown 表格嵌入 表6-1 乡镇防治对象统计表、表6-2 重点防治对象表（数据与给定一致），重大隐患对象附【照片:对象名称】占位符。\n\n"
        "【乡镇统计】{{乡镇防治对象统计}}\n\n【重点对象清单】{{重点防治对象清单}}\n\n用户补充资料：{{补充资料}}"),
    end(1500, 450),
]
build(conn, "山洪报告_06防治区分析", "读取防治对象统计数据，AI生成《六、防治区分析》章节", nodes, ids, BUO)

conn.commit()
conn.close()
print("done")
