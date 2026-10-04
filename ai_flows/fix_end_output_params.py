# -*- coding: utf-8 -*-
"""补齐章节子流程结束节点输出变量绑定（幂等）。

结束节点：绑定 llm1.text 为「章节内容」，outputType=text。
降级节点：保留 CATCH 兜底，清空残缺 outputParams（固定文案无需绑定）。
同时处理模型变体流程（名称含 #M）与 09 合并导出。

用法：
  python ai_flows/fix_end_output_params.py              # 更新数据库 + .jeecgai
  python ai_flows/fix_end_output_params.py --files-only # 只更新资产文件
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
ASSET_DIR = os.path.dirname(os.path.abspath(__file__))

CHAPTER_BIND = [{"field": "text", "name": "章节内容", "nodeId": "llm1", "type": "string"}]
EXPORT_BIND = [{"field": "result", "name": "导出结果", "nodeId": "export1", "type": "string"}]


def _as_text_options(opts):
    changed = False
    if opts.get("outputType") != "text":
        opts["outputType"] = "text"
        changed = True
    if "outputText" in opts:
        opts.pop("outputText", None)
        changed = True
    return changed


def _bind_ok(ops, want):
    if not isinstance(ops, list) or len(ops) != len(want):
        return False
    for got, exp in zip(ops, want):
        if not isinstance(got, dict):
            return False
        for k, v in exp.items():
            if got.get(k) != v:
                return False
    return True


def patch_design(design_obj_or_str):
    as_str = isinstance(design_obj_or_str, str)
    design = json.loads(design_obj_or_str) if as_str else (design_obj_or_str or {})
    changed = False
    for n in design.get("nodes") or []:
        if n.get("type") != "end":
            continue
        props = n.setdefault("properties", {})
        opts = props.setdefault("options", {})
        nid = n.get("id") or ""
        title = props.get("text") or ""
        content = (opts.get("outputContent") or "").strip()

        if nid == "end-fb" or "降级" in title:
            changed |= _as_text_options(opts)
            if props.get("outputParams"):
                props["outputParams"] = []
                changed = True
            continue

        if content == "{{章节内容}}":
            changed |= _as_text_options(opts)
            if not _bind_ok(props.get("outputParams"), CHAPTER_BIND):
                props["outputParams"] = [dict(CHAPTER_BIND[0])]
                changed = True
            continue

        if content == "{{导出结果}}":
            changed |= _as_text_options(opts)
            if not _bind_ok(props.get("outputParams"), EXPORT_BIND):
                props["outputParams"] = [dict(EXPORT_BIND[0])]
                changed = True
            continue

    return changed, (json.dumps(design, ensure_ascii=False) if as_str else design)


def patch_asset_files():
    updated = 0
    for path in glob.glob(os.path.join(ASSET_DIR, "*.jeecgai")):
        try:
            wrapper = json.loads(base64.b64decode(open(path, "r", encoding="utf-8").read()))
        except Exception:
            continue
        cfg = wrapper.get("flowConfig") or {}
        changed, new_design = patch_design(cfg.get("design"))
        if not changed:
            continue
        cfg["design"] = new_design
        open(path, "w", encoding="utf-8").write(
            base64.b64encode(json.dumps(wrapper, ensure_ascii=False).encode("utf-8")).decode("ascii"))
        print("资产已更新：", os.path.basename(path), "|", cfg.get("name"))
        updated += 1
    if updated == 0:
        print("资产文件无需更新")
    return updated


def patch_db():
    if pymysql is None:
        raise RuntimeError("未安装 pymysql，无法更新数据库；可使用 --files-only 仅更新资产文件")
    conn = pymysql.connect(host="127.0.0.1", user="root", password="123456",
                           database="jeecg-boot", charset="utf8mb4")
    cur = conn.cursor()
    updated = 0
    try:
        cur.execute(
            "SELECT id, name, design FROM airag_flow "
            "WHERE name LIKE %s OR name LIKE %s OR name=%s",
            ("山洪报告_%", "山洪灾害%", "山洪报告_09合并导出"),
        )
        rows = cur.fetchall()
        for flow_id, name, design in rows:
            changed, new_design = patch_design(design)
            if not changed:
                print("DB 无需更新：", name)
                continue
            cur.execute("UPDATE airag_flow SET design=%s WHERE id=%s", (new_design, flow_id))
            print("DB 已更新：", name)
            updated += 1
        conn.commit()
    finally:
        cur.close()
        conn.close()
    return updated


def main():
    db_n = 0
    if not FILES_ONLY:
        try:
            db_n = patch_db()
        except Exception as e:
            print("数据库更新失败（可稍后带 --files-only 仅更新资产）：", e)
    file_n = patch_asset_files()
    print("完成。DB %s 条，资产 %s 个。" % (db_n, file_n))
    if db_n:
        print("库内流程已更新，请重启后端使 liteflow 重新加载。")


if __name__ == "__main__":
    main()
