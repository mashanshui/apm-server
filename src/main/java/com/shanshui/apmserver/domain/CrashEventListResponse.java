package com.shanshui.apmserver.domain;

import java.time.Instant;
import java.util.List;

public record CrashEventListResponse(
        java.util.UUID appId,
        String fingerprint,
        Instant from,
        Instant to,
        List<CrashEventSummary> events,
        String nextCursor,
        String dataSource) {
}
