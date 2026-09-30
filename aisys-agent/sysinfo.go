package main

import (
	"net"
	"os"
	"strconv"
	"strings"
	"syscall"
)

// readRealCPUModel 读取真实 CPU 型号（/proc/cpuinfo）。
func readRealCPUModel() string {
	data, err := os.ReadFile("/proc/cpuinfo")
	if err != nil {
		return "unknown"
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "model name") {
			parts := strings.SplitN(line, ":", 2)
			if len(parts) == 2 {
				return strings.TrimSpace(parts[1])
			}
		}
	}
	return "unknown"
}

// readRealMemory 读取真实内存总量（字节，/proc/meminfo）。
func readRealMemory() int64 {
	data, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(string(data), "\n") {
		if strings.HasPrefix(line, "MemTotal:") {
			fields := strings.Fields(line)
			if len(fields) >= 2 {
				if kb, err := strconv.ParseInt(fields[1], 10, 64); err == nil {
					return kb * 1024
				}
			}
		}
	}
	return 0
}

// readRealDisk 读取根分区磁盘总量（字节）。
func readRealDisk() int64 {
	var stat syscall.Statfs_t
	if err := syscall.Statfs("/", &stat); err != nil {
		return 0
	}
	return int64(stat.Blocks) * int64(stat.Bsize)
}

// pickDisk 返回节点磁盘总量：优先使用 NODE_DISK_GB 覆盖值（宿主真实容量），
// 否则回退到自动检测（容器内为 overlay 层大小）。容器化 Agent 建议设置 NODE_DISK_GB。
func pickDisk(overrideBytes int64) int64 {
	if overrideBytes > 0 {
		return overrideBytes
	}
	return readRealDisk()
}

// detectLocalIP 自动检测本机非 loopback IP。
func detectLocalIP() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ""
	}
	for _, addr := range addrs {
		if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() {
			if ip4 := ipNet.IP.To4(); ip4 != nil {
				return ip4.String()
			}
		}
	}
	return ""
}
