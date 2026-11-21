#!/bin/bash
# Полное удаление всего, что было установлено для OpenJarvis.
# Всё находится внутри Linux-контейнера (Ubuntu rootfs), Android-система не затрагивается.
# Запуск: bash uninstall-openjarvis.sh

set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$HERE/OpenJarvis"

echo "=== Удаление OpenJarvis ==="

# 1. Остановить фоновые серверы Ollama
pkill -f 'ollama serve' 2>/dev/null || true
pkill -f 'llama-server -m' 2>/dev/null || true
pkill -f '[j]arvis serve' 2>/dev/null || true
pkill -f '[j]arvis gui' 2>/dev/null || true
pkill -f '[v]ite --host' 2>/dev/null || true
pkill -f '[j]arvis-proxy.py' 2>/dev/null || true
pkill -f 'strace -f' 2>/dev/null || true

# 2. Вернуть оригинальный llama-server, если был установлен wrapper
if [ -f /usr/local/lib/ollama/llama-server.real ]; then
    mv -f /usr/local/lib/ollama/llama-server.real /usr/local/lib/ollama/llama-server 2>/dev/null || true
fi

# 3. Удалить команды
rm -f /usr/local/bin/jarvis /usr/local/bin/openjarvis-eval

# 4. Удалить клон репозитория вместе с .venv
rm -rf "$REPO"

# 5. Удалить конфиг и данные OpenJarvis
rm -rf /root/.openjarvis

# 6. Удалить модели Ollama (примерно 2.7 ГБ)
rm -rf /root/.ollama

# 7. Удалить Ollama (бинарники, библиотеки, systemd-сервис, пользователя)
rm -f /usr/local/bin/ollama
rm -rf /usr/local/lib/ollama
rm -f /etc/systemd/system/ollama.service
systemctl daemon-reload 2>/dev/null || true
userdel ollama 2>/dev/null || true
rm -rf /usr/share/ollama 2>/dev/null || true

# 8. Удалить uv, установленный через pip
python3 -m pip uninstall -y uv 2>/dev/null || true

# 9. Удалить кэш браузера Playwright и вернуть npm исходную версию
rm -rf /root/.cache/ms-playwright
npm install -g npm@10.9.8 2>/dev/null || true

# 10. Удалить Android-приложение, мост и инструменты сборки
rm -rf "$HERE/JarvisAndroid"
rm -f "$HERE/jarvis-proxy.py"

# 11. Удалить системные пакеты, установленные при установке (опционально)
apt-get remove -y strace zstd android-sdk android-sdk-build-tools \
  android-sdk-platform-23 android-sdk-platform-tools dalvik-exchange dexdump \
  openjdk-17-jdk-headless 2>/dev/null || true
apt-get autoremove -y 2>/dev/null || true

echo "=== Готово ==="
echo "Проверка в контейнере: jarvis --version -> 'command not found'"
echo "На телефоне удалите приложение JARVIS через Настройки -> Приложения вручную."
