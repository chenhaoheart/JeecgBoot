# -*- coding: utf-8 -*-
"""章节子流程改造（历史脚本，勿直接重跑覆盖线上）。

线上以 upgrade_watershed.py + fix_pg_volc.py 为准：SQL 走 PostgreSQL shreport_map，
LLM 为火山方舟-agent。本文件仅保留结构参考。
"""
import json, pymysql, uuid, datetime

W, H = 332, 92
# 与线上一致：id/code 不变，实际 JDBC 为 PostgreSQL shreport_map（fix_pg_volc.py）
DS = {"dataSourceId": "1999990000000000001", "dataSourceCode": "local_mysql2", "dataSourceName": "山洪报告数据源"}

def node(id, t, x, y, text, options=None, ip=None, op=None):
    return {"id": id, "type": t, "x": x, "y": y,
            "properties": {"text": text, "remarks": "", "options": options or {},
                           "inputParams": ip or [], "outputParams": op or [], "width": W, "height": H}}

def sql(x, y, text, sqltext, seq):
    o = {"sql": {"outputSql": sqltext, "dataSourceId": DS["dataSourceId"],
                 "dataSourceCode": DS["dataSourceCode"], "dataSourceName": DS["dataSourceName"]}}
    return node(f"sql{seq}", "sql", x, y, text, options=o, op=[{"field": f"out_sql{seq}", "name": text, "type": "string"}])

def llm(id, x, y, user):
    return node(id, "llm", x, y, "LLM撰写",
                options={"model": {"modeId": "2099900548148649986", "modelId": "2099900548148649986",
                                   "params": {"model": "ark-code-latest", "temperature": 0.3, "timeout": 120}},
                         "history": 0,
                         "messages": [{"role": "system", "content": SYS},
                                      {"role": "user", "content": user}]},
                ip=[{"field": "result", "name": "统计数据", "nodeId": "sql1", "type": "string"},
                    {"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"}],
                op=[{"field": "text", "name": "章节内容", "type": "string"}])

def save(bean, x, y):
    return node("save1", "enhanceJava", x, y, "章节落库",
                options={"enhance": {"type": "spring", "path": bean}},
                ip=[{"field": "text", "name": "章节内容", "nodeId": "llm1", "type": "string"},
                    {"field": "batch_no", "name": "批次号", "nodeId": "start-node", "type": "string"}],
                op=[{"field": "result", "name": "保存结果", "type": "string"}])

def end_node(id, content, y):
    if id == "end-node":
        return node(id, "end", 1600, y, "结束",
                    options={"outputType": "text", "outputContent": content},
                    op=[{"field": "text", "name": "章节内容", "nodeId": "llm1", "type": "string"}])
    return node(id, "end", 1600, y, "默认内容(降级)",
                options={"outputType": "text", "outputContent": content})

SYS = ("你是山洪灾害风险隐患调查评价报告的编写专家，按照水利行业公文风格撰写报告章节：客观严谨、以数据为依据，使用简体中文。"
       "所有数字必须来自给定的统计数据，严禁编造或修改任何数值；表格使用 Markdown 表格语法输出；"
       "涉及具体隐患对象时保留其编码，并在该对象描述后附图片占位符【照片:编码】。")

def chain(ids_normal, fb_id):
    parts = ",\n    ".join(ids_normal)
    return (f"THEN(\n    {parts},\n    "
            f"CATCH(THEN(llm.tag('llm1'),enhanceJava.tag('save1'),end.tag('end-node'))).DO(end.tag('{fb_id}'))\n"
            f").tag(\"start-node\")")

