#!/bin/bash
# Поднять всё окружение OpenJarvis после перезапуска контейнера:
# 1) модель (llama.cpp), 2) API-сервер, 3) веб-GUI.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$HERE/OpenJarvis"
BLOB=/root/.ollama/models/blobs/sha256-c5396e06af294bd101b30dce59131a76d2b773e76950acc870eda801d3ab0515
MODEL=qwen2.5:0.5b
MODEL_PORT=8081

wait_for() {
    local url="$1" name="$2" tries="${3:-30}"
    for i in $(seq 1 "$tries"); do
        if curl -s --max-time 2 "$url" >/dev/null 2>&1; then
            echo "  OK: $name"
            return 0
        fi
        sleep 2
    done
    echo "  FAIL: $name"
    return 1
}

echo "=== OpenJarvis: запуск ==="

if ! curl -s --max-time 2 "http://127.0.0.1:$MODEL_PORT/v1/models" >/dev/null 2>&1; then
    echo "Запускаю быструю модель ($MODEL)..."
    nohup /usr/local/lib/ollama/llama-server -m "$BLOB" -a "$MODEL" \
        --host 127.0.0.1 --port "$MODEL_PORT" \
        -c 2048 -t 4 -tb 4 -b 512 -ub 256 -fa on --parallel 1 \
        --no-webui --reasoning off \
        > /tmp/opencode/llama-server.log 2>&1 &
    wait_for "http://127.0.0.1:$MODEL_PORT/v1/models" "модель" 30
else
    echo "  Модель уже запущена"
fi

if ! curl -s --max-time 2 http://127.0.0.1:8000/docs >/dev/null 2>&1; then
    echo "Запускаю API-сервер..."
    cd "$REPO" || exit 1
    nohup uv run --extra server jarvis serve --host 127.0.0.1 --port 8000 -e llamacpp -a simple \
        > /tmp/opencode/jarvis-api.log 2>&1 &
    wait_for http://127.0.0.1:8000/docs "API" 30
else
    echo "  API уже запущен"
fi

if ! curl -s --max-time 2 http://127.0.0.1:8001/health >/dev/null 2>&1; then
    echo "Запускаю мост для Android-приложения..."
    nohup python3 "$HERE/jarvis-proxy.py" > /tmp/opencode/jarvis-proxy.log 2>&1 &
    wait_for http://127.0.0.1:8001/health "Android-мост" 15
else
    echo "  Android-мост уже запущен"
fi

if ! curl -s --max-time 2 http://127.0.0.1:5173 >/dev/null 2>&1; then
    echo "Запускаю веб-GUI..."
    cd "$REPO" || exit 1
    nohup jarvis gui --no-server --no-browser --frontend-port 5173 \
        > /tmp/opencode/jarvis-gui.log 2>&1 &
    wait_for http://127.0.0.1:5173 "GUI" 30
else
    echo "  GUI уже запущен"
fi

HOST_IP=$(python3 - <<'PY'
import socket, fcntl, struct
for iface in ("wlan0", "eth0"):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        print(socket.inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915,
              struct.pack('256s', iface.encode()[:15]))[20:24]))
        break
    except Exception:
        pass
PY
)

echo
echo "Готово:"
echo "  CLI:   jarvis ask 'Привет'  /  jarvis chat"
echo "  API:   http://127.0.0.1:8000/docs"
echo "  GUI:   http://127.0.0.1:5173"
echo "  Приложение: http://$HOST_IP:8001/v1/chat/completions"
