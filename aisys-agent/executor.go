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
	"strconv"
	"strings"
	"sync"
	"time"
)

// Executor 执行任务（DDD 7.2）：
//   - 「每个模型是一个容器」：从存储池下载镜像 tar（docker load）+ 数据集，挂载后 docker run 真实模型容器，
//     解析其统一脚本输出的 JSONL（status/log/metric/result）实时回传平台。
//   - 兼容旧路径：无镜像 tar 时回退到内置 CPU 模拟脚本（step 行 + RESULT）。
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
			if imageName == "" {
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

	name := fmt.Sprintf("task-%d", cmd.TaskID)
	args := []string{"run", "--rm", "--name", name,
		"-v", datasetDir + ":/data/dataset:ro",
		"-v", outputDir + ":/output",
		"-e", "AISYS_DATASET_DIR=/data/dataset",
		"-e", "AISYS_OUTPUT_DIR=/output",
	}
	taskMode := cmd.TaskMode
	if taskMode == "" {
		taskMode = "train"
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

	// 训练产物回传：容器退出后、defer 删除 workDir 之前，把 best.pt PUT 到平台预签名 URL，
	// 否则 best.pt 随 workDir 一起被清理，训练成果无法落盘（仅 TRAINING，平台仅对此下发 outputUploadUrl）。
	if cmd.OutputUploadUrl != "" {
		best := filepath.Join(outputDir, "best.pt")
		if _, err := os.Stat(best); err == nil {
			if err := uploadFile(cmd.OutputUploadUrl, best); err != nil {
				log.Printf("[agent] 上传训练产物失败 taskId=%d: %v", cmd.TaskID, err)
			} else {
				log.Printf("[agent] 已上传训练产物 best.pt taskId=%d", cmd.TaskID)
			}
		} else {
			log.Printf("[agent] 无 best.pt 可上传 taskId=%d（路径 %s）", cmd.TaskID, best)
		}
	}
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
	scanner.Buffer(make([]byte, 0, 64*1024), 4*1024*1024)
	var result map[string]any
	for scanner.Scan() {
		line := scanner.Text()
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
		agent.send(Report{Type: "log", TaskID: cmd.TaskID, TaskType: cmd.TaskType,
			Level: strOr(obj, "level", "INFO"), Message: strOr(obj, "message", ""), Data: obj})
	case "metric":
		m, _ := obj["metrics"].(map[string]any)
		if m == nil {
			m = map[string]any{}
		}
		agent.send(Report{Type: "metrics", TaskID: cmd.TaskID, TaskType: cmd.TaskType, Metrics: m})
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
	resp, err := http.DefaultClient.Do(req)
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

func strOr(m map[string]any, key, def string) string {
	if v, ok := m[key]; ok && v != nil {
		return fmt.Sprintf("%v", v)
	}
	return def
}