# 每章默认内容（模型不可用时的系统默认文案）
DEFAULTS = {
 1: ("## 【系统默认内容】模型未配置或调用失败，以下为本章节系统默认框架\n\n"
     "# 一、流域概况\n\n## 1.1 自然地理概况\n\n暂无调查数据。\n\n## 1.2 社会经济\n\n暂无调查数据。\n\n"
     "## 1.3 山洪灾害防御情况\n\n暂无调查数据。\n\n请配置大模型后重新生成本章节。"),
 2: ("## 【系统默认内容】模型未配置或调用失败\n\n二、前期准备\n\n本章应包含：内业排查工作组织与技术路线、"
     "风险隐患要素排查情况统计、主要隐患类型特征（含表2-1风险隐患要素内业排查清单）。请配置大模型后重新生成本章节。"),
 3: ("## 【系统默认内容】模型未配置或调用失败\n\n三、外业调查成果\n\n本章应包含：涉水工程（跨沟道路、桥涵、坝）调查成果"
     "（R1阻水面积比分级：低≤10%、中10-30%、高>30%、极端>80%）、沟滩占地调查成果（占压面积>70㎡为严重占压）、"
     "重点关注对象分析（含表3-1~3-3及照片引用）。请配置大模型后重新生成本章节。"),
 4: ("## 【系统默认内容】模型未配置或调用失败\n\n四、影响分析\n\n本章应包含：分析方法概述（壅水高度h=(Q·n/(b·√S))^0.6、"
     "溃坝洪峰Qmax=0.9·b·d^1.5）、壅水/溃决/漫流影响分析、综合风险评价（含表4-1影响分析计算成果表）。请配置大模型后重新生成本章节。"),
 5: ("## 【系统默认内容】模型未配置或调用失败\n\n五、历史山洪灾害情况\n\n本章应包含：灾害记录统计与年代趋势、严重程度分级、"
     "历史洪痕与入户调查、灾害演变规律。请提供历史灾害资料并配置大模型后重新生成本章节。"),
 6: ("## 【系统默认内容】模型未配置或调用失败\n\n六、防治区分析\n\n本章应包含：防治区基本情况、重点防治对象分析（人口>300人）、"
     "防治能力评估、防治对策建议（含表6-1、表6-2）。请配置大模型后重新生成本章节。"),
 7: ("## 【系统默认内容】模型未配置或调用失败\n\n七、成果与建议\n\n本章应包含：主要成果、隐患整治优先序、工程措施建议、"
     "监测预警能力提升、群测群防体系建设、分期实施路线图。请配置大模型后重新生成本章节。"),
}

conn = pymysql.connect(host="127.0.0.1", user="root", password="123456", database="jeecg-boot", charset="utf8mb4")
cur = conn.cursor()

