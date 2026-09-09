package com.shanshui.apmserver.memory.api;

import java.time.Instant;

/** 单个 UTC 时间桶的内存统计。空桶的统计值为 null。 */
public record MemoryTrendPoint(
        Instant bucketStart,
        Instant bucketEnd,
        long sampleCount,
        Double averageBytes,
        Double p50Bytes,
        Double p90Bytes,
        Double p95Bytes,
        Double p99Bytes,
        String status) {

    public static MemoryTrendPoint from(Instant bucketStart, Instant bucketEnd, MemoryMetricStats stats) {
        return new MemoryTrendPoint(bucketStart, bucketEnd, stats.sampleCount(), stats.averageBytes(),
                stats.p50Bytes(), stats.p90Bytes(), stats.p95Bytes(), stats.p99Bytes(), stats.status());
    }
}
