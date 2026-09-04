package com.shanshui.apmserver.jank.api;

/** 白名单维度聚合结果；metric 决定两组数值字段中哪一组有效。 */
public record MetricDimensionAggregate(
        String metric,
        String dimension,
        String dimensionValue,
        String algorithmVersion,
        long totalRecords,
        long validRecords,
        long validDeviceDayRecords,
        Double averageFps,
        Double p50Fps,
        Double p90Fps,
        Double p99Fps,
        Double averageSecondsPerHour,
        Double p50SecondsPerHour,
        Double p90SecondsPerHour,
        Double p99SecondsPerHour,
        String status) {
}
