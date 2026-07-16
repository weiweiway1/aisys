#!/bin/sh
set -eu

LOG_FILE=/tmp/web-tunnel.log
rm -f "$LOG_FILE"

cloudflared tunnel --url http://web:80 --no-autoupdate --loglevel info 2>&1 | /bin/busybox tee "$LOG_FILE" &
pid=$!

url=""
while [ -z "$url" ]; do
  if [ -s "$LOG_FILE" ]; then
    url=$(/bin/busybox awk '
      {
        for (i = 1; i <= NF; i++) {
          if ($i ~ /^https:\/\/.*trycloudflare\.com/) {
            print $i
            exit
          }
        }
      }
    ' "$LOG_FILE" || true)
  fi
  /bin/busybox sleep 1
done

echo "[web-tunnel] PUBLIC URL=$url"
wait "$pid"
