package main

import (
	"archive/tar"
	"archive/zip"
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Executor 执行任务（DDD 7.2）：
//   - 「每个模型是一个容器」：从存储池下载镜像 tar（docker load）+ 数据集，挂载后 docker run 真实模型容器，
//     解析其统一脚本输出的 JSONL（status/log/metric/result）实时回传平台。
//   - 兼容旧路径：无镜像 tar 时回退到内置 CPU 模拟脚本（step 行 + RESULT）。
var ansiEscapeRE = regexp.MustCompile(`\x1b\[[;?0-9]*[ -/]*[@-~]`)

type Executor struct {
	cfg     *Config
	mu      sync.Mutex
	procs   map[int64]*exec.Cmd // taskId → 运行中容器进程
	stopped map[int64]bool      // taskId → 是否由 Stop 主动终止（区分 cancelled 与 failed）
}

func NewExecutor(cfg *Config) *Executor {
	return &Executor{cfg: cfg, procs: map[int64]*exec.Cmd{}, stopped: map[int64]bool{}}
}

// 模拟训练/评测脚本（旧路径回退）：打印 step 行 + RESULT 行。
const simScript = `
import time, random, math, json
total = 40
final_loss = 0.0
for i in range(1, total + 1):
    loss = 2.0 * math.exp(-i / 12.0) + 0.05 * random.random()
    final_loss = loss
    acc = 0.62 + 0.3 * (1 - math.exp(-i / 12.0)) + 0.02 * random.random()
    lr = 2e-5
    print(f"step {i}/{total} loss {loss:.4f} lr {lr:.2e} accuracy {acc:.4f}", flush=True)
    time.sleep(0.4)
res = {"final_loss": round(final_loss, 4), "accuracy": round(0.62 + 0.3 * random.random(), 4), "steps": total}
print("RESULT " + json.dumps(res), flush=True)
`

func (e *Executor) Run(cmd *Command, agent *Agent) {
	// 任务退出（任何路径）后从运行中集合移除，保证心跳 runningTasks 准确。
	defer func() {
		agent.tasksMu.Lock()
		delete(agent.tasks, cmd.TaskID)
		agent.tasksMu.Unlock()
	}()
	agent.send(Report{Type: "status", TaskID: cmd.TaskID, TaskType: cmd.TaskType,
		Data: map[string]any{"status": "running", "progress": 0}})

	if cmd.ImageTarUrl != "" {
		e.runModelContainer(cmd, agent)
	} else {
		e.runSim(cmd, agent)
	}
}

// runModelContainer：真实模型容器流程（下载镜像 tar → docker load → 下载/挂载数据集 → docker run → 解析 JSONL）。
func (e *Executor) runModelContainer(cmd *Command, agent *Agent) {
	log.Printf("[agent] runModelContainer taskId=%d imageName=%q imageTarUrl=%d datasetUrl=%d taskMode=%q",
		cmd.TaskID, cmd.ImageName, len(cmd.ImageTarUrl), len(cmd.DatasetUrl), cmd.TaskMode)
	imageName := cmd.ImageName
	// 镜像缓存：若镜像已存在则跳过下载/加载（重复任务不重复拉 2GB+ tar）
	if imageName != "" {
		if err := exec.Command("docker", "image", "inspect", imageName).Run(); err == nil {
			log.Printf("[agent] 镜像已存在，跳过下载/加载 %s", imageName)
		} else if cmd.ImageTarUrl != "" {
			loaded, err := downloadAndLoadImage(cmd.ImageTarUrl, cmd.TaskID)
			if err != nil {
				e.reportTerminal(agent, cmd, "failed", "", "下载/加载镜像失败: "+err.Error())
				return
			}
			// ★ 始终使用 docker load 解析出的实际镜像标签（tar 内部标签可能与 config 中的 imageName 不一致，
			//   例如用户打包时用 docker build -t yolo:v1 但数据库填的是 yolo:v2）
			if loaded != "" {
				if loaded != imageName {
					log.Printf("[agent] 镜像标签修正: config=%q → tar实际=%q", imageName, loaded)
				}
				imageName = loaded
			}
		}
	} else if cmd.ImageTarUrl != "" {
		loaded, err := downloadAndLoadImage(cmd.ImageTarUrl, cmd.TaskID)
		if err != nil {
			e.reportTerminal(agent, cmd, "failed", "", "下载/加载镜像失败: "+err.Error())
			return
		}
		imageName = loaded
	}
	log.Printf("[agent] 镜像就绪 taskId=%d image=%s", cmd.TaskID, imageName)
	if imageName == "" {
		imageName = cmd.Image
	}
	if imageName == "" {
		e.reportTerminal(agent, cmd, "failed", "", "未提供镜像名/镜像 tar")
		return
	}

	// 任务工作目录（dataset 挂载 + output 落盘）：基址 WorkDir 必须与宿主共享（compose bind mount），
	// 否则经宿主 docker.sock 拉起的容器其 -v 绑定路径在宿主侧不存在 → 容器挂载点为空。
	workDir := filepath.Join(e.cfg.WorkDir, fmt.Sprintf("aisys-task-%d", cmd.TaskID))
	datasetDir := filepath.Join(workDir, "dataset")
	outputDir := filepath.Join(workDir, "output")
	_ = os.MkdirAll(datasetDir, 0o755)
	_ = os.MkdirAll(outputDir, 0o755)
	defer func() { _ = os.RemoveAll(workDir) }()

	// 下载数据集对象到挂载目录；归档（zip/tar/tar.gz）自动解压，使 data.yaml 等落在 /data/dataset/...
	// （run.py 会在挂载点含子目录递归查找 data.yaml）。非归档则作为单文件落到 datasetDir/dataset。
	if cmd.DatasetUrl != "" {
		if err := downloadAndPrepareDataset(cmd.DatasetUrl, datasetDir); err != nil {
			log.Printf("[agent] 下载数据集失败 taskId=%d: %v（继续，容器侧可能用合成集）", cmd.TaskID, err)
		}
	}

	// 任务模式（提前声明：resolveDatasetMountDir 需要用）
	taskMode := cmd.TaskMode
	if taskMode == "" {
		taskMode = "train"
	}

	// 检测数据集格式并选择正确的挂载目录（平台侧处理，模型零感知）：
	//   - 三分割格式(train/val/test)：根据 taskMode 选择子目录，rw 挂载（Ultralytics 等框架需要写缓存）
	//   - 扁平 ImageFolder 格式：整体 ro 挂载（安全默认值）
	effectiveDatasetDir, datasetMode := resolveDatasetMountDir(datasetDir, taskMode)

	datasetRoRw := ":ro" // 默认只读（扁平格式 / 安全）
	if datasetMode == "split" {
		datasetRoRw = ":rw" // 三分割需要写缓存
	}

	name := fmt.Sprintf("task-%d", cmd.TaskID)
	// 不用 -t：分配 TTY 会让 tqdm/ultralytics 误判为交互终端，用 \r 原地刷新进度条，
	// bufio.Scanner 只按 \n 分割会把所有 \r-刷新累积成单行突破 buffer 上限（ErrTooLong），
	// scanner 静默退出后后续 stdout（log/metric/result 帧）全部丢失。
	// TQDM_DISABLE=1 双保险：即使容器内 isatty 误判，tqdm 也不输出进度条。
	args := []string{"run", "--rm", "--name", name,
		"-v", effectiveDatasetDir + ":/data/dataset" + datasetRoRw,
		"-v", outputDir + ":/output",
		"-e", "AISYS_DATASET_DIR=/data/dataset",
		"-e", "AISYS_OUTPUT_DIR=/output",
		"-e", "PYTHONUNBUFFERED=1",
		"-e", "TQDM_DISABLE=1",
	}
	if datasetMode == "split" {
		args = append(args, "-e", "AISYS_DATASET_FORMAT=split")
	}
	args = append(args, "-e", "AISYS_TASK="+taskMode)
	if cmd.DatasetFormat != "" {
		args = append(args, "-e", "AISYS_DATASET_FORMAT="+cmd.DatasetFormat)
	}
	for k, v := range cmd.Env {
		args = append(args, "-e", k+"="+v)
	}
	// 资源限制：CPU / 内存始终生效；GPU 仅在分配了设备时透传（CPU 节点不传 --gpus，避免 docker 报错）。
	if cmd.CPU > 0 {
		args = append(args, "--cpus", strconv.Itoa(cmd.CPU))
	}
	if cmd.Memory > 0 {
		args = append(args, "--memory", strconv.FormatInt(cmd.Memory, 10))
	}
	if len(cmd.GPUDevs) > 0 {
		devs := make([]string, len(cmd.GPUDevs))
		for i, d := range cmd.GPUDevs {
			devs[i] = strconv.Itoa(d)
		}
		args = append(args, "--gpus", fmt.Sprintf(`"device=%s"`, strings.Join(devs, ",")))
	}
	args = append(args, imageName)
	if len(cmd.Args) > 0 {
		args = append(args, cmd.Args...)
	}
	e.runDockerAndStream(cmd, agent, imageName, args, true)

	// 训练产物回传：容器退出后、defer 删除 workDir 之前，把最佳权重 PUT 到平台预签名 URL，
	// 否则权重文件随 workDir 一起被清理，训练成果无法落盘（仅 TRAINING，平台仅对此下发 outputUploadUrl）。
	// 不同算法库的权重命名/路径各异（best.pt / best.pth / runs/.../weights/best.pt / model.safetensors），
	// 用 findBestWeight 扫描整个 /output 挑选权重文件，避免硬编码 best.pt 漏抓。
	// 落盘 S3 key 固定为 training/{taskId}/output/best.pt（与 resource 预签名 URL 对应），前端按此路径下载。
	if cmd.OutputUploadUrl != "" {
		best := findBestWeight(outputDir)
		if best == "" {
			log.Printf("[agent] 未找到权重文件 taskId=%d（扫描 %s 无 .pt/.pth/.onnx/.safetensors/.bin）", cmd.TaskID, outputDir)
		} else if err := uploadFile(cmd.OutputUploadUrl, best); err != nil {
			log.Printf("[agent] 上传训练产物失败 taskId=%d file=%s: %v", cmd.TaskID, filepath.Base(best), err)
		} else {
			log.Printf("[agent] 已上传训练产物 taskId=%d file=%s", cmd.TaskID, filepath.Base(best))
			agent.send(Report{Type: "status", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Data: map[string]any{
				"status": "completed", "progress": 100, "outputUploaded": true,
				"outputPath": fmt.Sprintf("training/%d/output/best.pt", cmd.TaskID),
			}})
		}
	}
}

// findBestWeight 在 outputDir（含子目录）中查找最佳权重文件。
// 不同算法库命名/路径各异（best.pt / best.pth / runs/.../weights/best.pt / model.safetensors），
// 硬编码 best.pt 会漏掉，故扫描整个输出目录按评分挑选。返回空字符串表示未找到。
func findBestWeight(outputDir string) string {
	if info, err := os.Stat(outputDir); err != nil || !info.IsDir() {
		return ""
	}
	type cand struct {
		path string
		name string
		pref int
	}
	var cands []cand
	_ = filepath.Walk(outputDir, func(p string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() {
			return nil
		}
		ext := strings.ToLower(filepath.Ext(info.Name()))
		pref := 0
		switch ext {
		case ".pt", ".pth":
			pref = 3
		case ".safetensors":
			pref = 2
		case ".onnx", ".bin", ".h5", ".pkl", ".ckpt":
			pref = 1
		default:
			return nil
		}
		cands = append(cands, cand{path: p, name: info.Name(), pref: pref})
		return nil
	})
	bestIdx := -1
	var bestScore int
	for i, c := range cands {
		score := c.pref
		if strings.Contains(strings.ToLower(c.name), "best") {
			score += 100 // 优先选名字含 best 的权重
		}
		score -= len(c.path) / 10 // 路径越短（越靠近根目录）越优先
		if bestIdx == -1 || score > bestScore {
			bestIdx = i
			bestScore = score
		}
	}
	if bestIdx == -1 {
		return ""
	}
	return cands[bestIdx].path
}

// runSim：旧路径回退（内置 CPU 模拟脚本，step 行协议）。
func (e *Executor) runSim(cmd *Command, agent *Agent) {
	image := cmd.Image
	if image == "" {
		image = e.cfg.TaskImage
	}
	name := fmt.Sprintf("task-%d", cmd.TaskID)
	args := []string{"run", "--rm", "--name", name, image, "python", "-c", simScript}
	for k, v := range cmd.Env {
		_ = k
		_ = v
	}
	e.runDockerAndStream(cmd, agent, image, args, false)
}

// runDockerAndStream：启动容器，流式读 stdout，逐行解析（JSONL 优先，回退 step 行），上报。
// jsonlMode=true 时按统一脚本 JSONL 协议解析；false 时按旧 step 行解析。
func (e *Executor) runDockerAndStream(cmd *Command, agent *Agent, image string, args []string, jsonlMode bool) {
	dockerCmd := exec.Command("docker", args...)
	for k, v := range cmd.Env {
		dockerCmd.Env = append(dockerCmd.Env, k+"="+v)
	}
	dockerCmd.Env = append(dockerCmd.Env, fmt.Sprintf("TASK_ID=%d", cmd.TaskID))

	stdout, err := dockerCmd.StdoutPipe()
	if err != nil {
		e.reportTerminal(agent, cmd, "failed", "", err.Error())
		return
	}
	// 捕获容器 stderr：容器崩溃（import 失败/OOM/CUDA 不匹配/缺 data.yaml）时，stdout 往往无 result 帧，
	// dockerCmd.Wait() 仅返回 "exit status N"。把 stderr 尾部并入失败上报，便于平台侧诊断。
	var stderrBuf bytes.Buffer
	dockerCmd.Stderr = &stderrBuf
	if err := dockerCmd.Start(); err != nil {
		e.reportTerminal(agent, cmd, "failed", "", "启动容器失败（镜像 "+image+" 未加载？）: "+err.Error())
		return
	}
	e.mu.Lock()
	e.procs[cmd.TaskID] = dockerCmd
	e.mu.Unlock()
	defer func() {
		e.mu.Lock()
		delete(e.procs, cmd.TaskID)
		delete(e.stopped, cmd.TaskID)
		e.mu.Unlock()
	}()

	scanner := bufio.NewScanner(stdout)
	// 上限 64MB：防御性兜底（去掉 -t 后单行不应再累积超长，但容器可能输出大 traceback/JSON）。
	// 之前 4MB 被 tqdm 累积行突破，scanner 报 ErrTooLong 退出，后续 stdout 全丢。
	scanner.Buffer(make([]byte, 0, 64*1024), 64*1024*1024)
	var result map[string]any
	for scanner.Scan() {
		line := cleanLogLine(scanner.Text())
		if jsonlMode {
			parsed := e.handleJsonlLine(line, cmd, agent)
			if parsed != nil {
				result = parsed
			}
		} else {
			agent.send(Report{Type: "log", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Level: "INFO", Message: line})
			if strings.HasPrefix(line, "step ") {
				if m := parseStep(line); m != nil {
					agent.send(Report{Type: "metrics", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Metrics: m})
				}
			}
			if strings.HasPrefix(line, "RESULT ") {
				var r map[string]any
				if json.Unmarshal([]byte(line[len("RESULT "):]), &r) == nil {
					result = r
				}
			}
		}
	}

	// scanner 退出可能是 ErrTooLong（单行超 buffer）或读管道错误；之前静默吞掉，
	// 导致容器后续 stdout 全丢、log/metric/result 帧都不上报。这里暴露出来便于排查。
	if err := scanner.Err(); err != nil {
		log.Printf("[agent] stdout scanner 错误 taskId=%d: %v", cmd.TaskID, err)
	}

	err = dockerCmd.Wait()
	e.mu.Lock()
	wasStopped := e.stopped[cmd.TaskID]
	e.mu.Unlock()
	if err != nil {
		if wasStopped {
			e.reportTerminal(agent, cmd, "cancelled", "", "")
			log.Printf("[agent] 任务 %d 已停止（cancelled）", cmd.TaskID)
			return
		}
		errMsg := err.Error()
		if tail := tailN(stderrBuf.String(), 4000); tail != "" {
			errMsg = errMsg + "\n--- container stderr (tail) ---\n" + tail
		}
		e.reportTerminal(agent, cmd, "failed", "", errMsg)
		log.Printf("[agent] 任务 %d 失败: %v stderr=%s", cmd.TaskID, err, tailN(stderrBuf.String(), 1000))
		return
	}
	rbytes, _ := json.Marshal(result)
	e.reportTerminal(agent, cmd, "completed", string(rbytes), "")
	log.Printf("[agent] 任务 %d 完成 result=%s", cmd.TaskID, string(rbytes))
}

// handleJsonlLine 解析统一脚本输出的一行 JSON，映射为平台上报帧。返回 result map 当且仅当该行是 result 帧。
func (e *Executor) handleJsonlLine(line string, cmd *Command, agent *Agent) map[string]any {
	line = strings.TrimSpace(line)
	if line == "" || line[0] != '{' {
		// 非 JSON 行：作为日志回传（兼容混合输出）
		agent.send(Report{Type: "log", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Level: "INFO", Message: line})
		return nil
	}
	var obj map[string]any
	if err := json.Unmarshal([]byte(line), &obj); err != nil {
		agent.send(Report{Type: "log", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Level: "INFO", Message: line})
		return nil
	}
	switch t, _ := obj["type"].(string); t {
	case "status":
		agent.send(Report{Type: "status", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Data: obj})
	case "log":
		// 不带 Data:obj——resource forwardLog 只读 level/message，Data 会携带未截断的原始 message，
		// 长 ERROR 帧（带 traceback）可能超 WS 文本上限触发 close 1009。Message 已 cleanLogLine 截断。
		agent.send(Report{Type: "log", TaskID: cmd.TaskID, TaskType: cmd.TaskType,
			Level: strOr(obj, "level", "INFO"), Message: cleanLogLine(strOr(obj, "message", ""))})
	case "metric":
		m, _ := obj["metrics"].(map[string]any)
		if m == nil {
			m = map[string]any{}
		}
		// Data: obj 透传整个 metric 帧（含 step/progress/total），resource 据此填 TaskMetricsMessage.step。
		// Metrics 字段保留给 resource forwardMetrics 直接读 msg.metrics()。
		agent.send(Report{Type: "metrics", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Metrics: m, Data: obj})
		// 兼容：把 progress 也作为 status 帧上报，便于平台更新进度
		if p, ok := obj["progress"].(float64); ok {
			agent.send(Report{Type: "status", TaskID: cmd.TaskID, TaskType: cmd.TaskType,
				Data: map[string]any{"status": "running", "progress": int(p)}})
		}
	case "result":
		return obj
	default:
		agent.send(Report{Type: "log", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Level: "INFO", Message: line})
	}
	return nil
}

// reportTerminal 上报终态（type=result，data 内 status/result/error）。
func (e *Executor) reportTerminal(agent *Agent, cmd *Command, status, resultJson, errMsg string) {
	data := map[string]any{"status": status, "progress": 100}
	if resultJson != "" {
		var rd map[string]any
		if json.Unmarshal([]byte(resultJson), &rd) == nil {
			data["result"] = rd
		} else {
			data["result"] = map[string]any{"raw": resultJson}
		}
	} else if status == "completed" && resultJson == "" {
		data["result"] = map[string]any{}
	}
	if errMsg != "" {
		data["error"] = errMsg
	}
	agent.send(Report{Type: "result", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Data: data})
}

// Stop 主动终止任务容器。
func (e *Executor) Stop(taskID int64) {
	e.mu.Lock()
	if _, ok := e.procs[taskID]; !ok {
		e.mu.Unlock()
		return
	}
	e.stopped[taskID] = true
	e.mu.Unlock()
	_ = exec.Command("docker", "kill", fmt.Sprintf("task-%d", taskID)).Run()
}

// runShell 执行远程 shell 命令并回传结果。
func (e *Executor) runShell(data map[string]any, agent *Agent) {
	commandID, _ := data["commandId"].(string)
	command, _ := data["command"].(string)
	timeoutF, _ := data["timeout"].(float64)
	timeout := time.Duration(int(timeoutF)) * time.Second
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	c := exec.CommandContext(ctx, "sh", "-c", command)
	var outBuf, errBuf strings.Builder
	c.Stdout = &outBuf
	c.Stderr = &errBuf
	err := c.Run()
	exitCode := 0
	timedOut := false
	if err != nil {
		if ctx.Err() == context.DeadlineExceeded {
			timedOut = true
			exitCode = -1
		} else if ee, ok := err.(*exec.ExitError); ok {
			exitCode = ee.ExitCode()
		} else {
			exitCode = -1
		}
	}
	_ = agent.send(Report{Type: "shell_result", Data: map[string]any{
		"commandId": commandID, "exitCode": exitCode,
		"stdout": outBuf.String(), "stderr": errBuf.String(), "timedOut": timedOut,
	}})
}

// downloadAndLoadImage 下载镜像 tar 并 docker load，返回镜像名（解析 "Loaded image: <name>"）。
func downloadAndLoadImage(url string, taskID int64) (string, error) {
	tarPath := filepath.Join(os.TempDir(), fmt.Sprintf("aisys-image-%d.tar", taskID))
	if err := httpDownload(url, tarPath); err != nil {
		return "", err
	}
	out, err := exec.Command("docker", "load", "-i", tarPath).CombinedOutput()
	_ = os.Remove(tarPath)
	if err != nil {
		return "", fmt.Errorf("docker load: %w (%s)", err, strings.TrimSpace(string(out)))
	}
	for _, line := range strings.Split(string(out), "\n") {
		if i := strings.Index(line, "Loaded image:"); i >= 0 {
			return strings.TrimSpace(line[i+len("Loaded image:"):]), nil
		}
	}
	return "", nil
}

// httpDownload 流式下载 URL 到本地文件。
func httpDownload(url, dst string) error {
	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	f, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer f.Close()
	_, err = io.Copy(f, resp.Body)
	return err
}

// uploadFile 以 HTTP PUT 上传文件到预签名 URL（训练产物 best.pt 回传）。
func uploadFile(url, src string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()
	req, err := http.NewRequest(http.MethodPut, url, f)
	if err != nil {
		return err
	}
	// 大权重文件可能几十~几百 MB，给 10 分钟上限，避免 SeaweedFS 不可达时无限挂起
	// （挂起会导致 task goroutine 泄漏、第二个 status 上报永不发出，前端无下载按钮）。
	client := &http.Client{Timeout: 10 * time.Minute}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return nil
}

// tailN 返回字符串最后 n 个字符（用于截取容器 stderr 尾部，避免上报过长）。
func tailN(s string, n int) string {
	s = strings.TrimSpace(s)
	if n <= 0 || len(s) <= n {
		return s
	}
	return s[len(s)-n:]
}

// downloadAndPrepareDataset 下载数据集对象；归档（zip/tar/tar.gz）解压到 datasetDir，
// 否则作为单文件落到 datasetDir/dataset。容器侧 run.py 递归查找 data.yaml。
func downloadAndPrepareDataset(url, datasetDir string) error {
	tmp, err := os.CreateTemp(datasetDir, ".dl-*")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)
	tmp.Close()
	if err := httpDownload(url, tmpPath); err != nil {
		return err
	}
	if fi, e := os.Stat(tmpPath); e == nil {
		log.Printf("[agent] 数据集已下载 size=%d", fi.Size())
	}
	return extractOrPlace(tmpPath, datasetDir)
}

