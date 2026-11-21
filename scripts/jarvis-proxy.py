#!/usr/bin/env python3
"""Tiny bridge: Android app -> fast local llama-server (OpenJarvis model backend).

The OpenJarvis API stays on container loopback (so the bundled GUI keeps
working), while this proxy listens on the device Wi-Fi address and forwards
requests to the low-latency model server. Streaming SSE replies are passed
through line by line so the UI can render tokens as they arrive.
"""
import http.server
import socketserver
import urllib.request
import urllib.error

UPSTREAM = "http://127.0.0.1:8081"
PORT = 8001


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _forward(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        body = self.rfile.read(length) if length else None
        streaming = bool(body and b'"stream"' in body and b'true' in body)
        req = urllib.request.Request(
            UPSTREAM + self.path,
            data=body,
            method=self.command,
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                ctype = resp.headers.get("Content-Type", "application/json")
                if streaming:
                    self.send_response(resp.status)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Cache-Control", "no-cache")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    self.close_connection = True
                    for line in resp:
                        self.wfile.write(line)
                        self.wfile.flush()
                else:
                    data = resp.read()
                    self.send_response(resp.status)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
        except urllib.error.HTTPError as exc:
            data = exc.read()
            self.send_response(exc.code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as exc:
            data = ('{"error":"%s"}' % str(exc)).encode()
            self.send_response(502)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    do_GET = _forward
    do_POST = _forward

    def log_message(self, fmt, *args):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    print("Jarvis bridge listening on 0.0.0.0:%d -> %s" % (PORT, UPSTREAM), flush=True)
    Server(("0.0.0.0", PORT), Handler).serve_forever()
