#!/usr/bin/env python3
"""随拍互通协议 (SYNC.md v1) 的**独立实现** —— 用来当第二端做互操作测试。

它不是参考实现的翻译：帧编解码、JSON 字段、应答语义都按 SYNC.md 的描述重写一遍，
这样 Kotlin 那边「两个实现都有的同一个 bug」才藏不住。

    serve  起一个 TCP 照片库服务端（对端连进来拉照片）
    probe  作为客户端连进别人的服务端，跑一遍协议并打印结果

只依赖标准库。

    python tools/sync_peer.py serve --port 47481 --dir /tmp/peerlib --journal /tmp/peer_journal.json
    python tools/sync_peer.py probe --host 127.0.0.1 --port 27471
    # 翻页：跑两次比 ids，就能单机确认两页不重叠
    python tools/sync_peer.py probe --host 127.0.0.1 --port 27471 --offset 0  --limit 60
    python tools/sync_peer.py probe --host 127.0.0.1 --port 27471 --offset 60 --limit 60
"""

import argparse
import json
import os
import socket
import struct
import sys
import threading
import time
import uuid

PROTO_MAGIC = "SPDISCOVER1|"
MAX_BODY = 64 * 1024 * 1024

META_FIELDS = ("id", "name", "dateTaken", "width", "height", "sizeBytes")


# ---------------------------------------------------------------- 帧编解码
# 线格式（大端）：[4B headerLen][header JSON UTF-8][4B bodyLen][body]


def encode_frame(header: dict, body: bytes | None = None) -> bytes:
    hb = json.dumps(header, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    bb = body or b""
    if len(hb) > MAX_BODY or len(bb) > MAX_BODY:
        raise ValueError("frame too large")
    return struct.pack(">I", len(hb)) + hb + struct.pack(">I", len(bb)) + bb


class Truncated(ConnectionError):
    """对端在帧中间断掉（区别于「帧边界处的正常关闭」）。"""


def recv_exactly(conn: socket.socket, n: int) -> bytes | None:
    """读满 n 字节；对端在边界处正常关闭时返回 None。"""
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            if buf:
                raise Truncated()
            return None
        buf += chunk
    return bytes(buf)


def read_frame(conn: socket.socket):
    lenb = recv_exactly(conn, 4)
    if lenb is None:
        return None
    header_len = struct.unpack(">I", lenb)[0]
    if header_len > MAX_BODY:
        raise ConnectionError("header too large")
    header_bytes = recv_exactly(conn, header_len) if header_len else b""
    body_len = struct.unpack(">I", recv_exactly(conn, 4))[0]
    if body_len > MAX_BODY:
        raise ConnectionError("body too large")
    body = recv_exactly(conn, body_len) if body_len else None
    try:
        header = json.loads(header_bytes.decode("utf-8")) if header_bytes else {}
    except json.JSONDecodeError:
        header = None
    return header, body


# ---------------------------------------------------------------- 照片库


class Library:
    def __init__(self, directory: str):
        self.photos = []
        for i, name in enumerate(sorted(os.listdir(directory))):
            if not name.lower().endswith((".jpg", ".jpeg")):
                continue
            path = os.path.join(directory, name)
            w, h = self._probe(path)
            self.photos.append(
                {
                    "id": str(9000 + i),
                    "name": name,
                    "dateTaken": int(os.path.getmtime(path) * 1000),
                    "width": w,
                    "height": h,
                    "sizeBytes": os.path.getsize(path),
                    "_path": path,
                }
            )

    @staticmethod
    def _probe(path: str):
        """从 JPEG SOF 头里读宽高（不依赖 PIL）。"""
        with open(path, "rb") as fh:
            data = fh.read()
        i = 2
        while i < len(data) - 9:
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB):
                h, w = struct.unpack(">HH", data[i + 7 : i + 11])
                return w, h
            seg_len = struct.unpack(">H", data[i + 2 : i + 4])[0]
            i += 2 + seg_len
        return 0, 0

    def meta(self, photo_id: str):
        for p in self.photos:
            if p["id"] == photo_id:
                return p
        return None

    def public(self, photo: dict) -> dict:
        return {k: photo[k] for k in META_FIELDS}


# ---------------------------------------------------------------- 服务端