CH = {
 1: dict(name="山洪报告_01流域概况", start_ip=[
             {"field": "wsmc", "name": "流域名称", "type": "string", "required": False},
             {"field": "sjbao", "name": "实测数据包", "type": "string", "required": False},
             {"field": "tigang", "name": "写作提纲", "type": "string", "required": False}],
         sqls=[("流域基本统计", "SELECT name AS 流域名称, hecd AS 流域编码, channel AS 所在沟道, address AS 所在位置, baseele AS 沟口高程, baselgtd AS 经度, baselttd AS 纬度 FROM ia_m_hsurface ORDER BY name"),
               ("对象类型统计", "SELECT ftp AS 类型, COUNT(*) AS 数量, SUM(pcount) AS 人口 FROM prevention_object GROUP BY ftp"),
               ("乡镇分布", "SELECT county_name AS 县区, COUNT(*) AS 防治对象数, SUM(pcount) AS 人口 FROM prevention_object GROUP BY county_name ORDER BY 防治对象数 DESC")],
         user=("请撰写流域《{{流域名称}}》山洪灾害风险隐患调查评价报告的章节《一、流域概况》。"
               "章标题写「# 一、流域概况」。本章必须写成下面三节，不能只写防治对象统计。"
               "没有给出的数字一律写「暂无调查数据」，不得编造。\n\n"
               "## 1.1 自然地理概况\n"
               "写本流域位置、所在水系、集水面积、周长、坡度、沟口高程、最高点高程、山洪沟道条数、集镇个数、防治村个数。"
               "这些数字只采用【实测数据包】里「流域基本信息」的对应列；数据包没有的气候、土壤质地、土地利用与植被，"
               "各用一句话写「暂无调查数据」。不要写「见图」，不要编造支流名录。\n\n"
               "## 1.2 社会经济\n"
               "写涉及县区、乡镇，以及防治对象数量、受威胁人口、类型构成。"
               "县区和乡镇用【乡镇分布】，数量和人口用【流域基本统计】【对象类型统计】。"
               "数据包没有的产值、产业、学校、医院，写「暂无调查数据」。\n\n"
               "## 1.3 山洪灾害防御情况\n"
               "根据防治对象与人口分布，概括本流域受山洪威胁的对象和防御重点。"
               "没有监测预警设施、避险场所、预案等调查数据时写「暂无调查数据」。\n\n"
               "用 Markdown 表格嵌入表1-1 流域基本统计表、表1-2 对象类型统计表、表1-3 乡镇分布表，"
               "单元格与给定数据完全一致；某一来源为空就不要输出空表。\n\n"
               "【实测数据包】\n{{实测数据包}}\n\n"
               "【流域基本统计】\n{{流域基本统计}}\n\n"
               "【对象类型统计】\n{{对象类型统计}}\n\n"
               "【乡镇分布】\n{{乡镇分布}}\n\n"
               "【写作提纲】\n{{写作提纲}}\n\n"
               "用户补充资料：{{补充资料}}"),
         llm_ips=[("流域基本统计", "sql1"), ("对象类型统计", "sql2"), ("乡镇分布", "sql3")],
         llm_extra=[("wsmc", "流域名称"), ("sjbao", "实测数据包"), ("tigang", "写作提纲")]),
 2: dict(name="山洪报告_02前期准备", start_ip=[],
         sqls=[("风险隐患排查清单", "SELECT county_name AS 县区, town_name AS 乡镇, fnm AS 防治对象, ftp AS 类型, pcount AS 人口, rvnm AS 所在河流, risk_backwater AS 壅水, risk_overflow AS 漫流, risk_debris_flow AS 泥石流, risk_landslide AS 滑坡, comments AS 隐患描述 FROM prevention_object WHERE risk_backwater='是' OR risk_overflow='是' OR risk_debris_flow='是' OR risk_landslide='是' ORDER BY county_name, town_name LIMIT 100")],
         user=("请撰写报告章节《二、前期准备》：内业排查工作组织与技术路线概述、风险隐患要素排查情况统计（按乡镇分布分析）、主要隐患类型特征；"
               "以 Markdown 表格嵌入 表2-1 风险隐患要素内业排查清单（可精简列，数据保持一致）。\n\n"
               "【排查清单】{{风险隐患排查清单}}\n\n用户补充资料：{{补充资料}}"),
         llm_ips=[("风险隐患排查清单", "sql1")]),
 3: dict(name="山洪报告_03外业调查成果", start_ip=[],
         sqls=[("桥涵坝R1分级统计", "SELECT type AS 类型, COUNT(*) AS 数量, ROUND(AVG(obstruction_area_ratio),2) AS 平均R1, MAX(obstruction_area_ratio) AS 最大R1, SUM(CASE WHEN obstruction_area_ratio<=10 THEN 1 ELSE 0 END) AS 低阻水, SUM(CASE WHEN obstruction_area_ratio>10 AND obstruction_area_ratio<=30 THEN 1 ELSE 0 END) AS 中阻水, SUM(CASE WHEN obstruction_area_ratio>30 THEN 1 ELSE 0 END) AS 高阻水, SUM(CASE WHEN obstruction_area_ratio>80 THEN 1 ELSE 0 END) AS 极端阻水 FROM road_culvert_bridge_dam GROUP BY type"),
               ("高阻水桥涵坝清单", "SELECT name AS 名称, code AS 编码, type AS 类型, channelw AS 沟宽, channeld AS 沟深, obstruction_area_ratio AS 阻水比R1, obstruction_storage_capacity AS 阻水库容, backwater_objname AS 壅水影响对象, overflow_objname AS 漫流影响对象, comments AS 备注 FROM road_culvert_bridge_dam WHERE obstruction_area_ratio>30 ORDER BY obstruction_area_ratio DESC LIMIT 20"),
               ("沟滩占地清单", "SELECT name AS 名称, code AS 编码, type AS 类型, width AS 沟宽, deep AS 沟深, zsmj AS 占压面积, rvnm AS 所在河流, comments AS 备注 FROM gully_occupy_area WHERE zsmj>0 ORDER BY zsmj DESC LIMIT 20")],
         user=("请撰写报告章节《三、外业调查成果》：3.1 涉水工程调查成果（R1阻水面积比分级：低≤10%、中10-30%、高>30%、极端>80%）；"
               "3.2 沟滩占地调查成果（占压面积大于70平方米为严重占压）；3.3 重点关注对象分析（对高阻水与严重占压对象逐一简述，每个对象保留编码并附图片占位符【照片:编码】）。"
               "以 Markdown 表格嵌入 表3-1 桥涵坝阻水分级统计表、表3-2 高阻水桥涵坝调查表、表3-3 沟滩占地调查表（数据与给定一致）。\n\n"
               "【R1分级统计】{{桥涵坝R1分级统计}}\n\n【高阻水桥涵坝清单】{{高阻水桥涵坝清单}}\n\n【沟滩占地清单】{{沟滩占地清单}}\n\n用户补充资料：{{补充资料}}"),
         llm_ips=[("桥涵坝R1分级统计", "sql1"), ("高阻水桥涵坝清单", "sql2"), ("沟滩占地清单", "sql3")]),
 4: dict(name="山洪报告_04影响分析", start_ip=[{"field": "flow_q", "name": "设计洪峰流量", "type": "string", "required": False},
                                                {"field": "cv", "name": "泥石流体积浓度", "type": "string", "required": False}],
         sqls=[("壅水溃决计算成果", "SELECT name AS 名称, code AS 编码, channelw AS 沟宽b, channeld AS 沟深d, obstruction_area_ratio AS 阻水比R1, ROUND(POW(30*0.035/(channelw*SQRT(0.01)),0.6),2) AS 壅水高度m, ROUND(0.9*channelw*POW(channeld,1.5),1) AS 溃决洪峰, backwater_objname AS 壅水影响对象, burst_objname AS 溃决影响对象, overflow_objname AS 漫流影响对象 FROM road_culvert_bridge_dam WHERE obstruction_area_ratio>10 AND channelw>0 AND channeld>0 ORDER BY obstruction_area_ratio DESC LIMIT 50"),
               ("影响对象统计", "SELECT COUNT(*) AS 计算对象数, SUM(CASE WHEN backwater_objname IS NOT NULL AND backwater_objname<>'' THEN 1 ELSE 0 END) AS 有壅水影响, SUM(CASE WHEN burst_objname IS NOT NULL AND burst_objname<>'' THEN 1 ELSE 0 END) AS 有溃决影响, SUM(CASE WHEN overflow_objname IS NOT NULL AND overflow_objname<>'' THEN 1 ELSE 0 END) AS 有漫流影响, MAX(ROUND(POW(30*0.035/(channelw*SQRT(0.01)),0.6),2)) AS 最大壅水高度 FROM road_culvert_bridge_dam WHERE obstruction_area_ratio>10 AND channelw>0 AND channeld>0")],
         user=("请撰写报告章节《四、影响分析》：4.1 分析方法概述（说明假设：设计洪峰流量Q取30立方米每秒、糙率n=0.035、比降S=0.01；"
               "壅水高度公式h=(Q·n/(b·√S))^0.6；溃坝洪峰公式Qmax=0.9·b·d^1.5）；4.2 壅水影响分析；4.3 溃决影响分析；4.4 改道漫流影响分析；"
               "4.5 综合风险评价（结合R1与影响对象）。以 Markdown 表格嵌入 表4-1 影响分析计算成果表（数据与给定一致），重点对象保留编码并附【照片:编码】占位符。\n\n"
               "【计算成果】{{壅水溃决计算成果}}\n\n【影响对象统计】{{影响对象统计}}\n\n用户补充资料：{{补充资料}}"),
         llm_ips=[("壅水溃决计算成果", "sql1"), ("影响对象统计", "sql2")]),
 5: dict(name="山洪报告_05历史灾害", start_ip=[{"field": "lishijilv", "name": "历史灾害记录", "type": "string", "required": False},
                                                {"field": "honghen", "name": "洪痕入户调查", "type": "string", "required": False}],
         sqls=[],
         user=("请撰写报告章节《五、历史山洪灾害情况》。若下方提供了历史灾害记录或洪痕入户调查资料，依据资料撰写：5.1 灾害记录统计（按年代时间序列归纳，判断上升/下降/稳定趋势）；"
               "5.2 严重程度分级（特大型≥10人死亡或≥1亿元损失；大型3-9人死亡或1000万-1亿元；中型1-2人死亡或100-1000万元；小型其余）；"
               "5.3 历史洪痕与入户调查情况（防灾意识偏高/一般/偏低评价）；5.4 灾害演变规律分析。"
               "若资料为空，则撰写章节框架并说明该流域需补充历史灾害调查资料，列出调查要求。涉及具体灾害事件使用 Markdown 表格，不得虚构具体数据。\n\n"
               "历史灾害记录：{{历史灾害记录}}\n\n洪痕入户调查：{{洪痕入户调查}}\n\n用户补充资料：{{补充资料}}"),
         llm_ips=[]),
 6: dict(name="山洪报告_06防治区分析", start_ip=[],
         sqls=[("乡镇防治对象统计", "SELECT county_name AS 县区, town_name AS 乡镇, COUNT(*) AS 对象数, SUM(pcount) AS 人口 FROM prevention_object GROUP BY county_name, town_name ORDER BY 人口 DESC"),
               ("重点防治对象清单", "SELECT fnm AS 对象名称, county_name AS 县区, town_name AS 乡镇, ftp AS 类型, pcount AS 人口, rvnm AS 所在河流, risk_backwater AS 壅水, risk_overflow AS 漫流 FROM prevention_object WHERE pcount>300 ORDER BY pcount DESC LIMIT 30")],
         user=("请撰写报告章节《六、防治区分析》：6.1 防治区基本情况；6.2 重点防治对象分析（人口>300人，超500人为极重点）；"
               "6.3 防治能力评估（结合壅水/漫流隐患分布定性评价监测预警与避险转移能力薄弱环节）；6.4 防治对策建议。"
               "以 Markdown 表格嵌入 表6-1 乡镇防治对象统计表、表6-2 重点防治对象表（数据与给定一致），重大隐患对象附【照片:对象名称】占位符。\n\n"
               "【乡镇统计】{{乡镇防治对象统计}}\n\n【重点对象清单】{{重点防治对象清单}}\n\n用户补充资料：{{补充资料}}"),
         llm_ips=[("乡镇防治对象统计", "sql1"), ("重点防治对象清单", "sql2")]),
 7: dict(name="山洪报告_07成果与建议", start_ip=[{"field": "ch1", "name": "流域概况内容", "type": "string", "required": False},
                                                {"field": "ch2", "name": "前期准备内容", "type": "string", "required": False},
                                                {"field": "ch3", "name": "外业调查内容", "type": "string", "required": False},
                                                {"field": "ch4", "name": "影响分析内容", "type": "string", "required": False},
                                                {"field": "ch5", "name": "历史灾害内容", "type": "string", "required": False},
                                                {"field": "ch6", "name": "防治区分析内容", "type": "string", "required": False}],
         sqls=[],
         user=("以下是一份山洪灾害风险隐患调查报告前六章的内容。请据此撰写《七、成果与建议》，内容包括："
               "7.1 主要成果；7.2 隐患整治优先序（急需整治、近期整治、远期整治三类清单）；"
               "7.3 工程措施建议（桥涵坝：R1>50%拆除重建、30-50%拓宽改造、10-30%中期计划；沟滩占地：拆违-清障-疏浚-护岸）；"
               "7.4 监测预警能力提升建议；7.5 群测群防体系建设建议；7.6 分期实施路线图（用 Markdown 表格列出近/中/远期任务）。"
               "建议必须与前六章反映的隐患情况对应，可引用对象名称与编码。\n\n"
               "一、流域概况：{{流域概况内容}}\n\n二、前期准备：{{前期准备内容}}\n\n三、外业调查成果：{{外业调查内容}}\n\n"
               "四、影响分析：{{影响分析内容}}\n\n五、历史灾害：{{历史灾害内容}}\n\n六、防治区分析：{{防治区分析内容}}\n\n"
               "用户补充资料：{{补充资料}}"),
         llm_ips=[]),
}
NAMES7 = list(CH[1]["name"] for _ in [0])  # placeholder not used

