package com.shanshui.apmserver.memory.api;

/** 一轮进程的观测峰值，时间均为原运行的 Unix 毫秒。 */
public record MemoryPeakSummaryPayload(
        /** 原进程名称。 */ String processName,
        /** 本轮开始采集时间。 */ Long collectionStartedAt,
        /** 最后持久化有效采样时间。 */ Long lastSampledAt,
        /** PSS 独立峰值。 */ MemoryPeak pss,
        /** VSS 独立峰值。 */ MemoryPeak vss,
        /** Java 堆独立峰值。 */ MemoryPeak javaHeap) {
    /** 返回 PSS 字节，缺失保持 null。 */
    public Long pssBytes() { return pss == null ? null : pss.bytes(); }
    /** 返回 VSS 字节，缺失保持 null。 */
    public Long vssBytes() { return vss == null ? null : vss.bytes(); }
    /** 返回 Java 堆字节，缺失保持 null。 */
    public Long javaHeapUsedBytes() { return javaHeap == null ? null : javaHeap.bytes(); }
}
