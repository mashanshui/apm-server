package com.shanshui.apmserver.jank.api;

public record JankStats(
        long jankEvents,
        long affectedSessions,
        long affectedDevices,
        long groupableEvents,
        JankDurationPercentiles exactMessageDuration,
        String status) {
}
