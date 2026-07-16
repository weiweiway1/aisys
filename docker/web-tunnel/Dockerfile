# syntax=docker/dockerfile:1
FROM cloudflare/cloudflared:latest

COPY --from=busybox:1.36.1 /bin/busybox /bin/busybox
COPY docker/web-tunnel/entrypoint.sh /entrypoint.sh

ENTRYPOINT ["/bin/busybox", "sh", "/entrypoint.sh"]
