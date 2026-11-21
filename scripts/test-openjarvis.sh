#!/bin/bash
# Быстрая проверка всех включённых возможностей OpenJarvis.
# Безопасные тесты: ничего не удаляет и не меняет.
set -u
REPO="/root/chat/2026-11-21/session-bc6c7164-dad7-4b4f-ab35-7904e2ce168f/OpenJarvis"

pass() { echo "  [PASS] $1"; }
fail() { echo "  [FAIL] $1"; }

echo "=== 1. Версия ==="
jarvis --version && pass "CLI" || fail "CLI"

echo "=== 2. Диагностика ==="
jarvis doctor 2>&1 | strings | grep -E 'passed|failures' | head -1

echo "=== 3. Простой чат (быстрый агент) ==="
OUT=$(timeout 120 jarvis ask "Скажи одно слово: готов" 2>&1 | tail -1)
echo "  -> $OUT"
[ -n "$OUT" ] && pass "simple agent" || fail "simple agent"

echo "=== 4. Инструмент calculator (оркестратор) ==="
OUT=$(timeout 240 jarvis ask -a orchestrator "Сколько будет 12*8? Используй calculator." 2>&1 | tail -1)
echo "  -> $OUT"
echo "$OUT" | grep -q '96' && pass "calculator" || fail "calculator"

echo "=== 5. Инструмент shell_exec ==="
OUT=$(timeout 300 jarvis ask -a orchestrator "Выполни через shell_exec: echo TOOL_OK2. Сообщи результат." 2>&1)
echo "  -> $(echo "$OUT" | tail -3 | tr '\n' ' ')"
echo "$OUT" | grep -q 'TOOL_OK2' && pass "shell_exec" || fail "shell_exec"

echo "=== 6. API-сервер ==="
CODE=$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8000/docs)
[ "$CODE" = "200" ] && pass "API docs HTTP $CODE" || fail "API docs HTTP $CODE"
OUT=$(timeout 280 curl -s --max-time 270 http://127.0.0.1:8000/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen3.5:2b","messages":[{"role":"user","content":"Say OK"}],"max_tokens":32,"stream":false}' | head -c 200)
echo "  -> $(echo "$OUT" | head -c 180)"
echo "$OUT" | grep -q '"content":"OK"' && pass "API chat" || fail "API chat"

echo "=== 7. Web GUI ==="
CODE=$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:5173)
[ "$CODE" = "200" ] && pass "GUI HTTP $CODE" || fail "GUI HTTP $CODE"

echo "=== 8. Браузер Playwright ==="
OUT=$(cd "$REPO" && timeout 150 uv run python -c \
  "from playwright.sync_api import sync_playwright; p=sync_playwright().start(); b=p.chromium.launch(); pg=b.new_page(); pg.goto('https://example.com'); print(pg.title()); b.close(); p.stop()" 2>&1 | tail -1)
echo "  -> $OUT"
echo "$OUT" | grep -q 'Example Domain' && pass "Playwright" || fail "Playwright"

echo "=== Готово ==="
