# -*- coding: utf-8 -*-
"""
把《山洪灾害补充调查评价技术要求（风险隐患调查与影响分析）》
导入独立知识库「山洪风险隐患技术规范」，并写入 pgvector。

文字层用 PyMuPDF 抽取；页面里的示意图再做 OCR，把图中文字并进对应章节。
不写入「山洪调查成果」知识库。

用法：
  python import_sh_spec_kb.py
  python import_sh_spec_kb.py --dry-run
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys

import numpy as np
import pymupdf

from import_sh_survey_kb import (
    EMBED_ID_VOLC,
    LLM_ID,
    api_post,
    db,
    login,
    pick_embed_id,
    sanitize_text,
    split_oversize,
    upsert_docs,
)

KNOW_ID = "2099990000000000201"
APP_ID = "2099990000000000202"
KNOW_NAME = "山洪风险隐患技术规范"
APP_NAME = "山洪风险隐患技术规范问答"
DEFAULT_PDF = r"D:\青海2025\2023-山洪灾害补充调查评价技术要求(风险隐患调查与影响分析)(4).pdf"

HEAD_CHAPTERS = {
    "1 总则",
    "2 技术路线",
    "3 跨沟道路、桥涵和塘（堰）坝调查",
    "4 沟滩占地情况调查",
    "5 多支齐汇和干流顶托调查",
    "6 其他隐患类型调查",
    "7 主要风险隐患影响分析",
    "8 成果整理与应用",
}
APPENDIX_RE = re.compile(r"^附\s*录(?:\s*[1-3])?(?:\s+[\u4e00-\u9fa5].{1,24})?$")
TABLE_RE = re.compile(r"^附表\s*\d+\s*[-－]\s*\d+\s+[\u4e00-\u9fa5].{1,40}$")
PAGE_NO_RE = re.compile(r"^\d{1,3}$")


def _norm(s: str) -> str:
    return re.sub(r"\s+", "", s or "")


def ocr_engine():
    from rapidocr_onnxruntime import RapidOCR
    return RapidOCR()


def ocr_page_images(page, ocr) -> list[str]:
    lines = []
    seen = set()
    try:
        blocks = page.get_text("dict").get("blocks") or []
    except Exception:
        return lines
    for block in blocks:
        if block.get("type") != 1:
            continue
        rect = pymupdf.Rect(block.get("bbox"))
        if rect.width < 80 or rect.height < 80:
            continue
        pix = page.get_pixmap(matrix=pymupdf.Matrix(2, 2), clip=rect, alpha=False)
        if pix.width < 40 or pix.height < 40:
            continue
        img = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.height, pix.width, pix.n)
        try:
            result, _ = ocr(img)
        except Exception as e:
            print("  OCR 失败：", e)
            continue
        if not result:
            continue
        for item in result:
            text = (item[1] or "").strip()
            score = float(item[2]) if len(item) > 2 else 1.0
            key = _norm(text)
            if score < 0.55 or len(key) < 2 or key in seen:
                continue
            seen.add(key)
            lines.append("图注：" + text)
    return lines


def page_text(page, ocr) -> str:
    raw = page.get_text("text") or ""
    raw = sanitize_text(raw)
    kept = []
    for line in raw.splitlines():
        s = line.strip()
        if not s or PAGE_NO_RE.match(s):
            continue
        kept.append(s)
    body = "\n".join(kept)
    body_norm = _norm(body)
    extra = []
    for line in ocr_page_images(page, ocr):
        if _norm(line) in body_norm:
            continue
        extra.append(line)
    if extra:
        body += "\n\n【图中文字】\n" + "\n".join(extra)
    return body.strip()


def is_heading(line: str) -> bool:
    s = re.sub(r"\s+", " ", (line or "").strip()).replace("*", "")
    if not s or len(s) > 50 or "。" in s or "“" in s or "…" in s or "..." in s:
        return False
    if s in ("前言", "前 言") or s in HEAD_CHAPTERS:
        return True
    if APPENDIX_RE.match(s) or TABLE_RE.match(s):
        return True
    return False


def split_sections(pages: list[str], filename: str) -> list[dict]:
    lines = []
    for text in pages:
        lines.extend(text.splitlines())
        lines.append("")
    sections = []
    title = "封面"
    buf = []

    def flush():
        content = "\n".join(buf).strip()
        if len(content) < 40:
            return
        sections.append({
            "title": title if len(title) <= 80 else title[:80],
            "content": "文献：山洪灾害补充调查评价技术要求（风险隐患调查与影响分析）（试行）\n"
                       "章节：%s\n来源：%s\n\n%s" % (title, filename, content),
        })

    for line in lines:
        s = line.strip()
        if is_heading(s) and buf:
            flush()
            title = re.sub(r"\s+", " ", s)
            buf = [s]
        else:
            if s or buf:
                buf.append(line)
    flush()
    return sections


def extract(path: str) -> list[dict]:
    doc = pymupdf.open(path)
    print("页数", doc.page_count)
    ocr = ocr_engine()
    pages = []
    for i, page in enumerate(doc):
        text = page_text(page, ocr)
        pages.append(text)
        fig = "图中文字" in text
        print("  第%02d页 chars=%d%s" % (i + 1, len(text), " +图" if fig else ""))
    docs = split_oversize(split_sections(pages, os.path.basename(path)))
    print("切成", len(docs), "条")
    for d in docs:
        print(" ", d["title"], "chars=", len(d["content"]))
    return docs


def ensure_knowledge(cur, embed_id: str) -> str:
    meta = json.dumps({
        "enableSegment": True,
        "segmentStrategy": "auto",
        "maxSegment": 3000,
        "overlap": 40,
    }, ensure_ascii=False)
    descr = "2023年《山洪灾害补充调查评价技术要求（风险隐患调查与影响分析）（试行）》。按章节入库，示意图文字已 OCR 补入。"
    cur.execute("SELECT id FROM airag_knowledge WHERE id=%s OR name=%s LIMIT 1", (KNOW_ID, KNOW_NAME))
    row = cur.fetchone()
    if row:
        cur.execute(
            "UPDATE airag_knowledge SET name=%s, descr=%s, embed_id=%s, status='enable', type='knowledge', metadata=%s WHERE id=%s",
            (KNOW_NAME, descr, embed_id, meta, row[0]),
        )
        return row[0]
    cur.execute(
        "INSERT INTO airag_knowledge (id, create_by, create_time, sys_org_code, tenant_id, name, descr, embed_id, status, type, metadata) "
        "VALUES (%s,'admin',NOW(),'A04','0',%s,%s,%s,'enable','knowledge',%s)",
        (KNOW_ID, KNOW_NAME, descr, embed_id, meta),
    )
    return KNOW_ID


def ensure_app(cur, know_id: str) -> str:
    prompt = (
        "你是「山洪风险隐患技术规范」助手，依据《山洪灾害补充调查评价技术要求（风险隐患调查与影响分析）（试行）》回答。"
        "条款、公式、表字段必须来自检索片段，不要编造。图中文字来自示意图 OCR，与正文条款冲突时以正文为准。"
        "没检索到就说明规范片段未命中，并提示可问的章节，例如阻水面积比、沟滩占地、附表填表说明。"
    )
    prologue = "我可以按《山洪灾害补充调查评价技术要求（风险隐患调查与影响分析）》回答调查方法、计算公式和附表填法。"
    preset = json.dumps([
        "阻水面积比 R1 怎么计算？",
        "沟滩占地要调查哪些内容？",
        "附表1-1防治对象名录怎么填？",
        "壅水和溃决影响分析有什么要求？",
    ], ensure_ascii=False)
    rag_meta = json.dumps({"topNumber": 5, "similarity": 0.35, "timeout": 60}, ensure_ascii=False)
    cur.execute("SELECT id FROM airag_app WHERE id=%s OR name=%s LIMIT 1", (APP_ID, APP_NAME))
    row = cur.fetchone()
    if row:
        cur.execute(
            "UPDATE airag_app SET name=%s, descr=%s, type='chatSimple', prologue=%s, prompt=%s, "
            "model_id=%s, knowledge_ids=%s, status='enable', msg_num=8, preset_question=%s, metadata=%s WHERE id=%s",
            (APP_NAME, KNOW_NAME, prologue, prompt, LLM_ID, know_id, preset, rag_meta, row[0]),
        )
        return row[0]
    cur.execute(
        "INSERT INTO airag_app (id, create_by, create_time, sys_org_code, tenant_id, name, descr, type, "
        "prologue, prompt, model_id, knowledge_ids, status, msg_num, preset_question, metadata) "
        "VALUES (%s,'admin',NOW(),'A04','0',%s,%s,'chatSimple',%s,%s,%s,%s,'enable',8,%s,%s)",
        (APP_ID, APP_NAME, KNOW_NAME, prologue, prompt, LLM_ID, know_id, preset, rag_meta),
    )
    return APP_ID


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pdf", default=DEFAULT_PDF)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--base", default=os.environ.get("JEECG_BASE", "http://127.0.0.1:8080/jeecg-boot"))
    ap.add_argument("--user", default=os.environ.get("JEECG_USER", "admin"))
    ap.add_argument("--password", default=os.environ.get("JEECG_PASSWORD", "123456"))
    args = ap.parse_args()
    if not os.path.isfile(args.pdf):
        raise SystemExit("找不到 PDF：" + args.pdf)

    sys.stdout.reconfigure(encoding="utf-8")
    docs = extract(args.pdf)
    if args.dry_run:
        return
    if not docs:
        raise SystemExit("没有抽出正文")

    conn = db()
    cur = conn.cursor()
    embed_id = pick_embed_id(cur)
    if embed_id != EMBED_ID_VOLC:
        print("当前向量模型", embed_id)
    know_id = ensure_knowledge(cur, embed_id)
    app_id = ensure_app(cur, know_id)
    conn.commit()
    print("知识库", know_id, KNOW_NAME)
    print("AI应用", app_id, APP_NAME)

    token = login(args.base, args.user, args.password)
    cur.execute("SELECT title FROM airag_knowledge_doc WHERE knowledge_id=%s", (know_id,))
    existing = {r[0]: r[0] for r in cur.fetchall()}
    ok, fail, skip = upsert_docs(args.base, token, know_id, docs, existing, conn=conn, serial=True)
    print("完成：成功", ok, "跳过", skip, "失败", fail)
    cur.close()
    conn.close()


if __name__ == "__main__":
    main()
