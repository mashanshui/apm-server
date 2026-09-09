package com.shanshui.apmserver.memory.api;

/** 单项内存指标的统计结果，数值保持字节单位以避免接口层产生单位歧义。 */
public record MemoryMetricStats(
        long sampleCount,
        Double averageBytes,
        Double p50Bytes,
        Double p90Bytes,
        Double p95Bytes,
        Double p99Bytes,
        String status) {
}
