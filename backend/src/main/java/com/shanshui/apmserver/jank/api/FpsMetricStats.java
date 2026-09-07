package com.shanshui.apmserver.jank.api;

import com.shanshui.apmserver.jank.api.FpsMetricAggregate;

/** 对外 FPS 查询结果；单位为帧/秒。 */
public record FpsMetricStats(
        String algorithmVersion,
        long totalRecords,
        long validRecords,
        Double averageFps,
        Double p50Fps,
        Double p90Fps,
        Double p99Fps,
        String status) {

    public static FpsMetricStats from(FpsMetricAggregate aggregate) {
        return new FpsMetricStats(aggregate.algorithmVersion(), aggregate.totalRecords(),
                aggregate.validRecords(), aggregate.averageFps(), aggregate.p50Fps(),
                aggregate.p90Fps(), aggregate.p99Fps(), aggregate.status());
    }
}
