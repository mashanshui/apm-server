package com.shanshui.apmserver.domain;

/** 白名单多维指标点；根据 metric 选择 FPS 或挂起率字段。 */
public record MetricDimensionPoint(
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

    public static MetricDimensionPoint from(MetricDimensionAggregate aggregate) {
        return new MetricDimensionPoint(aggregate.dimensionValue(), aggregate.algorithmVersion(),
                aggregate.totalRecords(), aggregate.validRecords(), aggregate.validDeviceDayRecords(),
                aggregate.averageFps(), aggregate.p50Fps(), aggregate.p90Fps(), aggregate.p99Fps(),
                aggregate.averageSecondsPerHour(), aggregate.p50SecondsPerHour(),
                aggregate.p90SecondsPerHour(), aggregate.p99SecondsPerHour(), aggregate.status());
    }
}