def build_chapter(no):
    cfg = CH[no]
    nodes = []
    start_ip = cfg["start_ip"] + [{"field": "batch_no", "name": "批次号", "type": "string", "required": False},
                                   {"field": "buochong", "name": "补充资料", "type": "string", "required": False}]
    nodes.append(node("start-node", "start", 200, 450, "开始", ip=start_ip))
    prev = "start-node"
    sql_seq = 0
    sql_ids = []
    sql_names = {}
    y = 360
    for text, q in cfg["sqls"]:
        sql_seq += 1
        sid = f"sql{sql_seq}"
        sql_names[text] = sid
        nodes.append(sql(560, y, text, q, sql_seq))
        sql_ids.append(sid)
        y += 150
    # llm 输入
    llm_ip = [{"field": "out_" + sid, "name": n, "nodeId": sid, "type": "string"} for n, sid in cfg["llm_ips"]]
    llm_ip.append({"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"})
    # 特殊：ch7 输入来自上游章节内容（start 输入）；占位符名与提示词中的 {{中文名}} 一致
    if no == 7:
        ch_names = {"ch1": "流域概况内容", "ch2": "前期准备内容", "ch3": "外业调查内容",
                    "ch4": "影响分析内容", "ch5": "历史灾害内容", "ch6": "防治区分析内容"}
        llm_ip = [{"field": f, "name": ch_names[f], "nodeId": "start-node", "type": "string"}
                  for f in ["ch1", "ch2", "ch3", "ch4", "ch5", "ch6"]]
        llm_ip.append({"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"})
    if no == 5:
        llm_ip = [{"field": "lishijilv", "name": "历史灾害记录", "nodeId": "start-node", "type": "string"},
                  {"field": "honghen", "name": "洪痕入户调查", "nodeId": "start-node", "type": "string"},
                  {"field": "buochong", "name": "补充资料", "nodeId": "start-node", "type": "string"}]
    for field, name in cfg.get("llm_extra", []):
        llm_ip.append({"field": field, "name": name, "nodeId": "start-node", "type": "string"})
    lx = 1200
    nodes.append(node("llm1", "llm", lx, 450, "LLM撰写",
                      options={"model": {"modeId": "2099900548148649986", "modelId": "2099900548148649986",
                                   "params": {"model": "ark-code-latest", "temperature": 0.3, "timeout": 120}},
                               "history": 0,
                               "messages": [{"role": "system", "content": SYS}, {"role": "user", "content": cfg["user"]}]},
                      ip=llm_ip, op=[{"field": "text", "name": "章节内容", "type": "string"}]))
    nodes.append(save(f"reportChapterSave{no}", 1450, 450))
    nodes.append(end_node("end-node", "{{章节内容}}", 450))
    nodes.append(end_node("end-fb", DEFAULTS[no], 650))

    edges = []
    prev = "start-node"
    for sid in sql_ids:
        edges.append((prev, sid)); prev = sid
    if sql_ids:
        edges.append((prev, "llm1"))
    edges.append(("llm1", "save1")); edges.append(("save1", "end-node"))
    edge_objs = []
    nm = {n["id"]: n for n in nodes}
    for a, b in edges:
        s, t = nm[a], nm[b]
        x1, y1 = s["x"] + W, s["y"] - 15
        x2, y2 = t["x"], t["y"] - 15
        mx = (x1 + x2) // 2
        edge_objs.append({"id": f"e_{a}_{b}", "type": "base-edge", "sourceNodeId": a, "targetNodeId": b,
                          "sourceAnchorId": f"{a}_output", "targetAnchorId": f"{b}_input",
                          "pointsList": [{"x": x1, "y": y1}, {"x": mx, "y": y1}, {"x": mx, "y": y2}, {"x": x2, "y": y2}]})
    design = json.dumps({"nodes": nodes, "edges": edge_objs}, ensure_ascii=False)
    head = ["start.tag('start-node')"] + [f"sql.tag('sql{i+1}')" for i in range(len(sql_ids))]
    tail = f"llm.tag('llm1'),java.tag('save1'),end.tag('end-node')"
    chain = (f"THEN(\n    {', '.join(head)},\n    "
             f"CATCH(THEN(llm.tag('llm1'),enhanceJava.tag('save1'),end.tag('end-node'))).DO(end.tag('end-fb'))\n"
             f").tag(\"start-node\")")
    inputs_meta = [{"field": p["field"], "name": p["name"], "required": p.get("required", False), "type": p.get("type", "string")} for p in start_ip]
    metadata = json.dumps({"inputs": inputs_meta,
                           "outputs": [{"field": "outputText", "type": "string", "nodeId": "end-node", "name": "章节内容"}]}, ensure_ascii=False)
    cur.execute("SELECT id FROM airag_flow WHERE name=%s", (cfg["name"],))
    row = cur.fetchone()
    if row:
        cur.execute("UPDATE airag_flow SET design=%s, chain=%s, metadata=%s WHERE id=%s", (design, chain, metadata, row[0]))
        print("updated", cfg["name"])
    else:
        print("MISSING FLOW", cfg["name"])

