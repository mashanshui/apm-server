"""为 Native 页面布局核对提供只监听本机的合成只读 API。"""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import urlparse

# 共享样例仅包含合成数据，不包含设备或用户凭据。
ROOT = Path(__file__).resolve().parents[3]
EVENT = json.loads((ROOT / "docs/examples/native-crash-batch.json").read_text(encoding="utf-8"))["events"][0]
CRASH = EVENT["crash"]
DIAGNOSTICS = CRASH["diagnostics"]
for thread in DIAGNOSTICS["otherThreads"]["threads"]:
    # 查询响应与上报载荷使用不同堆栈外壳；不改变合成线程身份。
    frames = thread.pop("frames")
    thread["stack"] = {"format": "raw_frames", "content": frames}
    thread["symbolicationStatus"] = "pending" if frames else "not_applicable"
    thread["symbolicationReason"] = "native_symbolication_deferred" if frames else None

# 仅用于页面验证的合成应用和事件详情。
APP = {"appId": "demo", "name": "合成演示应用", "description": None, "packageName": "com.example.app",
       "role": "OWNER", "createdAt": "2026-11-09T00:00:00Z", "updatedAt": "2026-11-09T00:00:00Z"}
DETAIL = {**EVENT, "appId": "demo", "eventId": "native-demo", "occurredAt": "2026-11-09T00:00:00Z",
          "receivedAt": "2026-11-09T00:00:01Z", "exceptionType": "SIGSEGV", "fingerprint": "native-demo-fingerprint",
          "fingerprintVersion": "native-tmp-v1", "symbolicationStatus": "pending",
          "symbolicationReason": "native_symbolication_deferred", "groupingKind": "temporary", "crashKind": "native",
          "fatal": True, "symbolFileId": None, "symbolFileRevision": None, "symbolFileSha256": None, "processedAt": None,
          "stack": {"format": "raw_native", "content": CRASH["nativeCrash"]}, "diagnostics": DIAGNOSTICS}
DETAIL.pop("crash")


class Handler(BaseHTTPRequestHandler):
    """仅返回固定合成对象，未列出的路径统一返回 404。"""

    def do_GET(self):
        """处理页面需要的会话、应用和 Native 详情读取。"""
        # 只根据路径选择静态合成对象，不记录请求和凭据。
        path = urlparse(self.path).path
        if path == "/api/v1/session":
            value = {"id": "demo-user", "email": "demo@example.test", "displayName": "演示用户"}
        elif path == "/api/v1/apps":
            value = [APP]
        elif path == "/api/v1/apps/demo":
            value = APP
        elif path == "/api/v1/apps/demo/crashes/events/native-demo":
            value = DETAIL
        else:
            self.send_error(404)
            return
        payload = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        """不打印包含请求参数的访问日志。"""


if __name__ == "__main__":
    # 固定绑定 loopback，避免把测试入口暴露到局域网。
    HTTPServer(("127.0.0.1", 18731), Handler).serve_forever()
