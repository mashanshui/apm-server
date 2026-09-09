package com.shanshui.apmserver.memory.api;

import java.time.Instant;
import java.util.UUID;

/** 内存指标概览响应，三个指标分别统计，不把不同进程相加。 */
public record MemoryMetricsSummaryResponse(
        UUID appId,
        Instant from,
        Instant to,
        MemoryMetricStats pss,
        MemoryMetricStats vss,
        MemoryMetricStats javaHeap,
        String status,
        String dataSource) {
}
