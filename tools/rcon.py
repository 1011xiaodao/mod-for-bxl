#!/usr/bin/env python3
"""极简 RCON 客户端：向本地服务器发送命令并打印响应。
用法：python tools/rcon.py "colony create smoke_base" "colony info" ...
注意：原版 RCON 对请求方向的中文支持不稳定，命令参数请用 ASCII。
"""
import socket
import struct
import sys

HOST = "127.0.0.1"
PORT = 25575
PASSWORD = "pioneer_colony_dev"


def send(sock, req_id, ptype, payload: bytes):
    data = struct.pack("<ii", req_id, ptype) + payload + b"\x00\x00"
    sock.send(struct.pack("<i", len(data)) + data)


def recv(sock):
    raw = b""
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            raise EOFError("connection closed")
        raw += chunk
    (length,) = struct.unpack("<i", raw)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise EOFError("connection closed (body)")
        data += chunk
    rid, ptype = struct.unpack("<ii", data[:8])
    return rid, ptype, data[8:-2].decode("utf-8", "replace")


def main():
    commands = sys.argv[1:]
    if not commands:
        print("no commands given")
        return 1
    sock = socket.create_connection((HOST, PORT), timeout=20)
    try:
        send(sock, 1, 3, PASSWORD.encode())
        rid, _, _ = recv(sock)
        if rid == -1:
            print("RCON auth failed")
            return 1
        for i, cmd in enumerate(commands, start=2):
            send(sock, i, 2, cmd.encode("utf-8"))
            rid, _, body = recv(sock)
            print(f"$ {cmd}\n{body}")
        return 0
    finally:
        sock.close()


if __name__ == "__main__":
    sys.exit(main())
