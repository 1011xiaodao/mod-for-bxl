#!/usr/bin/env python3
"""maven.neoforged.net 本地缓存反代（开发机网络专用工具）。

背景：本网络环境下 Java TLS 指纹访问 maven.neoforged.net 会被连接重置
（curl 正常，其余镜像源均正常）。本代理把该域名的请求转由 curl 完成并落盘缓存，
Gradle 侧改用纯 HTTP 访问 http://127.0.0.1:8528，绕开 Java TLS。

用法：python tools/neoforged_maven_proxy.py   （后台常驻；构建期间保持运行）
"""
import argparse
import hashlib
import http.server
import json
import os
import subprocess
import sys
import threading
import time
import urllib.parse

UPSTREAM = "https://maven.neoforged.net"
NEGATIVE_TTL = 600  # 404 负缓存秒数

_file_locks = {}
_locks_guard = threading.Lock()


def _lock_for(key):
    with _locks_guard:
        return _file_locks.setdefault(key, threading.Lock())


class ProxyHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stdout.write("[proxy] %s\n" % (fmt % args))
        sys.stdout.flush()

    def do_HEAD(self):
        self._handle(send_body=False)

    def do_GET(self):
        self._handle(send_body=True)

    def _handle(self, send_body):
        parsed = urllib.parse.urlsplit(self.path)
        rel = urllib.parse.unquote(parsed.path).lstrip("/")
        if not rel:
            self._plain(400, b"empty path", send_body)
            return
        upstream_url = "%s/%s" % (UPSTREAM, rel)
        cache_key = hashlib.sha1(upstream_url.encode()).hexdigest()
        cache_file = os.path.join(self.server.cache_dir, cache_key + ".data")
        meta_file = os.path.join(self.server.cache_dir, cache_key + ".meta.json")

        if not send_body:
            self._handle_head(upstream_url, cache_file, meta_file)
            return
        with _lock_for(cache_key):
            status, path, size = self._fetch_cached(upstream_url, cache_file, meta_file)
        if status == 200:
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(path, "rb") as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        else:
            self._plain(status if status in (404, 403) else 502,
                        json.dumps({"upstream": upstream_url, "status": status}).encode(), True)

    def _handle_head(self, upstream_url, cache_file, meta_file):
        # 已缓存：直接用缓存大小应答
        if os.path.exists(cache_file) and os.path.exists(meta_file):
            try:
                with open(meta_file, "r", encoding="utf-8") as f:
                    meta = json.load(f)
                if meta.get("status") == 200:
                    self.send_response(200)
                    self.send_header("Content-Length", str(os.path.getsize(cache_file)))
                    self.end_headers()
                    return
                if time.time() - meta.get("ts", 0) < NEGATIVE_TTL:
                    self._plain(meta.get("status", 404), b"negative-cached", False)
                    return
            except (OSError, ValueError):
                pass
        # 未缓存：轻量 HEAD 探测（不做全量下载），结果不做正缓存
        probe = subprocess.run(
            ["curl", "-sI", "--retry", "10", "--retry-all-errors", "--retry-delay", "2",
             "--connect-timeout", "20", "--max-time", "60", upstream_url],
            capture_output=True, text=True, timeout=90)
        status, length = self._parse_head(probe.stdout or "")
        if status == 200:
            self.send_response(200)
            if length:
                self.send_header("Content-Length", str(length))
            self.end_headers()
        else:
            self._plain(status if status in (404, 403) else 502,
                        json.dumps({"upstream": upstream_url, "status": status}).encode(), False)

    @staticmethod
    def _parse_head(text):
        status, length = 0, 0
        for line in text.splitlines():
            low = line.strip().lower()
            if low.startswith("http/") and status == 0:
                parts = line.split()
                if len(parts) >= 2 and parts[1].isdigit():
                    status = int(parts[1])
            elif low.startswith("content-length:"):
                try:
                    length = int(low.split(":", 1)[1].strip())
                except ValueError:
                    pass
        return status, length

    def _fetch_cached(self, upstream_url, cache_file, meta_file):
        if os.path.exists(cache_file) and os.path.exists(meta_file):
            try:
                with open(meta_file, "r", encoding="utf-8") as f:
                    meta = json.load(f)
                if meta.get("status") == 200:
                    return 200, cache_file, os.path.getsize(cache_file)
                if time.time() - meta.get("ts", 0) < NEGATIVE_TTL:
                    return meta.get("status", 404), None, 0
            except (OSError, ValueError):
                pass
        tmp = cache_file + ".tmp" + str(threading.get_ident())
        cmd = ["curl", "-s", "--retry", "12", "--retry-all-errors", "--retry-delay", "2",
               "--connect-timeout", "20", "--max-time", "900",
               "-w", "%{http_code}", "-o", tmp, upstream_url]
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=960)
            lines = [x for x in (out.stdout or "").strip().splitlines() if x.strip()]
            status = int(lines[-1]) if lines and lines[-1].isdigit() else 0
        except (subprocess.TimeoutExpired, ValueError, OSError):
            status = 0
        if status == 200 and os.path.exists(tmp):
            os.replace(tmp, cache_file)
            with open(meta_file, "w", encoding="utf-8") as f:
                json.dump({"status": 200, "ts": time.time(), "url": upstream_url}, f)
            return 200, cache_file, os.path.getsize(cache_file)
        if os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass
        if status == 0:
            status = 503
        with open(meta_file, "w", encoding="utf-8") as f:
            json.dump({"status": status, "ts": time.time(), "url": upstream_url}, f)
        return status, None, 0

    def _plain(self, code, payload, send_body):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if send_body:
            self.wfile.write(payload)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8528)
    parser.add_argument("--cache", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                        "..", "..", ".tools", "proxy_cache"))
    args = parser.parse_args()
    cache_dir = os.path.abspath(args.cache)
    os.makedirs(cache_dir, exist_ok=True)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", args.port), ProxyHandler)
    server.cache_dir = cache_dir
    print("[proxy] maven.neoforged.net 缓存反代已启动：http://127.0.0.1:%d -> %s（缓存 %s）"
          % (args.port, UPSTREAM, cache_dir))
    sys.stdout.flush()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
