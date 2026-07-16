# syntax=docker/dockerfile:1
# Go Agent：编译单二进制，运行镜像含 docker-cli（挂载宿主 /var/run/docker.sock 拉起 CPU 模拟容器）
FROM golang:1.24 AS build
WORKDIR /src
COPY aisys-agent/ /src/
ENV GOPROXY=https://goproxy.cn,direct GOCACHE=/root/.cache GOMODCACHE=/root/gomod
RUN --mount=type=cache,target=/root/.cache --mount=type=cache,target=/root/gomod \
    CGO_ENABLED=0 go build -o /agent .

FROM alpine:3.21
RUN apk add --no-cache docker-cli docker-cli-compose ca-certificates tzdata
COPY --from=build /agent /usr/local/bin/agent
ENV TZ=Asia/Shanghai
ENTRYPOINT ["/usr/local/bin/agent"]
