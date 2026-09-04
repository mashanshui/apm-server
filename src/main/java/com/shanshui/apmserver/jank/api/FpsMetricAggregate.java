package com.shanshui.apmserver.jank.api;

/** 按 FPS 算法版本隔离后的场景帧聚合结果；分位数按 FPS 从高到低计算。 */
public record FpsMetricAggregate(
        String algorithmVersion,
        long totalRecords,
        long validRecords,
        Double averageFps,
        Double p50Fps,
        Double p90Fps,
        Double p99Fps,
        String status) {
}
