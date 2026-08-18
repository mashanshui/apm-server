package com.shanshui.apmserver.domain;

import java.time.Instant;

public record CrashIssueSummary(
        String fingerprint,
        String fingerprintVersion,
        String exceptionType,
        long eventCount,
        long crashedSessionCount,
        long affectedDeviceCount,
        Instant firstSeenAt,
        Instant lastSeenAt) {
}
