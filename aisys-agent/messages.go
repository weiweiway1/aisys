package main

// 消息帧定义（Agent ↔ Resource Service，DDD 7）。与 aisys-resource 的 AgentWebSocketHandler 协议保持一致。

// Agent → 平台（首帧鉴权，字段对齐 resource AgentWebSocketHandler：
// agentId 在顶层，agentToken 放 data 内 —— 与 handler 的读取方式一致）
type AuthFrame struct {
	Type    string   `json:"type"` // "auth"
	AgentId string   `json:"agentId"`
	Data    AuthData `json:"data"`
}
type AuthData struct {
	AgentToken string `json:"agentToken"`
}

// Agent → 平台（心跳）
type PingFrame struct {
	Type string `json:"type"` // "ping"
}

// 平台 → Agent 指令（字段名与 resource TaskDtos.RunTaskCommand 记录组件一一对应）
type Command struct {
	Type      string            `json:"type"`      // run_task / stop_task / pause_task / update_agent
	TaskID    int64             `json:"taskId,omitempty"`
	TaskType  string            `json:"taskType,omitempty"` // TRAINING / EVALUATION
	TenantID  int64             `json:"tenantId,omitempty"`
	TraceID   string            `json:"traceId,omitempty"`
	Image     string            `json:"image,omitempty"`
	Command   string            `json:"command,omitempty"`
	Args      []string          `json:"args,omitempty"`
	Env       map[string]string `json:"env,omitempty"`
	GPUDevs   []int             `json:"gpuDevices,omitempty"`
	CPU       int               `json:"cpu,omitempty"`
	Memory    int64             `json:"memoryBytes,omitempty"`
	OutputDir string            `json:"outputDir,omitempty"`
	Version   string            `json:"version,omitempty"`
	URL       string            `json:"downloadUrl,omitempty"`
	Checksum  string            `json:"checksum,omitempty"`
	// ——「每个模型是一个容器」——
	ImageName      string `json:"imageName,omitempty"`      // docker load 后 run 的镜像名
	ImageTarUrl    string `json:"imageTarUrl,omitempty"`    // 镜像 tar 预签名下载 URL
	DatasetUrl     string `json:"datasetUrl,omitempty"`     // 数据集对象预签名下载 URL
	DatasetFormat  string `json:"datasetFormat,omitempty"`  // yolo/coco/csv...
	TaskMode       string `json:"taskMode,omitempty"`       // train / eval
	OutputUploadUrl string `json:"outputUploadUrl,omitempty"` // 训练产物 best.pt 的预签名 PUT 上传 URL
	Data           map[string]any `json:"data,omitempty"`
}

// Agent → 平台 上报（字段对齐 resource WebSocketMessage：status/progress/result 放 data 内，
// type 用 status/log/metrics/result，与 REPORT_* 常量一致）
type Report struct {
	Type     string         `json:"type"` // status / log / metrics / result
	TaskID   int64          `json:"taskId,omitempty"`
	TaskType string         `json:"taskType,omitempty"`
	Level    string         `json:"level,omitempty"`  // log: INFO/WARN/ERROR
	Message  string         `json:"message,omitempty"` // log/status 文本
	Metrics  map[string]any `json:"metrics,omitempty"` // metrics（值可为 float/int/string，与平台 Map<String,Object> 对齐）
	Data     map[string]any `json:"data,omitempty"`    // status:{status,progress} / result:{status,progress,result,error}
}