class Peer:
    """SYNC.md 的服务端角色。journal 记录连接数与请求数，供外部断言。"""

    BUCKET = {
        "list-photos": "list",
        "get-thumb": "thumb",
        "get-photo": "photo",
        "send-photo": "send",
        "hello": "hello",
    }

    def __init__(self, library: Library, name: str, device_id: str, journal: dict):
        self.lib = library
        self.name = name
        self.device_id = device_id
        self.journal = journal

    def handle(self, conn: socket.socket, addr):
        self.journal["connections"] = self.journal.get("connections", 0) + 1
        self.journal.setdefault("peers", []).append(f"{addr[0]}:{addr[1]}")
        # 按连接记账：「一次批量取图只用一条连接」这条只能这么量，
        # 光看总 connections 会把别的用例算进来。
        served = {"list": 0, "thumb": 0, "photo": 0, "send": 0, "other": 0, "hello": 0}
        self.journal.setdefault("perConnection", []).append(served)
        conn.settimeout(70)
        try:
            first = read_frame(conn)
            if first is None:
                return
            header, _ = first
            if not header or header.get("type") != "hello":
                conn.sendall(encode_frame({"type": (header or {}).get("type", ""), "ok": False, "error": "unknown type"}))
                return
            self.journal["hellos"] = self.journal.get("hellos", 0) + 1
            served["hello"] = 1
            self.journal["hello_fields"] = {k: header.get(k) for k in ("deviceId", "name", "platform")}
            conn.sendall(
                encode_frame(
                    {"type": "hello", "ok": True, "deviceId": self.device_id, "name": self.name, "platform": "python"}
                )
            )
            while True:
                frame = read_frame(conn)
                if frame is None:
                    return
                header, body = frame
                if header is None:
                    continue  # 帧头坏了：Kotlin 那边是 continue，照做
                served[self.BUCKET.get(header.get("type"), "other")] += 1
                self.serve_one(conn, header, body)
        except (ConnectionError, socket.timeout, OSError) as exc:
            self.journal.setdefault("errors", []).append(f"{type(exc).__name__}: {exc}")
        finally:
            conn.close()

    def serve_one(self, conn, header, body):
        mtype = header.get("type")
        self.journal.setdefault("requests", []).append(mtype)
        if mtype == "list-photos":
            offset = int(header.get("offset") or 0)
            limit = int(header.get("limit") or 0)
            items = [self.lib.public(p) for p in self.lib.photos[offset : offset + limit]] if limit > 0 else []
            conn.sendall(encode_frame({"type": "photo-list", "ok": True, "items": items}))
        elif mtype == "get-thumb":
            photo = self.lib.meta(str(header.get("photoId")))
            self.journal["thumb_bytes"] = self.journal.get("thumb_bytes", 0) + 1
            if photo is None:
                conn.sendall(encode_frame({"type": "photo-bytes", "ok": False, "error": "not found", "photoId": header.get("photoId")}))
            else:
                # 缩略图就直接发原文件：另一端只关心「能不能解出图」，不关心尺寸
                with open(photo["_path"], "rb") as fh:
                    conn.sendall(encode_frame({"type": "photo-bytes", "ok": True, "photoId": photo["id"]}, fh.read()))
        elif mtype == "get-photo":
            photo = self.lib.meta(str(header.get("photoId")))
            if photo is None:
                conn.sendall(encode_frame({"type": "photo-bytes", "ok": False, "error": "not found"}))
            else:
                with open(photo["_path"], "rb") as fh:
                    conn.sendall(encode_frame({"type": "photo-bytes", "ok": True, "photoId": photo["id"]}, fh.read()))
        elif mtype == "send-photo":
            meta = header.get("meta") or {}
            n = len(body or b"")
            self.journal.setdefault("received", []).append(
                {"name": meta.get("name"), "bytes": n, "dateTaken": meta.get("dateTaken")}
            )
            if n == 0:
                conn.sendall(encode_frame({"type": "send-error", "ok": False, "error": "missing body"}))
            elif n > MAX_BODY:
                conn.sendall(encode_frame({"type": "send-error", "ok": False, "error": "body too large"}))
            else:
                conn.sendall(encode_frame({"type": "send-ok", "ok": True}))
        elif mtype == "bye":
            raise ConnectionError("bye")
        else:
            conn.sendall(encode_frame({"type": mtype, "ok": False, "error": "unknown type"}))


