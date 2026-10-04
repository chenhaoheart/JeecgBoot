# -*- coding: utf-8 -*-
"""
山洪报告「实测数据包 + 写作提纲」流程补丁（准确率增强 P0/P1）。

作用（幂等，可重复执行）：
1. 7 个章节子流程的 start 节点增加两个可选入参：
   - sjbao 实测数据包（由 ShReportDataPacketService 按流域组装后注入，约束模型只能用真实数据）
   - tigang 写作提纲（两阶段生成确认后注入）
2. LLM 节点的「用户消息」追加对 {{实测数据包}} / {{写作提纲}} 的引用段，
   第 4 章追加洪水计算参数（Q/n/S）使用要求；
3. 同步更新 ai_flows/*.jeecgai 资产文件（存在才更新）。

用法：
  python upgrade_data_packet.py              # 同时更新数据库流程与 .jeecgai 文件
  python upgrade_data_packet.py --files-only # 只更新 .jeecgai 文件，不连数据库

注意：更新库内流程后需重启后端（liteflow 在启动时加载）。
"""
import base64
import glob
import json
import os
import sys

try:
    import pymysql
except ImportError:
    pymysql = None

FILES_ONLY = "--files-only" in sys.argv

CHAPTER_FLOWS = [
    "山洪报告_01流域概况", "山洪报告_02前期准备", "山洪报告_03外业调查成果", "山洪报告_04影响分析",
    "山洪报告_05历史灾害", "山洪报告_06防治区分析", "山洪报告_07成果与建议",
]

PACKET_LINE = "\n\n【实测数据包】{{实测数据包}}\n以上数据包是本章唯一允许使用的事实数据来源，包中没有的数据写「暂无调查数据」，严禁编造或引用其他流域数字。"
OUTLINE_LINE = "\n\n【本章写作提纲】{{写作提纲}}\n请严格按提纲的小节顺序撰写；如与实测数据冲突，以实测数据为准并在文中说明。"
CH4_LINE = "\n（重要）壅水/溃决计算必须采用实测数据包中给出的设计洪峰流量Q、沟道糙率n、沟道比降S，不得自行假设。"

ASSET_DIR = os.path.dirname(os.path.abspath(__file__))


def ensure_input_param(params, field, name, required=False):
    for p in params or []:
        if p.get("field") == field:
            return False
    params.append({"field": field, "name": name, "type": "string", "required": required})
    return True


def patch_design(design_obj_or_str, chapter_no):
    """补丁 design（dict 或 JSON 字符串），返回 (changed, design_dict_or_str)。"""
    as_str = isinstance(design_obj_or_str, str)
    design = json.loads(design_obj_or_str) if as_str else design_obj_or_str
    changed = False
    start_node = None
    llm_nodes = []
    for n in design.get("nodes", []):
        if n.get("type") == "start":
            start_node = n
        if n.get("type") == "llm":
            llm_nodes.append(n)
    if start_node is None:
        return False, design_obj_or_str

    props = start_node.setdefault("properties", {})
    ips = props.setdefault("inputParams", [])
    changed |= ensure_input_param(ips, "sjbao", "实测数据包")
    changed |= ensure_input_param(ips, "tigang", "写作提纲")

    for llm in llm_nodes:
        opts = llm.setdefault("properties", {}).setdefault("options", {})
        lip = opts.setdefault("inputParams", [])
        if ensure_input_param(lip, "sjbao", "实测数据包", ):
            lip[-1]["nodeId"] = "start-node"
            changed = True
        if ensure_input_param(lip, "tigang", "写作提纲"):
            lip[-1]["nodeId"] = "start-node"
            changed = True
        messages = opts.get("messages") or []
        for msg in messages:
            if msg.get("role") != "user":
                continue
            content = msg.get("content", "")
            if "{{实测数据包}}" not in content:
                content += PACKET_LINE
                changed = True
            if "{{写作提纲}}" not in content:
                content += OUTLINE_LINE
                changed = True
            if chapter_no == 4 and "壅水/溃决计算必须采用实测数据包" not in content:
                content += CH4_LINE
                changed = True
            msg["content"] = content

    return changed, (json.dumps(design, ensure_ascii=False) if as_str else design)


def patch_asset_file(flow_name, chapter_no):
    """在 ai_flows 目录下按流程名定位 .jeecgai 资产并补丁。"""
    path = os.path.join(ASSET_DIR, "*.jeecgai")
    target = None
    for f in glob.glob(path):
        try:
            wrapper = json.loads(base64.b64decode(open(f, "r", encoding="utf-8").read()))
            if wrapper.get("flowConfig", {}).get("name") == flow_name:
                target = f
                break
        except Exception:
            continue
    if not target:
        print("  资产文件未找到，跳过：", flow_name)
        return
    wrapper = json.loads(base64.b64decode(open(target, "r", encoding="utf-8").read()))
    cfg = wrapper["flowConfig"]
    changed, new_design = patch_design(cfg.get("design"), chapter_no)
    if changed:
        cfg["design"] = new_design
        # 元数据 inputs 同步
        try:
            meta = json.loads(cfg.get("metadata") or "{}")
            mis = meta.setdefault("inputs", [])
            ensure_input_param(mis, "sjbao", "实测数据包")
            ensure_input_param(mis, "tigang", "写作提纲")
            cfg["metadata"] = json.dumps(meta, ensure_ascii=False)
        except Exception:
            pass
        open(target, "w", encoding="utf-8").write(
            base64.b64encode(json.dumps(wrapper, ensure_ascii=False).encode("utf-8")).decode("ascii"))
        print("  资产文件已更新：", os.path.basename(target))
    else:
        print("  资产文件无需更新：", flow_name)


def patch_db():
    if pymysql is None:
        raise RuntimeError("未安装 pymysql，无法更新数据库；可使用 --files-only 仅更新资产文件")
    conn = pymysql.connect(host="127.0.0.1", user="root", password="123456",
                           database="jeecg-boot", charset="utf8mb4")
    cur = conn.cursor()
    try:
        for idx, name in enumerate(CHAPTER_FLOWS, start=1):
            cur.execute("SELECT id, design FROM airag_flow WHERE name=%s AND status='enable' LIMIT 1", (name,))
            row = cur.fetchone()
            if not row:
                print("DB 未找到启用流程，跳过：", name)
                continue
            flow_id, design = row
            changed, new_design = patch_design(design, idx)
            if changed:
                cur.execute("UPDATE airag_flow SET design=%s WHERE id=%s", (new_design, flow_id))
                print("DB 已更新：", name)
            else:
                print("DB 无需更新：", name)
        conn.commit()
    finally:
        cur.close()
        conn.close()


def main():
    if not FILES_ONLY:
        try:
            patch_db()
        except Exception as e:
            print("数据库更新失败（可稍后带 --files-only 仅更新资产）：", e)
    for idx, name in enumerate(CHAPTER_FLOWS, start=1):
        patch_asset_file(name, idx)
    print("完成。库内流程已更新的环境请重启后端使 liteflow 重新加载。")


if __name__ == "__main__":
    main()