// extractOrPlace 按魔数判定归档类型并解压；非归档则原样放置为 dataset 文件。
func extractOrPlace(src, dst string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	head := make([]byte, 512)
	n, _ := io.ReadFull(f, head)
	f.Close()
	head = head[:n]
	var kind string
	var extractErr error
	switch {
	case isZip(head):
		kind = "zip"
		extractErr = extractZip(src, dst)
	case isGzip(head):
		kind = "gzip"
		extractErr = extractTarGz(src, dst)
	case isTar(head):
		kind = "tar"
		extractErr = extractTar(src, dst)
	default:
		kind = "raw"
		extractErr = os.Rename(src, filepath.Join(dst, "dataset"))
	}
	hasDataYaml := false
	if de, e := os.Stat(filepath.Join(dst, "data.yaml")); e == nil && !de.IsDir() {
		hasDataYaml = true
	}
	log.Printf("[agent] 数据集处理 kind=%s err=%v data.yaml=%v", kind, extractErr, hasDataYaml)
	return extractErr
}

func isZip(h []byte) bool {
	return len(h) >= 4 && h[0] == 0x50 && h[1] == 0x4b && (h[2] == 0x03 || h[2] == 0x05 || h[2] == 0x07)
}

func isGzip(h []byte) bool {
	return len(h) >= 2 && h[0] == 0x1f && h[1] == 0x8b
}

