package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"runtime"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Agent 运行体（DDD 7.1）。
type Agent struct {
	cfg      *Config
	exec     *Executor
	mu       sync.Mutex
	conn     *websocket.Conn
	tasks    map[int64]bool // 运行中 taskId
	tasksMu  sync.Mutex
}

func NewAgent(cfg *Config) *Agent {
	return &Agent{cfg: cfg, exec: NewExecutor(cfg), tasks: map[int64]bool{}}
}

// register：enrollment token → agentId + agentToken（DDD 4.1.2 Agent 引导）。
func register(cfg *Config) (string, string, error) {
	body := map[string]any{
		"agentId":      cfg.AgentId,
		"nodeName":     cfg.NodeName,
		"ipAddress":    cfg.IPAddress,
		"agentVersion": cfg.AgentVersion,
		"osInfo":       runtime.GOOS + "/" + runtime.GOARCH,
		"enrollToken":  cfg.EnrollToken,
		"cpuInfo":      map[string]any{"model": readRealCPUModel(), "cores": runtime.NumCPU(), "threads": runtime.NumCPU()},
		"gpuInfo":      []any{},
		"totalMemory":  readRealMemory(),
		"totalDisk":    pickDisk(cfg.TotalDiskBytes),
		"labels":       map[string]string{"region": "sim", "gpu_type": "none", "simulated": "true"},
	}
	resp, code, err := httpJSON("POST", "http://"+cfg.PlatformAddr+"/api/v1/agent/register", body)
	if err != nil {
		return "", "", err
	}
	var apiResp struct {
		Code int    `json:"code"`
		Msg  string `json:"message"`
		Data struct {
			AgentId    string `json:"agentId"`
			AgentToken string `json:"agentToken"`
		} `json:"data"`
	}
	if uerr := json.Unmarshal(resp, &apiResp); uerr != nil {
		return "", "", fmt.Errorf("register resp parse (http %d): %s", code, string(resp))
	}
	// 成功判定：HTTP 200 + 业务码 0 + 非空 token；否则作为错误上抛（供 main 重试/重连时重新注册）
	if code != http.StatusOK || apiResp.Code != 0 || apiResp.Data.AgentToken == "" {
		return "", "", fmt.Errorf("register rejected (http %d, code %d): %s", code, apiResp.Code, apiResp.Msg)
	}
	return apiResp.Data.AgentId, apiResp.Data.AgentToken, nil
}

// heartbeatLoop：周期上报节点状态（DDD 5.7.2）。
func (a *Agent) heartbeatLoop() {
	ticker := time.NewTicker(time.Duration(a.cfg.HeartbeatSec) * time.Second)
	defer ticker.Stop()
	a.heartbeat() // 立即一次
	for range ticker.C {
		a.heartbeat()
	}
}

func (a *Agent) heartbeat() {
	a.tasksMu.Lock()
	running := len(a.tasks)
	a.tasksMu.Unlock()
	body := map[string]any{
		"agentId":     a.cfg.AgentId,
		"agentToken":  a.cfg.AgentToken,
		"nodeName":    a.cfg.NodeName,
		"status":      "online",
		"runningTasks": running,
	}
	_, code, err := httpJSON("POST", "http://"+a.cfg.PlatformAddr+"/api/v1/agent/heartbeat", body)
	if err != nil {
		log.Printf("[agent] heartbeat 错误: %v", err)
		return
	}
	if code != http.StatusOK {
		log.Printf("[agent] heartbeat 非 200: %d", code)
	}
}

// runWS：建立 WS 长连接，首帧 {type:auth,agentId,agentToken} 鉴权，循环读取指令。
func (a *Agent) runWS() error {
	// token 为空时尝试重新注册（Resource 重启后 Redis token 可能已变）
	if a.cfg.AgentToken == "" {
		log.Printf("[agent] token 为空，尝试重新注册...")
		aid, tok, err := register(a.cfg)
		if err != nil {
			return fmt.Errorf("重新注册失败: %w", err)
		}
		if tok != "" {
			a.cfg.AgentId = aid
			a.cfg.AgentToken = tok
			log.Printf("[agent] 重新注册成功 agentId=%s", aid)
		}
	}

	url := "ws://" + a.cfg.PlatformAddr + "/ws/v1/agent/connect"
	dialer := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	conn, _, err := dialer.Dial(url, nil)
	if err != nil {
		return err
	}
	a.mu.Lock()
	a.conn = conn
	a.mu.Unlock()
	defer func() {
		conn.Close()
		a.mu.Lock()
		a.conn = nil
		a.mu.Unlock()
	}()

	// 首帧鉴权（agentId 顶层，agentToken 放 data 内，对齐 resource AgentWebSocketHandler）
	if err := a.send(AuthFrame{Type: "auth", AgentId: a.cfg.AgentId, Data: AuthData{AgentToken: a.cfg.AgentToken}}); err != nil {
		return err
	}
	log.Printf("[agent] WS 已连接，等待指令")

	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			// 服务端鉴权失败会以 POLICY_VIOLATION(1008) 关闭（token 过期/失效）。
			// 清空内存 token，使下一轮 runWS 重新注册，避免用失效 token 无限重连。
			if websocket.IsCloseError(err, websocket.ClosePolicyViolation) {
				a.mu.Lock()
				a.cfg.AgentToken = ""
				a.mu.Unlock()
				log.Printf("[agent] WS 鉴权失败（token 可能已过期/失效），将在重连时重新注册")
			}
			return err
		}
		var cmd Command
		if err := json.Unmarshal(data, &cmd); err != nil {
			log.Printf("[agent] 无法解析帧: %v", err)
			continue
		}
		switch cmd.Type {
		case "auth_ok":
			log.Printf("[agent] 鉴权通过")
		case "pong":
			// 心跳应答
		case "run_task":
			log.Printf("[agent] 收到 run_task taskId=%d type=%s image=%s", cmd.TaskID, cmd.TaskType, cmd.Image)
			a.tasksMu.Lock()
			a.tasks[cmd.TaskID] = true
			a.tasksMu.Unlock()
			go a.exec.Run(&cmd, a)
		case "stop_task":
			log.Printf("[agent] 收到 stop_task taskId=%d", cmd.TaskID)
			a.exec.Stop(cmd.TaskID)
			a.tasksMu.Lock()
			delete(a.tasks, cmd.TaskID)
			a.tasksMu.Unlock()
		case "shell_command":
			go a.exec.runShell(cmd.Data, a)
		case "update_agent":
			log.Printf("[agent] 收到 update_agent（本版本模拟：忽略，打印）")
		default:
			log.Printf("[agent] 未知指令 %s", cmd.Type)
		}
	}
}

// send：向平台上报一帧。
func (a *Agent) send(frame any) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.conn == nil {
		return os.ErrClosed
	}
	data, _ := json.Marshal(frame)
	return a.conn.WriteMessage(websocket.TextMessage, data)
}
