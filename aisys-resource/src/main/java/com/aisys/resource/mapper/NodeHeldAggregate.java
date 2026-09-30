package com.aisys.resource.mapper;

/**
 * 节点 held 租约聚合结果（MyBatis 自动映射，列名 sum_gpu/sum_cpu/sum_memory/cnt）。
 * <p>用于调度计算空闲量与 getNodeMetrics。
 */
public class NodeHeldAggregate {

    private Long nodeId;
    private Long sumGpu;
    private Long sumCpu;
    private Long sumMemory;
    private Integer cnt;

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getSumGpu() { return sumGpu; }
    public void setSumGpu(Long sumGpu) { this.sumGpu = sumGpu; }
    public Long getSumCpu() { return sumCpu; }
    public void setSumCpu(Long sumCpu) { this.sumCpu = sumCpu; }
    public Long getSumMemory() { return sumMemory; }
    public void setSumMemory(Long sumMemory) { this.sumMemory = sumMemory; }
    public Integer getCnt() { return cnt; }
    public void setCnt(Integer cnt) { this.cnt = cnt; }

    public long gpuOrZero() { return sumGpu == null ? 0L : sumGpu; }
    public long cpuOrZero() { return sumCpu == null ? 0L : sumCpu; }
    public long memOrZero() { return sumMemory == null ? 0L : sumMemory; }
    public int cntOrZero() { return cnt == null ? 0 : cnt; }
}
