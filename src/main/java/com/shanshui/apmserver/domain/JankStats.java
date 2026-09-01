package com.shanshui.apmserver.domain;

public record JankStats(
        long jankEvents,
        long affectedSessions,
        long affectedDevices,
        long groupableEvents,
        JankDurationPercentiles exactMessageDuration,
        String status) {
}
