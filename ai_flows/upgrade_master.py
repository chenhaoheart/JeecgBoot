# -*- coding: utf-8 -*-
"""总调度流程：每个 subflow 包 CATCH().DO(默认内容end)，任一子流程失败不阻塞整体。"""
import json, pymysql, uuid, datetime

W, H = 332, 92
conn = pymysql.connect(host="127.0.0.1", user="root", password="123456", database="jeecg-boot", charset="utf8mb4")
cur = conn.cursor()

SUBS = [
 ("sub1", "流域概况内容", "一、流域概况"),
 ("sub2", "前期准备内容", "二、前期准备"),
 ("sub3", "外业调查内容", "三、外业调查成果"),
 ("sub4", "影响分析内容", "四、影响分析"),
 ("sub5", "历史灾害内容", "五、历史山洪灾害情况"),
 ("sub6", "防治区分析内容", "六、防治区分析"),
 ("sub7", "成果建议内容", "七、成果与建议"),
]
SUBFLOW_IDS = {
 "sub1": "1914883332769597518", "sub2": "1928547879718675711", "sub3": "1991643985719982804",
 "sub4": "1953066913553935493", "sub5": "1965726302223405840", "sub6": "1910333949858375511",
 "sub7": "1933774915911904676",
}

def node(id, t, x, y, text, options=None, ip=None, op=None):
    return {"id": id, "type": t, "x": x, "y": y,
            "properties": {"text": text, "remarks": "", "options": options or {},
                           "inputParams": ip or [], "outputParams": op or [], "width": W, "height": H}}

START_INPUTS = [
 {"field": "batch_no", "name": "批次号", "type": "string", "required": False},
 {"field": "wsmc", "name": "流域名称", "type": "string", "required": False},
 {"field": "flow_q", "name": "设计洪峰流量", "type": "string", "required": False},
 {"field": "cv", "name": "泥石流体积浓度", "type": "string", "required": False},
 {"field": "lishijilv", "name": "历史灾害记录", "type": "string", "required": False},
 {"field": "honghen", "name": "洪痕入户调查", "type": "string", "required": False},
 {"field": "buochong", "name": "补充资料", "type": "string", "required": False},
]
def common(): return [{"field": "buochong", "name": "buochong", "nodeId": "start-node", "type": "string"},
                      {"field": "batch_no", "name": "batch_no", "nodeId": "start-node", "type": "string"}]

nodes = [node("start-node", "start", 200, 430, "开始", ip=START_INPUTS)]
edges = []
nm = {"start-node": nodes[0]}

def edge(a, b):
    s, t = nm[a], nm[b]
    x1, y1 = s["x"] + W, s["y"] - 15
    x2, y2 = t["x"], t["y"] - 15
    mx = (x1 + x2) // 2
    edges.append({"id": f"e_{a}_{b}", "type": "base-edge", "sourceNodeId": a, "targetNodeId": b,
                  "sourceAnchorId": f"{a}_output", "targetAnchorId": f"{b}_input",
                  "pointsList": [{"x": x1, "y": y1}, {"x": mx, "y": y1}, {"x": mx, "y": y2}, {"x": x2, "y": y2}]})

chain_parts = ["start.tag('start-node')"]
for i, (sid, outname, cname) in enumerate(SUBS):
    y = 60 + i * 125
    if sid == "sub1":
        ips = [{"field": "wsmc", "name": "wsmc", "nodeId": "start-node", "type": "string"}] + common()
    elif sid == "sub4":
        ips = [{"field": "flow_q", "name": "flow_q", "nodeId": "start-node", "type": "string"},
               {"field": "cv", "name": "cv", "nodeId": "start-node", "type": "string"}] + common()
    elif sid == "sub5":
        ips = [{"field": "lishijilv", "name": "lishijilv", "nodeId": "start-node", "type": "string"},
               {"field": "honghen", "name": "honghen", "nodeId": "start-node", "type": "string"}] + common()
    else:
        ips = common()
    nodes.append(node(sid, "subflow", 650, y, cname,
                      options={"subflowId": SUBFLOW_IDS[sid]}, ip=ips,
                      op=[{"field": "outputText", "name": outname, "type": "string"}]))
    nm[sid] = nodes[-1]
    # 兜底 end：输出该章默认占位（变量名与 subflow 输出一致）
    fbid = f"fb{i+1}"
    fb_text = (f"## 【系统默认内容】\n\n{cname}\n\n本章生成流程执行异常，未产出内容。"
               f"请检查该章节子流程（{cname}）或重新生成本章节。")
    nodes.append(node(fbid, "end", 1000, y, f"默认:{cname}",
                      options={"outputText": True, "outputContent": fb_text},
                      op=[{"field": "outputText", "name": outname, "type": "string"}]))
    nm[fbid] = nodes[-1]
    chain_parts.append(f"CATCH(subflow.tag('{sid}')).DO(end.tag('{fbid}'))")

nodes.append(node("end-node", "end", 1350, 430, "结束",
                  options={"outputText": True,
                           "outputContent": "{{流域概况内容}}\n\n{{前期准备内容}}\n\n{{外业调查内容}}\n\n{{影响分析内容}}\n\n{{历史灾害内容}}\n\n{{防治区分析内容}}\n\n{{成果建议内容}}"},
                  op=[{"field": "outputText", "name": "完整报告内容", "type": "string"}]))
nm["end-node"] = nodes[-1]

# 边：start→sub1→sub2...→sub7→end（视觉主线）
seq = ["start-node"] + [s[0] for s in SUBS] + ["end-node"]
for a, b in zip(seq, seq[1:]):
    edge(a, b)

design = json.dumps({"nodes": nodes, "edges": edges}, ensure_ascii=False)
chain = f"THEN(\n    {',\n    '.join(chain_parts)},\n    end.tag('end-node')\n).tag(\"start-node\")"
metadata = json.dumps({"inputs": START_INPUTS,
                       "outputs": [{"field": "outputText", "type": "string", "nodeId": "end-node", "name": "完整报告内容"}]},
                      ensure_ascii=False)

cur.execute("SELECT id FROM airag_flow WHERE name='山洪灾害风险隐患调查报告自动生成'")
row = cur.fetchone()
if row:
    cur.execute("UPDATE airag_flow SET design=%s, chain=%s, metadata=%s WHERE id=%s", (design, chain, metadata, row[0]))
    print("master upgraded with per-subflow catch")
conn.commit()
conn.close()