func isTar(h []byte) bool {
	return len(h) >= 262 && string(h[257:262]) == "ustar"
}

// safeJoin 防范 zip-slip / tar-slip：确保解压目标在 dst 之内。
func safeJoin(dst, name string) (string, error) {
	cleanDst := filepath.Clean(dst)
	target := filepath.Clean(filepath.Join(cleanDst, name))
	if target != cleanDst && !strings.HasPrefix(target, cleanDst+string(os.PathSeparator)) {
		return "", fmt.Errorf("unsafe archive entry: %s", name)
	}
	return target, nil
}

func extractZip(src, dst string) error {
	r, err := zip.OpenReader(src)
	if err != nil {
		return err
	}
	defer r.Close()
	for _, f := range r.File {
		p, err := safeJoin(dst, f.Name)
		if err != nil {
			continue
		}
		if f.FileInfo().IsDir() {
			os.MkdirAll(p, 0o755)
			continue
		}
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			continue
		}
		out, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
		if err != nil {
			rc.Close()
			continue
		}
		_, _ = io.Copy(out, rc)
		out.Close()
		rc.Close()
	}
	return nil
}

func extractTarGz(src, dst string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()
	gz, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer gz.Close()
	return extractTarReader(tar.NewReader(gz), dst)
}

func extractTar(src, dst string) error {
	f, err := os.Open(src)
	if err != nil {
		return err
	}
	defer f.Close()
	return extractTarReader(tar.NewReader(f), dst)
}

