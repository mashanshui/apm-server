package com.shanshui.apmserver.crash.api;

import java.time.Instant;

public record CrashIssueSummary(
        String fingerprint,
        String fingerprintVersion,
        String exceptionType,
        long eventCount,
        long crashedSessionCount,
        long affectedDeviceCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        /** 当前归组类别。 */ String groupingKind,
        /** 临时问题的构建，正式问题为空。 */ String buildId) {
}
