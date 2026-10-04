# -*- coding: utf-8 -*-
"""本地 Mock 大模型服务(OpenAI 兼容 /chat/completions),仅用于测试山洪报告全链路流程。
- 意图解析请求(system 含"意图解析器")返回结构化意图 JSON
- 章节撰写请求返回带《章节标题》与数据表样例的 Markdown
测试完成后请停掉本服务并恢复模型配置。
"""
import json
import re
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 18080


def build_intent(user_msg: str) -> str:
    m = user_msg or ""
    if re.search(r"进度|状态|怎么样了|生成了吗|好了吗", m):
        return '{"action":"QUERY_STATUS","chapterNo":null,"wsmc":null,"requirement":null,"reply":"好的,我来查询当前报告进度。"}'
    if re.search(r"导出|下载", m) and re.search(r"[Ww]ord|文档", m):
        return '{"action":"EXPORT","chapterNo":null,"wsmc":null,"requirement":null,"reply":"好的,请在右侧面板点击导出Word。"}'
    chapter_no = 0
    num = re.search(r"第\s*([1-7一二三四五六七])\s*章", m)
    if num:
        g = num.group(1)
        chapter_no = ("一二三四五六七".find(g) + 1) if g in "一二三四五六七" else int(g)
    else:
        for i, k in enumerate(["流域概况", "前期准备", "外业调查", "影响分析", "历史灾害", "防治区", "成果"]):
            if k in m:
                chapter_no = i + 1
                break
    whole = bool(re.search(r"整份|全部|完整|整个", m)) or ("报告" in m and "生成" in m and "修改" not in m)
    ws = re.search(r"([\u4e00-\u9fa5A-Za-z0-9]{2,20}流域)", m)
    wsmc = ws.group(1) if ws else None
    if whole and not (chapter_no and "修改" in m):
        return json.dumps({"action": "GENERATE_ALL", "chapterNo": None, "wsmc": wsmc,
                           "requirement": m, "reply": "好的,我将依次生成全部7个章节,请稍候。"}, ensure_ascii=False)
    if chapter_no:
        return json.dumps({"action": "GENERATE_CHAPTER", "chapterNo": chapter_no, "wsmc": wsmc,
                           "requirement": m, "reply": f"好的,我将重新生成第{chapter_no}章并落实你的意见。"}, ensure_ascii=False)
    return json.dumps({"action": "CHAT", "chapterNo": None, "wsmc": wsmc, "requirement": None,
                       "reply": "收到。你可以对我说:生成整份报告 / 修改第三章:意见 / 查看进度。"}, ensure_ascii=False)


def build_chapter(user_msg: str) -> str:
    title_m = re.search(r"《(.+?)》", user_msg or "")
    title = title_m.group(1) if title_m else "报告章节"
    # 提取提示词中携带的数据表格行,验证 SQL 数据确实传入了 LLM
    data_lines = [ln.strip() for ln in (user_msg or "").splitlines()
                  if ln.strip().startswith("|") or re.search(r"：\s*\d", ln)]
    data_lines = [ln for ln in data_lines if "序号" not in ln or "---" not in ln][:12]
    lines = [f"# {title}", "",
             "> 本章节内容由【本地Mock测试模型】生成,用于验证报告生成全链路,非真实AI结果。", ""]
    if data_lines:
        lines += ["## 输入数据摘录(证明SQL数据已传入LLM节点)", ""]
        lines += data_lines
        lines.append("")
    lines += ["## 小结", "",
              f"本章({title})流程链路验证正常:start→SQL取数→LLM撰写→章节落库→待审核。",
              "如需真实AI撰写内容,请在AI模型配置中填入有效的大模型API Key后重新生成本章节。", ""]
    return "\n".join(lines)


def handle_messages(body):
    msgs = body.get("messages") or []
    system = " ".join(m.get("content", "") for m in msgs if m.get("role") == "system")
    user = next((m.get("content", "") for m in reversed(msgs) if m.get("role") == "user"), "")
    if "意图解析器" in system:
        # 只解析真正的用户消息(上下文行"当前报告状态:..."含"状态"等关键词会干扰判断)
        real = user.split("用户消息：")[-1].split("用户消息:")[-1]
        return build_intent(real)
    return build_chapter(user)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send_json(self, obj, status=200):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return self._send_json({"error": {"message": "bad json"}}, 400)
        content = handle_messages(body)
        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            chunks = [content[i:i + 40] for i in range(0, len(content), 40)] or [""]
            for c in chunks:
                ev = {"id": "mock", "object": "chat.completion.chunk", "created": 1700000000,
                      "model": body.get("model", "mock"), "choices": [{"index": 0, "delta": {"content": c}, "finish_reason": None}]}
                self.wfile.write(f"data: {json.dumps(ev, ensure_ascii=False)}\n\n".encode("utf-8"))
                self.wfile.flush()
            end = {"id": "mock", "object": "chat.completion.chunk", "created": 1700000000,
                   "model": body.get("model", "mock"), "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}
            self.wfile.write(b"data: " + json.dumps(end).encode("utf-8") + b"\n\n")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        else:
            self._send_json({"id": "mock", "object": "chat.completion", "created": 1700000000,
                             "model": body.get("model", "mock"),
                             "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                                          "finish_reason": "stop"}],
                             "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}})

    def do_GET(self):
        self._send_json({"data": [{"id": "mock"}], "object": "list"})


if __name__ == "__main__":
    print(f"Mock LLM server on http://127.0.0.1:{PORT}/v1/chat/completions")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