func extractTarReader(tr *tar.Reader, dst string) error {
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		p, err := safeJoin(dst, hdr.Name)
		if err != nil {
			continue
		}
		switch hdr.Typeflag {
		case tar.TypeDir:
			os.MkdirAll(p, os.FileMode(hdr.Mode&0o755))
		case tar.TypeReg, tar.TypeRegA, tar.TypeSymlink:
			if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
				continue
			}
			out, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, os.FileMode(hdr.Mode&0o777))
			if err != nil {
				continue
			}
			_, _ = io.Copy(out, tr)
			out.Close()
		}
	}
	return nil
}

// parseStep 解析旧模拟脚本 "step i/t loss L lr E accuracy A"。
func parseStep(line string) map[string]any {
	parts := strings.Fields(line)
	m := map[string]any{}
	if len(parts) >= 2 && parts[0] == "step" {
		it := strings.SplitN(parts[1], "/", 2)
		if len(it) == 2 {
			if i, err := strconv.ParseFloat(it[0], 64); err == nil {
				m["step"] = i
			}
		}
	}
	kv := map[string]int{"loss": 2, "lr": 4, "accuracy": 6}
	for k, idx := range kv {
		if idx < len(parts) {
			if v, err := strconv.ParseFloat(parts[idx], 64); err == nil {
				m[k] = v
			}
		}
	}
	if len(m) == 0 {
		return nil
	}
	return m
}

