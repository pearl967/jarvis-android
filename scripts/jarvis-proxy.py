#!/usr/bin/env python3
"""Bridge: Android JARVIS -> fast local llama-server + optional Yandex TTS.

- /v1/...            -> 127.0.0.1:8081 (llama.cpp, streaming SSE passthrough)
- /tts?text=...      -> Yandex SpeechKit v3 (API key from request or env)
- /health            -> local health check
"""
import base64
import http.server
import json
import os
import socketserver
import urllib.error
import urllib.parse
import urllib.request

UPSTREAM = "http://127.0.0.1:8081"
TTS_URL = "https://tts.api.cloud.yandex.net/tts/v3/utteranceSynthesis"
PORT = 8001

WEATHER_CODES = {
    0: "ясно", 1: "преимущественно ясно", 2: "переменная облачность",
    3: "облачно", 45: "туман", 48: "гололёд", 51: "морось",
    53: "морось", 55: "сильная морось", 61: "небольшой дождь",
    63: "дождь", 65: "сильный дождь", 71: "небольшой снег",
    73: "снег", 75: "сильный снег", 80: "небольшой ливень",
    81: "ливень", 82: "сильный ливень", 95: "гроза",
    96: "гроза с градом", 99: "сильная гроза с градом",
}


def _send(self, code, data, ctype="application/json"):
    self.send_response(code)
    self.send_header("Content-Type", ctype)
    self.send_header("Content-Length", str(len(data)))
    self.end_headers()
    self.wfile.write(data)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _tts(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        text = query.get("text", [""])[0]
        voice = query.get("voice", ["marina"])[0]
        role = query.get("role", ["neutral"])[0]
        key = query.get("key", [os.environ.get("YANDEX_TTS_KEY", "")])[0]
        folder = query.get("folder", [os.environ.get("YANDEX_FOLDER_ID", "")])[0]

        if not text:
            _send(self, 400, b'{"error":"empty text"}')
            return
        if not key:
            _send(self, 400, '{"error":"Yandex API key not set"}'.encode())
            return

        payload = {
            "text": text,
            "outputAudioSpec": {"containerAudio": {"containerAudioType": "WAV"}},
            "hints": [{"voice": voice}, {"role": role}, {"speed": "1.0"}],
        }
        headers = {
            "Authorization": "Api-Key " + key,
            "Content-Type": "application/json",
        }
        if folder:
            headers["x-folder-id"] = folder
        req = urllib.request.Request(
            TTS_URL, data=json.dumps(payload).encode("utf-8"),
            headers=headers, method="POST",
        )
        try:
            audio = bytearray()
            with urllib.request.urlopen(req, timeout=40) as resp:
                for line in resp:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                        obj = obj.get("result", obj)
                        chunk = obj.get("audioChunk", {}).get("data")
                        if chunk:
                            audio.extend(base64.b64decode(chunk))
                    except Exception:
                        continue
            if not audio:
                _send(self, 502, b'{"error":"empty audio from Yandex"}')
                return
            _send(self, 200, bytes(audio), "audio/wav")
        except urllib.error.HTTPError as exc:
            _send(self, exc.code, exc.read())
        except Exception as exc:
            _send(self, 502, ('{"error":"%s"}' % str(exc)).encode())

    def _weather(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        lat = query.get("lat", [""])[0]
        lon = query.get("lon", [""])[0]
        try:
            if not lat or not lon:
                req = urllib.request.Request(
                    "https://ipapi.co/json/",
                    headers={"User-Agent": "JARVIS/2.1"},
                )
                with urllib.request.urlopen(req, timeout=8) as resp:
                    place = json.load(resp)
                lat = str(place["latitude"])
                lon = str(place["longitude"])
            url = (
                "https://api.open-meteo.com/v1/forecast"
                "?latitude=%s&longitude=%s"
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                "&timezone=auto" % (urllib.parse.quote(lat), urllib.parse.quote(lon))
            )
            req = urllib.request.Request(url, headers={"User-Agent": "JARVIS/2.1"})
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.load(resp)
            cur = data["current"]
            code = int(cur.get("weather_code", 0))
            desc = WEATHER_CODES.get(code, "переменная облачность")
            text = (
                "Сейчас %.1f градуса, %s, ветер %.1f метров в секунду, "
                "влажность %d процентов." % (
                    cur.get("temperature_2m", 0),
                    desc,
                    cur.get("wind_speed_10m", 0),
                    int(cur.get("relative_humidity_2m", 0)),
                )
            )
            _send(self, 200, json.dumps({"text": text, "lat": lat, "lon": lon}).encode())
        except Exception as exc:
            _send(self, 502, ('{"error":"%s"}' % str(exc)).encode())

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
                    _send(self, resp.status, data, ctype)
        except urllib.error.HTTPError as exc:
            _send(self, exc.code, exc.read())
        except Exception as exc:
            _send(self, 502, ('{"error":"%s"}' % str(exc)).encode())

    def do_GET(self):
        if self.path.startswith("/health"):
            _send(self, 200, b'{"status":"ok"}')
        elif self.path.startswith("/tts?"):
            self._tts()
        elif self.path.startswith("/weather"):
            self._weather()
        else:
            self._forward()

    do_POST = _forward

    def log_message(self, fmt, *args):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    print("Jarvis bridge on 0.0.0.0:%d -> %s (+Yandex TTS)" % (PORT, UPSTREAM),
          flush=True)
    Server(("0.0.0.0", PORT), Handler).serve_forever()