for no in CH:
    build_chapter(no)

# ===== 合并导出流程 =====
nodes = [
    node("start-node", "start", 200, 450, "开始",
         ip=[{"field": "batch_no", "name": "批次号", "type": "string", "required": True}]),
    node("export1", "enhanceJava", 700, 450, "合并导出Word",
         options={"enhance": {"type": "spring", "path": "reportWordExportEnhance"}},
         ip=[{"field": "batch_no", "name": "批次号", "nodeId": "start-node", "type": "string"}],
         op=[{"field": "result", "name": "导出结果", "type": "string"}]),
    node("end-node", "end", 1100, 450, "结束",
         options={"outputType": "text", "outputContent": "{{导出结果}}"},
         op=[{"field": "result", "name": "导出结果", "nodeId": "export1", "type": "string"}]),
]
nm = {n["id"]: n for n in nodes}
edges = []
for a, b in [("start-node", "export1"), ("export1", "end-node")]:
    s, t = nm[a], nm[b]
    x1, y1 = s["x"] + W, s["y"] - 15
    x2, y2 = t["x"], t["y"] - 15
    mx = (x1 + x2) // 2
    edges.append({"id": f"e_{a}_{b}", "type": "base-edge", "sourceNodeId": a, "targetNodeId": b,
                  "sourceAnchorId": f"{a}_output", "targetAnchorId": f"{b}_input",
                  "pointsList": [{"x": x1, "y": y1}, {"x": mx, "y": y1}, {"x": mx, "y": y2}, {"x": x2, "y": y2}]})