func cleanLogLine(line string) string {
	s := strings.Map(func(r rune) rune {
		if r < 32 && r != '\t' && r != '\n' && r != '\r' {
			return -1
		}
		return r
	}, ansiEscapeRE.ReplaceAllString(line, ""))
	// tqdm/ultralytics 进度条用 \r 原地刷新，多次更新被 bufio.Scanner（只按 \n 分割）串成一行。
	// 取最后一个 \r 段（最新进度状态），避免一行日志累积成超长字符串、也避免 \r 在前端造成渲染错乱。
	if idx := strings.LastIndex(s, "\r"); idx >= 0 {
		s = s[idx+1:]
	}
	// 仍可能产生超长行（如长 traceback），截断到合理长度避免 WS close 1009。按 rune 截断避免切断多字节字符。
	const maxRunes = 2000
	if r := []rune(s); len(r) > maxRunes {
		s = string(r[:maxRunes]) + "…(truncated, " + strconv.Itoa(len(r)) + " runes total)"
	}
	return s
}

func strOr(m map[string]any, key, def string) string {
	if v, ok := m[key]; ok && v != nil {
		return fmt.Sprintf("%v", v)
	}
	return def
}

// resolveDatasetMountDir 检测数据集目录结构，返回应挂载到容器的路径和格式标识。
// 平台侧处理此逻辑后，模型 run.py 无需关心数据集是三分割还是扁平格式——它看到的 /data/dataset
// 总是可直接使用的 ImageFolder 目录。
//
// 返回值：
//   - mountPath: 实际挂载到容器 /data/dataset 的宿主路径
//   - mode: "split"（三分割子目录）或 "flat"（扁平 ImageFolder）
func resolveDatasetMountDir(datasetDir string, taskMode string) (mountPath string, mode string) {
	entries, err := os.ReadDir(datasetDir)
	if err != nil || len(entries) == 0 {
		return datasetDir, "flat"
	}

	// 检测三分割格式：要求 train/val/test 中至少 2 个是有效目录（含文件）
	splitNames := map[string]bool{"train": false, "val": false, "test": false}
	validSplits := 0
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		if _, ok := splitNames[e.Name()]; ok {
			subPath := filepath.Join(datasetDir, e.Name())
			if subs, err := os.ReadDir(subPath); err == nil && len(subs) > 0 {
				splitNames[e.Name()] = true
				validSplits++
			}
		}
	}

	// 有效分割不足 2 个 → 按扁平格式处理
	if validSplits < 2 {
		return datasetDir, "flat"
	}

	// 根据任务模式选择挂载的子目录
	var chosen string
	switch taskMode {
	case "eval", "evaluate", "evaluation":
		// 评测优先用验证集，备选用测试集，最后兜底训练集
		if splitNames["val"] {
			chosen = "val"
		} else if splitNames["test"] {
			chosen = "test"
		} else {
			chosen = "train"
		}
	case "train", "training":
		// 训练优先用训练集（后续 run.py 可自行合并 val 做早停）
		if splitNames["train"] {
			chosen = "train"
		} else {
			for s, ok := range splitNames {
				if ok { chosen = s; break }
			}
		}
	default:
		for s, ok := range splitNames {
			if ok { chosen = s; break }
		}
	}

	mountPath = filepath.Join(datasetDir, chosen)
	log.Printf("[agent] 三分割数据集检测: 任务=%s, 选择 %s/ → %s (rw)", taskMode, chosen, mountPath)
	return mountPath, "split"
}
