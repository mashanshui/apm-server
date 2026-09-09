package com.shanshui.apmserver.memory.internal.domain;

import com.shanshui.apmserver.memory.api.MemoryMetricStats;

import java.time.Instant;

/** 存储适配器返回的一个时间桶聚合。 */
public record MemoryTrendAggregate(Instant bucketStart, Instant bucketEnd, MemoryMetricStats stats) {
}
