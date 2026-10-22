#!/usr/bin/env bash
# Arranca todo el stack del demo en local, accesible desde tu iPhone en
# el mismo wifi (mismo Mac IP) o vía ngrok si está corriendo en otra terminal.
#
# Uso:
#   ./scripts/dev.sh           — arranca postgres + backend + frontend
#   ./scripts/dev.sh stop      — detiene todo
#   ./scripts/dev.sh ngrok     — levanta tunnel ngrok del puerto 5173 (requiere ngrok instalado)
#
# Tips:
#   - Mac IP en mismo wifi:  ipconfig getifaddr en0
#   - URL en iPhone:         http://<mac-ip>:5173/k?u=04A3B2C1D5E801

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/.dev-logs"
mkdir -p "$LOG_DIR"

cmd="${1:-up}"

case "$cmd" in
  up)
    echo "▶ docker compose up postgres"
    (cd "$ROOT" && docker compose up -d)

    echo "▶ backend (Spring Boot) → http://localhost:8080"
    (cd "$ROOT/backend" && nohup ./gradlew --no-daemon bootRun > "$LOG_DIR/backend.log" 2>&1 &)

    echo "▶ frontend (Vite, expuesto 0.0.0.0) → http://localhost:5173"
    (cd "$ROOT/frontend" && nohup npm run dev > "$LOG_DIR/frontend.log" 2>&1 &)

    sleep 1
    MAC_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "<tu-ip>")
    echo
    echo "==============================="
    echo "  Demo corriendo:"
    echo "  Admin laptop:    http://localhost:5173/admin"
    echo "  Kiosko local:    http://localhost:5173/k"
    echo "  Kiosko iPhone:   http://$MAC_IP:5173/k"
    echo "  NFC sticker URL: http://$MAC_IP:5173/k?u=04A3B2C1D5E801"
    echo "==============================="
    echo "Logs en $LOG_DIR/"
    echo "Detener: ./scripts/dev.sh stop"
    ;;

  stop)
    echo "▶ stopping backend (gradle bootRun)"
    pkill -f "gradle.*bootRun" || true
    pkill -f "GradleDaemon" || true
    pkill -f "BackendApplication" || true
    echo "▶ stopping frontend (vite)"
    pkill -f "vite" || true
    echo "▶ docker compose down (Postgres se queda con datos en volumen)"
    (cd "$ROOT" && docker compose down)
    echo "Listo."
    ;;

  ngrok)
    if ! command -v ngrok &>/dev/null; then
      echo "ngrok no está instalado. Instala con: brew install --cask ngrok"
      exit 1
    fi
    echo "▶ ngrok http 5173 (HTTPS público hacia tu Vite local)"
    echo "  La URL pública aparece en la consola — úsala en stickers NFC para tests."
    ngrok http 5173
    ;;

  *)
    echo "Uso: $0 [up|stop|ngrok]"
    exit 1
    ;;
esac