design = json.dumps({"nodes": nodes, "edges": edges}, ensure_ascii=False)
chain = "THEN(\n    start.tag('start-node'),\n    enhanceJava.tag('export1'),\n    end.tag('end-node')\n).tag(\"start-node\")"
metadata = json.dumps({"inputs": [{"field": "batch_no", "name": "批次号", "required": True, "type": "string"}],
                       "outputs": [{"field": "outputText", "type": "string", "nodeId": "end-node", "name": "导出结果"}]}, ensure_ascii=False)
cur.execute("SELECT id FROM airag_flow WHERE name='山洪报告_09合并导出'")
if not cur.fetchone():
    fid = "19" + str(uuid.uuid4().int)[:17]
    cur.execute("""INSERT INTO airag_flow (id,create_by,create_time,sys_org_code,application_name,name,descr,chain,design,status,metadata)
      VALUES (%s,'admin',%s,'A04','jeecg','山洪报告_09合并导出',%s,%s,%s,'enable',%s)""",
                (fid, datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                 "输入批次号，校验全部章节已通过审核后合并导出Word（存在待审核/驳回章节则不导出）", chain, design, metadata))
    print("inserted 山洪报告_09合并导出")
else:
    cur.execute("UPDATE airag_flow SET design=%s, chain=%s, metadata=%s WHERE name='山洪报告_09合并导出'", (design, chain, metadata))
    print("updated 山洪报告_09合并导出")

# ===== 总调度流程：加 batch_no 并传给各子流程 =====
cur.execute("SELECT id, design FROM airag_flow WHERE name='山洪灾害风险隐患调查报告自动生成'")
row = cur.fetchone()
if row:
    fid, design = row
    d = json.loads(design)
    for n in d["nodes"]:
        if n["type"] == "start":
            ips = n["properties"]["inputParams"]
            if not any(p["field"] == "batch_no" for p in ips):
                ips.insert(0, {"field": "batch_no", "name": "批次号", "type": "string", "required": False})
        if n["type"] == "subflow":
            ips = n["properties"]["inputParams"]
            if not any(p["field"] == "batch_no" for p in ips):
                ips.append({"field": "batch_no", "name": "batch_no", "nodeId": "start-node", "type": "string"})
    cur.execute("UPDATE airag_flow SET design=%s WHERE id=%s", (json.dumps(d, ensure_ascii=False), fid))
    print("master updated with batch_no")

conn.commit()
conn.close()
print("all done")
