package com.shanshui.apmserver.jank.api;

import java.time.Instant;

public record JankIssueSummary(
        String fingerprint,
        String fingerprintVersion,
        String scene,
        String algorithmVersion,
        long eventCount,
        long affectedSessionCount,
        long affectedDeviceCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        JankDurationPercentiles exactMessageDuration,
        JankDurationPercentiles estimatedStackDuration) {
}
