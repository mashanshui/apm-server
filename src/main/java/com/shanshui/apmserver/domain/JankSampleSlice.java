package com.shanshui.apmserver.domain;

/** 采样重建出的有限覆盖片段；uncovered 片段不会被伪造为连续执行。 */
public record JankSampleSlice(
        long startOffsetNs,
        long endOffsetNs,
        String stackId,
        boolean covered) {

    public long durationNs() {
        return Math.max(0L, endOffsetNs - startOffsetNs);
    }
}
