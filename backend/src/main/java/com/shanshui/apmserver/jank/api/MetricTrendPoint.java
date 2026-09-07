package com.shanshui.apmserver.jank.api;

import com.shanshui.apmserver.jank.api.MetricTrendAggregate;

import java.time.Instant;

/** 指标趋势时间桶；只为当前 metric 填充对应单位的数值字段。 */
public record MetricTrendPoint(
        Instant bucketStart,
        Instant bucketEnd,
        String algorithmVersion,
        long totalRecords,
        long validRecords,
        Double averageFps,
        Double p50Fps,
        Double p90Fps,
        Double p99Fps,
        Double averageSecondsPerHour,
        Double p50SecondsPerHour,
        Double p90SecondsPerHour,
        Double p99SecondsPerHour,
        String status) {

    public static MetricTrendPoint from(String metric, MetricTrendAggregate aggregate) {
        boolean fps = "fps".equals(metric);
        return new MetricTrendPoint(aggregate.bucketStart(), aggregate.bucketEnd(), aggregate.algorithmVersion(),
                aggregate.totalRecords(), aggregate.validRecords(),
                fps ? aggregate.average() : null, fps ? aggregate.p50() : null,
                fps ? aggregate.p90() : null, fps ? aggregate.p99() : null,
                fps ? null : aggregate.average(), fps ? null : aggregate.p50(),
                fps ? null : aggregate.p90(), fps ? null : aggregate.p99(), aggregate.status());
    }
}
