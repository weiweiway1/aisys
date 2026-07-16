# syntax=docker/dockerfile:1
# 前端：node 构建 vue-pure-admin dist，nginx 托管并反向代理 /api、/ws 到网关

FROM node:22-alpine AS build
WORKDIR /app

ENV COREPACK_NPM_REGISTRY=https://registry.npmmirror.com
ENV npm_config_registry=https://registry.npmmirror.com

RUN corepack enable
COPY aisys-web/package.json aisys-web/pnpm-lock.yaml aisys-web/pnpm-workspace.yaml ./
RUN echo "registry=https://registry.npmmirror.com" > .npmrc
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --no-frozen-lockfile
COPY aisys-web/ ./
RUN pnpm build
COPY docker/web/platform-config.default.json /app/dist/platform-config.json

FROM nginx:1.27-alpine

RUN apk add --no-cache jq

COPY --from=build /app/dist /usr/share/nginx/html
COPY docker/web/docker-entrypoint.d/99-platform-config.sh /docker-entrypoint.d/99-platform-config.sh
RUN chmod +x /docker-entrypoint.d/99-platform-config.sh
COPY deploy/nginx/web.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
