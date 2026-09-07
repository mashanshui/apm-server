package com.shanshui.apmserver.jank.api;

/** 按挂起算法版本隔离后的设备日挂起率聚合结果。 */
public record SuspensionMetricAggregate(
        String algorithmVersion,
        long totalRecords,
        long validDeviceDayRecords,
        Double averageSecondsPerHour,
        Double p50SecondsPerHour,
        Double p90SecondsPerHour,
        Double p99SecondsPerHour,
        String status) {
}
