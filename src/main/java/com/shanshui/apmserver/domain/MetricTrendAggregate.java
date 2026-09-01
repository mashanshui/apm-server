package com.shanshui.apmserver.domain;

import java.time.Instant;

/** 存储层统一指标趋势聚合；数值单位由 metric 决定。 */
public record MetricTrendAggregate(
        Instant bucketStart,
        Instant bucketEnd,
        String algorithmVersion,
        long totalRecords,
        long validRecords,
        Double average,
        Double p50,
        Double p90,
        Double p99,
        String status) {
}
