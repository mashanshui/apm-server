package com.shanshui.apmserver.domain;

public record CrashStats(
        long startedSessions,
        long crashEvents,
        long crashedSessions,
        long affectedDevices,
        Double crashRatePer1000Sessions,
        Double crashFreeSessionRate,
        String status) {
}
