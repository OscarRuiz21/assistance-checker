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

# Variables de entorno de desarrollo local, según el contrato de entorno
# (docs/16-readiness-plan.md, docs/02-arquitectura.md). Perfil "local":
# Postgres de docker-compose.yml (puerto host 55432, db/user "assistance"),
# rol de aplicación "assistance_app" sin privilegios (migración V3 lo crea),
# JWT local con secreto de desarrollo y PII_KEY de desarrollo (NO usar en
# demo/prod; esos perfiles exigen sus propios valores vía secretos reales).
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:55432/assistance}"
export DB_MIGRATION_USER="${DB_MIGRATION_USER:-assistance}"
export DB_MIGRATION_PASSWORD="${DB_MIGRATION_PASSWORD:-assistance_dev}"
export DB_APP_USER="${DB_APP_USER:-assistance_app}"
export DB_APP_PASSWORD="${DB_APP_PASSWORD:-assistance_app_dev}"
export APP_PII_KEY="${APP_PII_KEY:-dev-only-pii-key-do-not-use-in-prod}"
export APP_JWT_SECRET="${APP_JWT_SECRET:-dev-only-jwt-secret-do-not-use-in-prod}"
export CORS_ORIGINS="${CORS_ORIGINS:-http://localhost:5173}"
export SERVER_PORT="${SERVER_PORT:-8080}"
export MANAGEMENT_PORT="${MANAGEMENT_PORT:-8081}"

# Espera activa a que el backend responda /actuator/health antes de seguir.
# $1: URL a sondear, $2: timeout total en segundos (default 90).
wait_for_backend_health() {
  local url="$1"
  local timeout="${2:-90}"
  local waited=0
  echo "▶ esperando a que el backend responda en $url ..."
  until curl -fsS "$url" >/dev/null 2>&1; do
    if [ "$waited" -ge "$timeout" ]; then
      echo "✗ timeout ($timeout s) esperando $url — revisa $LOG_DIR/backend.log"
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "✓ backend saludable después de ${waited}s"
}

cmd="${1:-up}"

case "$cmd" in
  up)
    echo "▶ docker compose up postgres"
    (cd "$ROOT" && docker compose up -d)

    echo "▶ backend (Spring Boot, perfil $SPRING_PROFILES_ACTIVE) → http://localhost:$SERVER_PORT"
    (cd "$ROOT/backend" && nohup ./gradlew --no-daemon bootRun > "$LOG_DIR/backend.log" 2>&1 &)

    wait_for_backend_health "http://localhost:${MANAGEMENT_PORT}/actuator/health" 120

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
