#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/aisys"
CF_IMAGE="${CF_IMAGE:-cloudflare/cloudflared:latest}"
CF_BIN="${CACHE_DIR}/cloudflared"
LOG_FILE="${CACHE_DIR}/web-tunnel.log"

mkdir -p "$CACHE_DIR"

# Start only web itself; do not pull its depends_on chain back into the startup.
docker compose -f "$COMPOSE_FILE" up -d --no-deps web

if [ ! -x "$CF_BIN" ]; then
  echo "[web-tunnel] extracting cloudflared binary..."
  tmp_container=$(docker create "$CF_IMAGE")
  trap 'docker rm -f "$tmp_container" >/dev/null 2>&1 || true' EXIT INT TERM
  docker cp "$tmp_container:/usr/local/bin/cloudflared" "$CF_BIN"
  docker rm -f "$tmp_container" >/dev/null
  chmod +x "$CF_BIN"
  trap - EXIT INT TERM
fi

GATEWAY_IP=$(ip route | awk '/default/ {print $3; exit}')
ORIGIN_URL="${ORIGIN_URL:-http://${GATEWAY_IP}:8081}"

rm -f "$LOG_FILE"
echo "[web-tunnel] origin=$ORIGIN_URL"
"$CF_BIN" tunnel --url "$ORIGIN_URL" --no-autoupdate --loglevel info >"$LOG_FILE" 2>&1 &
pid=$!

url=""
while kill -0 "$pid" 2>/dev/null; do
  if [ -s "$LOG_FILE" ]; then
    url=$(awk '
      match($0, /https:\/\/[A-Za-z0-9.-]*trycloudflare\.com/) {
        print substr($0, RSTART, RLENGTH)
        exit
      }
    ' "$LOG_FILE" || true)
    if [ -n "$url" ]; then
      echo "[web-tunnel] PUBLIC URL=$url"
      wait "$pid"
      exit 0
    fi
  fi
  sleep 1
done

wait "$pid"
