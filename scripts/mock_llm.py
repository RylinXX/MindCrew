#!/usr/bin/env python3
"""MindCrew 本地 Mock LLM（OpenAI 兼容）：无真实 Key 时用于启动演示。"""
import json, time, re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def pick_answer(req):
    msgs = req.get("messages") or []
    try:
        import os, uuid
        os.makedirs("/tmp/mock-requests", exist_ok=True)
        with open(f"/tmp/mock-requests/{uuid.uuid4().hex}.json","w",encoding="utf-8") as f:
            json.dump(req, f, ensure_ascii=False)
    except Exception:
        pass
    text = "\n".join(m.get("content") if isinstance(m.get("content"), str) else json.dumps(m.get("content"), ensure_ascii=False) for m in msgs)
    # 查询改写：原样返回用户问题，保证检索查询质量（Mock 不应污染 rewrite）
    if "改写后的查询词" in text or "检索改写助手" in text:
        m = re.search(r"用户问题：\s*(.+?)\s*(?:\n\n|$)", text, re.S)
        return (m.group(1).strip() if m else "知识库检索")
    # 需要 JSON 输出的内部任务（分类 / 自检 / 规划）：返回空结构走各自兜底逻辑
    if ("JSON" in text or "json" in text) and ("分类" in text or "审查" in text or "评审" in text or "规划" in text):
        if "数组" in text or "Array" in text:
            return "[]"
        return "{}"
    return ANSWER

ANSWER = ("（本地演示模式）MindCrew 是一套基于 Agentic RAG 的企业知识问答系统，"
          "支持多格式知识入库、查询改写、多路召回、RRF 融合、精排重排、自检纠错与答案溯源。"
          "当前环境未配置真实大模型 API Key，因此由本地 Mock 模型返回示例答案；"
          "配置阿里云百炼或其他 OpenAI 兼容 Key 后即可获得基于知识库的真实回答。")

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") in ("/v1/models", "/models"):
            self._json(200, {"object": "list", "data": [{"id": "mock-qwen", "object": "model"}]})
        else:
            self._json(404, {"error": {"message": "not found"}})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw or b"{}")
        except Exception:
            req = {}
        path = self.path.rstrip("/")
        if path.endswith("/embeddings"):
            data = req.get("input")
            items = data if isinstance(data, list) else [data]
            vec = [0.01] * 1024
            self._json(200, {"object": "list", "data": [
                {"object": "embedding", "index": i, "embedding": vec} for i, _ in enumerate(items)
            ], "model": req.get("model", "text-embedding-v3"), "usage": {"prompt_tokens": 10, "total_tokens": 10}})
            return
        if not path.endswith("/chat/completions"):
            self._json(404, {"error": {"message": "not found"}})
            return

        stream = bool(req.get("stream"))
        model = req.get("model", "qwen-plus")
        answer = pick_answer(req)
        if not stream:
            self._json(200, {
                "id": "chatcmpl-mock", "object": "chat.completion", "created": int(time.time()),
                "model": model,
                "choices": [{"index": 0, "message": {"role": "assistant", "content": answer},
                             "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 32, "completion_tokens": len(answer) // 2, "total_tokens": 32 + len(answer) // 2}
            })
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        def chunk(delta, finish=None):
            obj = {"id": "chatcmpl-mock", "object": "chat.completion.chunk", "created": int(time.time()),
                   "model": model, "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
            self.wfile.write(("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode())
            self.wfile.flush()
        try:
            chunk({"role": "assistant", "content": ""})
            step = 8
            for i in range(0, len(answer), step):
                chunk({"content": answer[i:i + step]})
                time.sleep(0.02)
            chunk({}, "stop")
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 18090), Handler).serve_forever()
