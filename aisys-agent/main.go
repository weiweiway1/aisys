package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// Agent 配置（环境变量注入，DDD 7.1）。
type Config struct {
	PlatformAddr    string // resource 服务地址 host:port
	EnrollToken     string // enrollment token（注册用，与服务端 aisys.resource.enroll-token 比对）
	AgentId         string // 注册后获得
	AgentToken      string // 注册后下发的长期 token
	NodeName        string
	IPAddress       string
	AgentVersion    string
	HeartbeatSec    int
	TaskImage       string // CPU 模拟训练/评测镜像（默认 python:3.11-slim）
	HostDockerSock  bool   // 是否挂载了宿主 docker.sock
	TotalDiskBytes  int64  // 节点磁盘总量（NODE_DISK_GB 覆盖，0=自动检测）
	WorkDir         string // 任务工作目录基址：必须与宿主机共享（bind mount），否则 docker run 的 -v 绑定路径在宿主侧不存在，容器看不到挂载内容
}

func loadConfig() *Config {
	c := &Config{
		PlatformAddr:   env("PLATFORM_ADDR", "resource:8006"),
		EnrollToken:    env("ENROLL_TOKEN", ""),
		AgentId:        env("AGENT_ID", ""),
		AgentToken:     env("AGENT_TOKEN", ""),
		NodeName:       env("NODE_NAME", "sim-node-01"),
		IPAddress:      env("NODE_IP", "127.0.0.1"),
		AgentVersion:   env("AGENT_VERSION", "1.0.0"),
		HeartbeatSec:   atoi(env("HEARTBEAT_SEC", "20"), 20),
		TaskImage:      env("TASK_IMAGE", "python:3.11-slim"),
		HostDockerSock: env("DOCKER_HOST_SOCKET", "true") == "true",
		TotalDiskBytes: int64(atoi(env("NODE_DISK_GB", "0"), 0)) * 1024 * 1024 * 1024,
		WorkDir:        env("AISYS_WORK_DIR", "/tmp/aisys-agent"),
	}
	if h, err := os.Hostname(); err == nil && c.NodeName == "" {
		c.NodeName = h
	}
	// IP 为空/127.0.0.1 时自动检测本机 IP
	if c.IPAddress == "" || c.IPAddress == "127.0.0.1" {
		if ip := detectLocalIP(); ip != "" {
			c.IPAddress = ip
		}
	}
	return c
}

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}
func atoi(s string, d int) int { v, err := strconv.Atoi(s); if err != nil { return d }; return v }

func main() {
	cfg := loadConfig()
	log.Printf("[agent] 启动 node=%s platform=%s version=%s", cfg.NodeName, cfg.PlatformAddr, cfg.AgentVersion)

	// 1. 注册（enrollment token → 长期 agentId/agentToken）。启动期带退避的有限次重试；
	//    若仍未成功则转由 WS 重连循环继续重试（runWS 在 token 为空时会重新注册）。
	if cfg.AgentToken == "" {
		backoff := 2 * time.Second
		for attempt := 1; attempt <= 6; attempt++ {
			aid, tok, err := register(cfg)
			if err == nil && tok != "" {
				cfg.AgentId = aid
				cfg.AgentToken = tok
				log.Printf("[agent] 注册成功 agentId=%s", aid)
				break
			}
			reason := "空 token"
			if err != nil {
				reason = err.Error()
			}
			if attempt == 6 {
				log.Printf("[agent] 启动注册重试已耗尽（最后错误: %s），转由 WS 重连循环继续重试", reason)
			} else {
				log.Printf("[agent] 注册失败 (%d/6): %s，%v 后重试", attempt, reason, backoff)
				time.Sleep(backoff)
				if backoff < 30*time.Second {
					backoff *= 2
				}
			}
		}
	}

	agent := NewAgent(cfg)

	// 2. 心跳循环（REST）
	go agent.heartbeatLoop()

	// 3. WebSocket 长连接（接收指令、上报状态）
	for {
		err := agent.runWS()
		if err != nil {
			log.Printf("[agent] WS 断开: %v，3s 后重连", err)
		}
		time.Sleep(3 * time.Second)
	}
}

// httpJSON 发送 JSON 请求返回 body。
func httpJSON(method, url string, body any) ([]byte, int, error) {
	var rd *strings.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, 0, err
		}
		rd = strings.NewReader(string(b))
	} else {
		rd = strings.NewReader("")
	}
	req, err := http.NewRequest(method, url, rd)
	if err != nil {
		return nil, 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	buf := make([]byte, 0, 4096)
	tmp := make([]byte, 4096)
	for {
		n, e := resp.Body.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
		}
		if e != nil {
			break
		}
	}
	return buf, resp.StatusCode, nil
}
