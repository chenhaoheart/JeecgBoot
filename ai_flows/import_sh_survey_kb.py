# -*- coding: utf-8 -*-
"""
把一期上传平台 PDF 调查成果报告导入 JeecgBoot AI 知识库「山洪调查成果」。

默认导入目录下全部 PDF。每份报告按「摘要 + 第X章」切成多条文档
（所以列表条数会多于报告份数，例如 4 份报告会显示 24 条）。

用法：
  python import_sh_survey_kb.py
  python import_sh_survey_kb.py --only-four   # 只导入最初 4 份
  python import_sh_survey_kb.py --word-patch  # 用 13 份 Word 补 PDF 失败章节
  python import_sh_survey_kb.py --files a.docx b.docx --serial
  python import_sh_survey_kb.py --wipe        # 先清空知识库再全量导入
  python import_sh_survey_kb.py --skip-embed
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

import pymysql
from pypdf import PdfReader

KNOW_ID = "2099990000000000101"
APP_ID = "2099990000000000102"
KNOW_NAME = "山洪调查成果"
APP_NAME = "山洪调查成果问答"
EMBED_ID_VOLC = "2099990000000000103"
EMBED_ID_ZHIPU = "1897884353107611650"
EMBED_ID_OPENAI = "1891459707122499586"
LLM_ID = "2099900548148649986"

DEFAULT_FILES = [
    "001甘肃省白龙江水系大崖沟流域.pdf",
    "002甘肃省白龙江水系塔子沟流域.pdf",
    "003甘肃省白龙江水系噶沟里流域.pdf",
    "004甘肃省白龙江水系南峪沟流域.pdf",
]

# PDF 失败且 Word 目录有对应文件的 13 份（补 15 条缺失章节）
WORD_PATCH_FILES = [
    "023甘肃省洮河水系大峪沟流域.docx",
    "024甘肃省洮河水系什巴沟流域.docx",
    "026甘肃省洮河水系木耳沟流域.docx",
    "035甘肃省洮河水系大扎沟流域.docx",
    "061甘肃省大夏河水系央曲河流域.docx",
    "065甘肃省白龙江水系拱坝河流域.docx",
    "066甘肃省白龙江水系阿坞河流域.docx",
    "067甘肃省白龙江水系官鹅沟流域.docx",
    "072甘肃省白龙江水系岷江流域.docx",
    "074甘肃省嘉陵江水系青崖沟流域.docx",
    "077甘肃省白龙江水系石门沟流域002.docx",
    "084甘肃省洮河水系立蔵沟流域001.docx",
    "107甘肃省渭河水系耤河流域001.docx",
]
MAX_CONTENT_BYTES = 60000
MAX_CONTENT_CHARS = 22000  # 知识库分段约 3000 字/块，控制在 10 块以内以免方舟 input 超限
HEAD_MAP = {
    "成果报告摘要": "成果报告摘要",
    "流域概况": "第一章 流域概况",
    "前期准备": "第二章 前期准备",
    "风险隐患调查": "第三章 风险隐患调查",
    "风险隐患影响分析": "第四章 风险隐患影响分析",
    "成果与建议": "第五章 成果与建议",
    "总结与建议": "第五章 总结与建议",
}

HEADER_RE = re.compile(
    r"甘肃省小流域山洪灾害[“\"']?四预[”\"']?能力建设项目\s*"
    r"|北京慧图科技（集团）股份有限公司\s*\d*"
    r"|===== PAGE \d+ =====\s*",
    re.M,
)
CHAPTER_RE = re.compile(r"^第([一二三四五六七八九十]+)章\s+(\S[^\n.]{0,40})$", re.M)
WATERSHED_RE = re.compile(r"([\u4e00-\u9fa5A-Za-z0-9]{2,20}流域)")


def db():
    return pymysql.connect(
        host=os.environ.get("DB_HOST", "127.0.0.1"),
        user=os.environ.get("DB_USER", "root"),
        password=os.environ.get("DB_PASSWORD", "123456"),
        database=os.environ.get("DB_NAME", "jeecg-boot"),
        charset="utf8mb4",
        autocommit=False,
    )


def pick_embed_id(cur) -> str:
    # 优先已激活向量模型（现网知识库在用），避免导入时换模型导致向量库维度不一致
    for preferred in (EMBED_ID_VOLC, EMBED_ID_OPENAI, EMBED_ID_ZHIPU):
        cur.execute(
            "SELECT id FROM airag_model WHERE id=%s AND model_type='EMBED' AND activate_flag=1",
            (preferred,),
        )
        row = cur.fetchone()
        if row:
            return row[0]
    cur.execute(
        "SELECT id FROM airag_model WHERE model_type='EMBED' AND activate_flag=1 LIMIT 1"
    )
    row = cur.fetchone()
    if not row:
        raise SystemExit("没有可用的向量模型（airag_model.model_type=EMBED 且已激活）")
    return row[0]


def extract_pdf(path: str) -> str:
    reader = PdfReader(path)
    parts = []
    for i, page in enumerate(reader.pages):
        t = page.extract_text() or ""
        parts.append("\n\n===== PAGE %d =====\n%s" % (i + 1, t))
    return sanitize_text("".join(parts))


def sanitize_text(text: str) -> str:
    if not text:
        return ""
    return text.encode("utf-8", "replace").decode("utf-8")


def _norm_head(text: str) -> str:
    t = re.sub(r"\s+", "", (text or "").strip())
    t = re.sub(r"^[0-9一二三四五六七八九十]+[、.\s]*", "", t)
    return t


def _table_to_text(table) -> str:
    rows = []
    for row in table.rows:
        cells = [" ".join(c.text.split()) for c in row.cells]
        if any(cells):
            rows.append(" | ".join(cells))
    return "\n".join(rows)


def extract_docx(path: str) -> str:
    from docx import Document
    from docx.oxml.ns import qn
    from docx.table import Table
    from docx.text.paragraph import Paragraph

    doc = Document(path)
    parts = []
    for child in doc.element.body.iterchildren():
        if child.tag == qn("w:p"):
            p = Paragraph(child, doc)
            text = (p.text or "").strip()
            if not text:
                continue
            style = (p.style.name if p.style else "") or ""
            if style.lower().startswith("toc"):
                continue
            mapped = HEAD_MAP.get(_norm_head(text))
            if mapped and not text.startswith("第"):
                parts.append("\n%s\n" % mapped)
            else:
                parts.append(text)
        elif child.tag == qn("w:tbl"):
            tbl = _table_to_text(Table(child, doc))
            if tbl:
                parts.append(tbl)
    return sanitize_text("\n".join(parts))


def clean_text(text: str) -> str:
    text = sanitize_text(text)
    text = HEADER_RE.sub("\n", text)
    text = re.sub(r"[ \t]+\n", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_oversize(docs: list[dict]) -> list[dict]:
    """MySQL content 是 TEXT，超约 60KB 时拆成「续N」，首篇保留原标题。"""
    out = []
    for doc in docs:
        content = sanitize_text(doc["content"])
        too_big = (
            len(content.encode("utf-8")) > MAX_CONTENT_BYTES
            or len(content) > MAX_CONTENT_CHARS
        )
        if not too_big:
            doc["content"] = content
            out.append(doc)
            continue
        limit_chars = MAX_CONTENT_CHARS
        chunks, buf = [], ""
        for para in content.split("\n"):
            piece = (buf + "\n" + para) if buf else para
            if (len(piece) > limit_chars or len(piece.encode("utf-8")) > MAX_CONTENT_BYTES) and buf:
                chunks.append(buf)
                buf = para
            else:
                buf = piece
        if buf:
            chunks.append(buf)
        for i, chunk in enumerate(chunks):
            title = doc["title"] if i == 0 else "%s（续%d）" % (doc["title"], i + 1)
            if len(title) > 100:
                title = title[:100]
            out.append({"title": title, "content": chunk})
            print("  超长拆分：", title, "chars=", len(chunk))
    return out


def watershed_from_name(filename: str) -> str:
    stem = os.path.splitext(os.path.basename(filename))[0]
    stem = re.sub(r"\(\d+\)$", "", stem).strip()
    m = re.search(r"((?:北川河|湟水|大通河)\d+（[^）]+）)", stem)
    if m:
        return m.group(1)
    stem = re.sub(r"^\d+", "", stem)
    m = re.search(r"水系(.+流域)$", stem)
    if m:
        return m.group(1)
    m = re.search(r"([\u4e00-\u9fa5]{2,12}流域)$", stem)
    return m.group(1) if m else stem


def split_docs(filename: str, raw: str) -> list[dict]:
    ws = watershed_from_name(filename)
    text = clean_text(raw)
    docs = []

    toc = text.find("目 录")
    if toc < 0:
        toc = text.find("目录")
    if toc < 0:
        m0 = CHAPTER_RE.search(text)
        toc = m0.start() if m0 else -1
    abstract_end = toc if toc > 0 else len(text)
    abstract = text[:abstract_end]
    abs_hit = re.search(r"成果报告摘要([\s\S]+)$", abstract)
    if abs_hit:
            abs_body = abs_hit.group(1).strip()
            if len(abs_body) > 80:
                docs.append({
                    "title": "%s · 成果报告摘要" % ws,
                    "content": "流域：%s\n章节：成果报告摘要\n来源：%s\n\n%s" % (ws, filename, sanitize_text(abs_body)),
                })

    body = text[toc:] if toc > 0 else text
    # 丢掉目录里带省略号的「第X章 … 页码」，只切正文标题
    matches = list(CHAPTER_RE.finditer(body))
    for i, m in enumerate(matches):
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        title = m.group(0).strip()
        chunk = body[start:end].strip()
        if len(chunk) < 80:
            continue
        docs.append({
            "title": "%s · %s" % (ws, title),
            "content": "流域：%s\n章节：%s\n来源：%s\n\n%s" % (ws, title, filename, sanitize_text(chunk)),
        })
    if not docs:
        docs.append({
            "title": "%s · 调查成果报告" % ws,
            "content": "流域：%s\n来源：%s\n\n%s" % (ws, filename, sanitize_text(text[:20000])),
        })
    return docs


def ensure_knowledge(cur, embed_id: str):
    meta_obj = {
        "enableSegment": True,
        "segmentStrategy": "auto",
        "maxSegment": 3000,
        "overlap": 15,
    }
    descr = "甘肃省小流域山洪灾害一期上传平台调查成果报告。按流域+章节切分，供检索问答与报告写作体例参考。"
    cur.execute("SELECT id, metadata FROM airag_knowledge WHERE id=%s OR name=%s LIMIT 1", (KNOW_ID, KNOW_NAME))
    row = cur.fetchone()
    if row:
        kid = row[0]
        try:
            old_meta = json.loads(row[1] or "{}")
            if old_meta.get("maxSegment"):
                meta_obj["maxSegment"] = old_meta["maxSegment"]
        except Exception:
            pass
        meta = json.dumps(meta_obj, ensure_ascii=False)
        cur.execute(
            "UPDATE airag_knowledge SET name=%s, descr=%s, embed_id=%s, status='enable', type='knowledge', metadata=%s "
            "WHERE id=%s",
            (KNOW_NAME, descr, embed_id, meta, kid),
        )
        return kid
    meta = json.dumps(meta_obj, ensure_ascii=False)
    cur.execute(
        "INSERT INTO airag_knowledge (id, create_by, create_time, sys_org_code, tenant_id, name, descr, embed_id, status, type, metadata) "
        "VALUES (%s,'admin',NOW(),'A04','0',%s,%s,%s,'enable','knowledge',%s)",
        (KNOW_ID, KNOW_NAME, descr, embed_id, meta),
    )
    return KNOW_ID


def ensure_app(cur, know_id: str):
    prompt = (
        "你是「山洪调查成果」知识库助手。"
        "有【检索到的一期调查成果】时：数字、对象名称、建议必须来自片段，不要编造。"
        "有【检索未命中引导】时：禁止只回复「知识库未收录」。必须按引导列出可查流域和示例问法，请用户点名流域和章节。"
        "问是否联网或模型干什么：一句话说明不访问外网、只检索已入库报告，然后给出可查流域和示例，不要反复强调规则。"
    )
    prologue = (
        "我是山洪调查成果助手，已入库一期上传平台各小流域调查报告。"
        "请直接问「某某流域」的摘要、风险隐患或建议；未点名时我会列出目前可查的流域。"
    )
    preset = json.dumps([
        "大崖沟流域报告摘要写了什么？",
        "塔子沟识别出哪些风险隐患？",
        "噶沟里的监测预警建议是什么？",
        "南峪沟第一章流域概况怎么写的？",
    ], ensure_ascii=False)
    rag_meta = json.dumps({"topNumber": 4, "similarity": 0.45, "timeout": 60}, ensure_ascii=False)
    cur.execute("SELECT id FROM airag_app WHERE id=%s OR name=%s LIMIT 1", (APP_ID, APP_NAME))
    row = cur.fetchone()
    if row:
        aid = row[0]
        cur.execute(
            "UPDATE airag_app SET name=%s, descr=%s, type='chatSimple', prologue=%s, prompt=%s, "
            "model_id=%s, knowledge_ids=%s, status='enable', msg_num=8, preset_question=%s, metadata=%s WHERE id=%s",
            (APP_NAME, KNOW_NAME, prologue, prompt, LLM_ID, know_id, preset, rag_meta, aid),
        )
        return aid
    cur.execute(
        "INSERT INTO airag_app (id, create_by, create_time, sys_org_code, tenant_id, name, descr, type, "
        "prologue, prompt, model_id, knowledge_ids, status, msg_num, preset_question, metadata) "
        "VALUES (%s,'admin',NOW(),'A04','0',%s,%s,'chatSimple',%s,%s,%s,%s,'enable',8,%s,%s)",
        (APP_ID, APP_NAME, KNOW_NAME, prologue, prompt, LLM_ID, know_id, preset, rag_meta),
    )
    return APP_ID


def login(base: str, user: str, password: str) -> str:
    payload = json.dumps({"username": user, "password": password}).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + "/sys/login",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    if not body.get("success"):
        raise RuntimeError("登录失败：" + str(body.get("message")))
    token = (body.get("result") or {}).get("token")
    if not token:
        raise RuntimeError("登录成功但未返回 token")
    return token


def api_post(base: str, token: str, path: str, data: dict, timeout=180):
    payload = json.dumps(data, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + path,
        data=payload,
        headers={
            "Content-Type": "application/json;charset=UTF-8",
            "X-Access-Token": token,
            "X-Tenant-Id": "0",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def list_existing_titles(cur, know_id: str) -> set[str]:
    cur.execute("SELECT title FROM airag_knowledge_doc WHERE knowledge_id=%s", (know_id,))
    return {r[0] for r in cur.fetchall()}


def collect_pdfs(folder: str, only_four: bool) -> list[tuple[str, str]]:
    files = []
    if only_four:
        names = DEFAULT_FILES
    else:
        names = sorted(
            n for n in os.listdir(folder) if n.lower().endswith(".pdf")
        )
    for name in names:
        path = os.path.join(folder, name)
        if not os.path.isfile(path):
            print("找不到文件：", path)
            continue
        files.append((name, path))
    return files


def collect_word_patch(folder: str) -> list[tuple[str, str]]:
    files = []
    for name in WORD_PATCH_FILES:
        path = os.path.join(folder, name)
        if not os.path.isfile(path):
            print("找不到 Word：", path)
            continue
        files.append((name, path))
    return files


def wipe_docs(base: str, token: str, know_id: str):
    req = urllib.request.Request(
        base.rstrip("/") + "/airag/knowledge/doc/deleteAll?knowId=" + know_id,
        headers={"X-Access-Token": token, "X-Tenant-Id": "0"},
        method="DELETE",
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            print("已清空原文档", body.get("message") or body.get("success"))
    except Exception as e:
        print("清空原文档失败：", e)


def poll_doc(conn, know_id: str, title: str, timeout: int = 240):
    """等异步向量化结束。REPEATABLE READ 下每次查询前提交，才能看到别的事务写入的状态。"""
    cur = conn.cursor()
    deadline = time.time() + timeout
    row = None
    while time.time() < deadline:
        conn.commit()
        cur.execute(
            "SELECT id, status, LEFT(metadata, 240) FROM airag_knowledge_doc "
            "WHERE knowledge_id=%s AND title=%s ORDER BY create_time DESC LIMIT 1",
            (know_id, title),
        )
        row = cur.fetchone()
        if row and row[1] in ("complete", "failed"):
            return row
        time.sleep(2)
    return row


def rebuild_doc(base: str, token: str, doc_id: str):
    req = urllib.request.Request(
        base.rstrip("/") + "/airag/knowledge/doc/rebuild?docIds=" + doc_id,
        data=b"",
        headers={"X-Access-Token": token, "X-Tenant-Id": "0", "Content-Type": "application/json"},
        method="PUT",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def upsert_docs(base, token, know_id, docs, existing, conn=None, serial=False):
    ok, fail, skip = 0, 0, 0
    for doc in docs:
        if len(doc["title"]) > 100:
            doc["title"] = doc["title"][:100]
        if doc["title"] in existing:
            skip += 1
            continue
        body = {
            "knowledgeId": know_id,
            "title": doc["title"],
            "type": "text",
            "content": doc["content"],
            "metadata": json.dumps({"useKnowledgeDefault": True}, ensure_ascii=False),
        }
        try:
            res = api_post(base, token, "/airag/knowledge/doc/edit", body)
            if not res.get("success"):
                fail += 1
                print("  失败：", doc["title"], res.get("message"))
                continue
            if not serial or conn is None:
                ok += 1
                existing[doc["title"]] = doc["title"]
                print("  已提交：", doc["title"], "chars=", len(doc["content"]))
                continue
            settled = None
            for attempt in range(3):
                settled = poll_doc(conn, know_id, doc["title"])
                if not settled or settled[1] == "complete":
                    break
                reason = settled[2] or ""
                if "RateLimit" not in reason and "频繁" not in reason and "input limit" not in reason:
                    break
                print("  重试向量化：", doc["title"], "attempt", attempt + 1)
                time.sleep(12)
                try:
                    rebuild_doc(base, token, settled[0])
                except Exception as e:
                    print("  重建请求失败：", e)
            if settled and settled[1] == "complete":
                ok += 1
                existing[doc["title"]] = doc["title"]
                print("  已向量化：", doc["title"], "chars=", len(doc["content"]))
            else:
                fail += 1
                print("  向量化未完成：", doc["title"], settled[1] if settled else "timeout", (settled[2] if settled else ""))
        except Exception as e:
            fail += 1
            print("  异常：", doc["title"], e)
        time.sleep(0.3 if not serial else 1)
    return ok, fail, skip


def insert_docs_sql(cur, know_id: str, docs: list[dict]):
    """后端不可登录时的兜底：只写文档，状态 draft，需在页面点重建。"""
    for doc in docs:
        cur.execute(
            "SELECT id FROM airag_knowledge_doc WHERE knowledge_id=%s AND title=%s LIMIT 1",
            (know_id, doc["title"]),
        )
        row = cur.fetchone()
        meta = json.dumps({"useKnowledgeDefault": True}, ensure_ascii=False)
        if row:
            cur.execute(
                "UPDATE airag_knowledge_doc SET content=%s, type='text', status='draft', metadata=%s WHERE id=%s",
                (doc["content"], meta, row[0]),
            )
        else:
            import uuid
            did = "20" + uuid.uuid4().hex[:17]
            cur.execute(
                "INSERT INTO airag_knowledge_doc (id, create_by, create_time, sys_org_code, tenant_id, "
                "knowledge_id, title, type, content, status, metadata) "
                "VALUES (%s,'admin',NOW(),'A04','0',%s,%s,'text',%s,'draft',%s)",
                (did, know_id, doc["title"], doc["content"], meta),
            )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=r"F:\省平台数据上报\一期上传平台报告-PDF版本")
    ap.add_argument("--word-dir", default=r"F:\省平台数据上报\一期省平台报告_word版本")
    ap.add_argument("--word-patch", action="store_true", help="用 Word 补 PDF 失败的 15 条章节")
    ap.add_argument("--only-four", action="store_true", help="只导入最初 4 份 PDF")
    ap.add_argument("--wipe", action="store_true", help="先清空知识库再导入")
    ap.add_argument("--files", nargs="*", help="指定要导入的 docx/pdf，按章节写入现有知识库")
    ap.add_argument("--serial", action="store_true", help="一篇向量化完成后再提交下一篇，避开接口限流")
    ap.add_argument("--skip-embed", action="store_true")
    ap.add_argument("--base", default=os.environ.get("JEECG_BASE", "http://127.0.0.1:8080/jeecg-boot"))
    ap.add_argument("--user", default=os.environ.get("JEECG_USER", "admin"))
    ap.add_argument("--password", default=os.environ.get("JEECG_PASSWORD", "123456"))
    args = ap.parse_args()

    if args.files:
        files = []
        for path in args.files:
            if not os.path.isfile(path):
                print("找不到文件：", path)
                continue
            files.append((os.path.basename(path), path))
        if not files:
            print("没有可导入的文件")
            sys.exit(1)
        print("指定文件", len(files), "份")
        extract_fn = None
    elif args.word_patch:
        files = collect_word_patch(args.word_dir)
        if not files:
            print("没有可补的 Word：", args.word_dir)
            sys.exit(1)
        print("Word 补丁", len(files), "份（只写入知识库中尚不存在的章节）")
        extract_fn = extract_docx
    else:
        files = collect_pdfs(args.dir, args.only_four)
        if not files:
            print("目录里没有 PDF：", args.dir)
            sys.exit(1)
        print("待处理 PDF", len(files), "份")
        extract_fn = extract_pdf

    all_docs = []
    for name, path in files:
        print("抽取", name, "...")
        try:
            if extract_fn is None:
                fn = extract_docx if name.lower().endswith(".docx") else extract_pdf
            else:
                fn = extract_fn
            raw = fn(path)
            docs = split_oversize(split_docs(name, raw))
        except Exception as e:
            print("  抽取失败：", e)
            continue
        print("  ->", len(docs), "篇", [d["title"] for d in docs])
        all_docs.extend(docs)

    conn = db()
    cur = conn.cursor()
    embed_id = pick_embed_id(cur)
    know_id = ensure_knowledge(cur, embed_id)
    app_id = ensure_app(cur, know_id)
    conn.commit()
    print("知识库", know_id, KNOW_NAME, "embed=", embed_id)
    print("AI应用", app_id, APP_NAME)
    print("切分后文档", len(all_docs), "条")

    if args.skip_embed:
        insert_docs_sql(cur, know_id, all_docs)
        conn.commit()
        print("已写入草稿。请到「AI知识库」点重建向量。")
        cur.close(); conn.close()
        return

    try:
        token = login(args.base, args.user, args.password)
        if args.wipe:
            wipe_docs(args.base, token, know_id)
        existing_titles = list_existing_titles(cur, know_id)
        existing = {t: t for t in existing_titles}
        print("已存在", len(existing), "条，本次跳过同名")
        ok, fail, skip = upsert_docs(
            args.base, token, know_id, all_docs, existing, conn=conn, serial=args.serial
        )
        print("完成：成功", ok, "跳过", skip, "失败", fail)
    except Exception as e:
        print("接口向量化失败，改为 SQL 草稿入库：", e)
        insert_docs_sql(cur, know_id, all_docs)
        conn.commit()
        print("已写入草稿。请登录系统后打开知识库点击重建。")

    cur.close()
    conn.close()
    print("可在「AI应用」打开「%s」提问；山洪报告助手配置 shreport.knowledge.id=%s" % (APP_NAME, know_id))


if __name__ == "__main__":
    main()