def serve(port: int, directory: str, journal_path: str, name: str):
    lib = Library(directory)
    journal = {"startedAt": time.time(), "librarySize": len(lib.photos)}
    peer = Peer(lib, name, "py-" + uuid.uuid4().hex[:8], journal)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", port))
    srv.listen(32)
    print(f"[peer] serving {len(lib.photos)} photos on :{port}", flush=True)

    def dump():
        while True:
            time.sleep(1)
            with open(journal_path, "w", encoding="utf-8") as fh:
                json.dump(journal, fh, ensure_ascii=False, indent=1)

    threading.Thread(target=dump, daemon=True).start()
    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=peer.handle, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        pass
    finally:
        with open(journal_path, "w", encoding="utf-8") as fh:
            json.dump(journal, fh, ensure_ascii=False, indent=1)
        srv.close()


# ---------------------------------------------------------------- 客户端（probe）


class Client:
    def __init__(self, host: str, port: int, name: str = "py-probe"):
        self.conn = socket.create_connection((host, port), timeout=10)
        self.conn.settimeout(75)
        self.device_id = "py-" + uuid.uuid4().hex[:8]
        self.name = name

    def hello(self):
        self._send({"type": "hello", "ok": True, "deviceId": self.device_id, "name": self.name, "platform": "python"})
        header, _ = self._recv()
        return header

    def request(self, header, body=None):
        # header 和 body 是**同一帧**：[4B headerLen][JSON][4B bodyLen][body]，不能分两次发。
        self._send_raw(encode_frame(header, body))
        return self._recv()

    def _send(self, header):
        self._send_raw(encode_frame(header))

    def _send_raw(self, data: bytes):
        self.conn.sendall(data)

    def _recv(self):
        frame = read_frame(self.conn)
        if frame is None:
            raise ConnectionError("peer closed")
        return frame


def probe(host: str, port: int, send_path: str | None, offset: int = 0, limit: int = 60):
    out = {}
    c = Client(host, port)
    out["hello"] = c.hello()
    hdr, _ = c.request({"type": "list-photos", "ok": True, "offset": offset, "limit": limit})
    items = hdr.get("items") or []
    # 把 offset/limit 和 id 都打出来：验「两页 id 不重叠」只需要跑两次 probe 再对一眼，
    # 不必手写客户端。判末页的那条（本页条数 < limit）也能直接从这个 count 看出来。
    out["list"] = {
        "type": hdr.get("type"),
        "ok": hdr.get("ok"),
        "offset": offset,
        "limit": limit,
        "count": len(items),
        "ids": [it.get("id") for it in items],
        "first": items[:2],
    }
    if items:
        pid = items[0]["id"]
        hdr, body = c.request({"type": "get-thumb", "ok": True, "photoId": pid, "maxEdge": 256})
        out["thumb"] = {"type": hdr.get("type"), "ok": hdr.get("ok"), "bytes": len(body or b"")}
        hdr, body = c.request({"type": "get-photo", "ok": True, "photoId": pid})
        out["photo"] = {"type": hdr.get("type"), "ok": hdr.get("ok"), "bytes": len(body or b"")}
    if send_path:
        with open(send_path, "rb") as fh:
            data = fh.read()
        meta = {
            "id": "probe-1",
            "name": os.path.basename(send_path),
            "dateTaken": int(time.time() * 1000),
            "width": 64,
            "height": 32,
            "sizeBytes": len(data),
        }
        hdr, _ = c.request({"type": "send-photo", "ok": True, "meta": meta}, data)
        out["send"] = hdr
    hdr, _ = c.request({"type": "not-a-real-type", "ok": True})
    out["unknown"] = hdr
    print(json.dumps(out, ensure_ascii=False, indent=1), flush=True)
    c.conn.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("serve")
    s.add_argument("--port", type=int, default=47481)
    s.add_argument("--dir", required=True)
    s.add_argument("--journal", required=True)
    s.add_argument("--name", default="PyPeer")

    p = sub.add_parser("probe")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, required=True)
    p.add_argument("--send", default=None)
    # 分页：协议没有 total，末页只能靠「本页条数 < limit」判断（见 SYNC.md）。
    # 有了这两个参数，单机就能验「两页 id 不重叠」：跑两次 probe 各加 --offset 0 / 60 再比 ids。
    p.add_argument("--offset", type=int, default=0, help="list-photos 的起始下标（默认 0）")
    p.add_argument("--limit", type=int, default=60, help="list-photos 每页条数（默认 60）")

    a = ap.parse_args()
    if a.cmd == "serve":
        serve(a.port, a.dir, a.journal, a.name)
    else:
        probe(a.host, a.port, a.send, a.offset, a.limit)


if __name__ == "__main__":
    sys.exit(main())
