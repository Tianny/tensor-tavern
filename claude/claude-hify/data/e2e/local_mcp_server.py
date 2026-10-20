#!/usr/bin/env python3
"""本地 MCP 服务（Streamable HTTP 子集），用于 Hify 真实链路验证。

手写 JSON-RPC 2.0：initialize / notifications/initialized / tools/list / tools/call。
tools/list 故意用 SSE 帧响应（text/event-stream 无 charset），验证客户端 UTF-8 解码。
只依赖标准库。验证用途，监听 127.0.0.1。
"""
import json
import re
import datetime
import zoneinfo
from http.server import BaseHTTPRequestHandler, HTTPServer

TOOLS = [
    {
        "name": "get_current_time",
        "description": "获取当前的日期和时间",
        "inputSchema": {
            "type": "object",
            "properties": {
                "timezone": {"type": "string", "description": "IANA 时区名，默认 Asia/Shanghai"}
            },
        },
    },
    {
        "name": "calculate",
        "description": "计算一个加减乘除算术表达式，例如 23*7+100",
        "inputSchema": {
            "type": "object",
            "properties": {"expression": {"type": "string", "description": "算术表达式"}},
            "required": ["expression"],
        },
    },
]


class McpHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        req = json.loads(self.rfile.read(length))
        method = req.get("method", "")
        rid = req.get("id")

        if method == "initialize":
            self._send_json(
                {
                    "jsonrpc": "2.0",
                    "id": rid,
                    "result": {
                        "protocolVersion": "2025-03-26",
                        "capabilities": {},
                        "serverInfo": {"name": "local-tools", "version": "1.0"},
                    },
                },
                extra_headers={"Mcp-Session-Id": "local-session-1"},
            )
        elif method == "notifications/initialized":
            self.send_response(202)
            self.end_headers()
        elif method == "tools/list":
            # SSE 帧响应（无 charset），覆盖客户端的 UTF-8 强制解码路径
            self._send_sse({"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            name = req["params"]["name"]
            args = req["params"].get("arguments", {})
            if name == "get_current_time":
                tz = args.get("timezone", "Asia/Shanghai")
                try:
                    now = datetime.datetime.now(zoneinfo.ZoneInfo(tz))
                    text = now.strftime("%Y-%m-%d %H:%M:%S") + "（时区 " + tz + "）"
                except Exception:
                    text = "无效时区：" + str(tz)
            elif name == "calculate":
                expression = str(args.get("expression", ""))
                if re.fullmatch(r"[0-9+\-*/(). ]+", expression) and expression.strip():
                    text = str(eval(expression))  # 本地验证服务：正则白名单 + eval
                else:
                    text = "表达式不合法，只支持数字和 + - * / ( )"
            else:
                self._send_json(
                    {
                        "jsonrpc": "2.0",
                        "id": rid,
                        "error": {"code": -32602, "message": "Unknown tool: " + name},
                    }
                )
                return
            self._send_json(
                {
                    "jsonrpc": "2.0",
                    "id": rid,
                    "result": {"content": [{"type": "text", "text": text}], "isError": False},
                }
            )
        else:
            self._send_json(
                {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "Method not found"}}
            )

    def _send_json(self, obj, extra_headers=None):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        for k, v in (extra_headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_sse(self, obj):
        body = ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("[mcp-stub] " + fmt % args, flush=True)


if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 18930), McpHandler)
    print("[mcp-stub] listening on 127.0.0.1:18930/mcp", flush=True)
    server.serve_forever()
