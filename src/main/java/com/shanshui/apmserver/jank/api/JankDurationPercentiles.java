package com.shanshui.apmserver.jank.api;

/** 卡顿耗时分位数，单位为毫秒；estimated 字段只能用于采样估算。 */
public record JankDurationPercentiles(
        Double p50Ms,
        Double p90Ms,
        Double p99Ms) {
}
