package com.shanshui.apmserver.jank.api;

import com.shanshui.apmserver.jank.api.SuspensionMetricAggregate;

/** 对外设备日挂起率查询结果；比率单位为挂起秒/前台小时。 */
public record SuspensionRateStats(
        String algorithmVersion,
        long totalRecords,
        long validDeviceDayRecords,
        Double averageSecondsPerHour,
        Double p50SecondsPerHour,
        Double p90SecondsPerHour,
        Double p99SecondsPerHour,
        String status) {

    public static SuspensionRateStats from(SuspensionMetricAggregate aggregate) {
        return new SuspensionRateStats(aggregate.algorithmVersion(), aggregate.totalRecords(),
                aggregate.validDeviceDayRecords(), aggregate.averageSecondsPerHour(),
                aggregate.p50SecondsPerHour(), aggregate.p90SecondsPerHour(),
                aggregate.p99SecondsPerHour(), aggregate.status());
    }
}
