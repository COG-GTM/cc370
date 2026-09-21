#!/usr/bin/env python3
"""Negative control for T-15 / F2: a reverse proxy in front of ledger-app that alters ONLY the
immediate HTTP response of the N-th apply (POST .../requests or .../requests:raw) while the
request itself is forwarded unchanged, so the persisted RESOUT stays correct.

The mutation is self-consistent: one byte of resultHex (OCHG, offset 69) is changed, and for a
typed response result.recordHex and result.charge are rewritten to agree with the altered bytes,
so a driver that only checked "typed fields match resultHex" or only read back RESOUT after
publication would still pass. parity_java.py must fail the stage on the immediate response.

  mutant_proxy.py --listen PORT --upstream http://127.0.0.1:18080 --nth 8 --log FILE
"""
import argparse
import json
import re
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APPLY = re.compile(r"^/v1/namespaces/[^/]+/generations/[^/]+/requests(?::raw)?$")
OCHG = 69  # PL7 charge at offset 69..75 in the 96-byte result


def mutate(body: bytes, log) -> bytes:
    resp = json.loads(body)
    raw = bytearray(bytes.fromhex(resp["resultHex"]))
    before = bytes(raw)
    raw[OCHG + 5] ^= 0x10  # +/-1000 cents in the tens-of-dollars digit, still valid packed digits
    resp["resultHex"] = raw.hex()
    typed = resp.get("result")
    if isinstance(typed, dict):
        typed["recordHex"] = raw.hex()
        digits = "".join(f"{b:02x}" for b in raw[OCHG:OCHG + 7])
        magnitude = int(digits[:-1])
        typed["charge"] = -magnitude if digits[-1] in "bd" else magnitude
    log.write(f"MUTATED ordinal={resp.get('ordinal')} typed={resp.get('typed')} "
              f"result before={before.hex()} after={raw.hex()} "
              f"typed.charge={typed.get('charge') if isinstance(typed, dict) else None}\n")
    log.flush()
    return json.dumps(resp).encode()


class Handler(BaseHTTPRequestHandler):
    upstream = ""
    nth = 0
    seen = 0
    lock = threading.Lock()
    log = sys.stderr

    def _forward(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        req = urllib.request.Request(self.upstream + self.path, data=body, method=self.command)
        for k, v in self.headers.items():
            if k.lower() not in ("host", "content-length", "connection"):
                req.add_header(k, v)
        try:
            with urllib.request.urlopen(req) as r:
                status, headers, out = r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            status, headers, out = e.code, e.headers, e.read()
        if self.command == "POST" and APPLY.match(self.path) and status == 200:
            with Handler.lock:
                Handler.seen += 1
                hit = Handler.seen == self.nth
            if hit:
                out = mutate(out, self.log)
        self.send_response(status)
        for k, v in headers.items():
            if k.lower() not in ("content-length", "transfer-encoding", "connection"):
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    do_GET = do_POST = do_PUT = do_DELETE = _forward

    def log_message(self, *a):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, required=True)
    ap.add_argument("--upstream", required=True)
    ap.add_argument("--nth", type=int, required=True)
    ap.add_argument("--log", required=True)
    a = ap.parse_args()
    Handler.upstream = a.upstream.rstrip("/")
    Handler.nth = a.nth
    Handler.log = open(a.log, "a")
    ThreadingHTTPServer(("127.0.0.1", a.listen), Handler).serve_forever()


if __name__ == "__main__":
    main()
