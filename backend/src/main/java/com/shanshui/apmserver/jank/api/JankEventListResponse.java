package com.shanshui.apmserver.jank.api;

import java.time.Instant;
import java.util.List;

public record JankEventListResponse(
        java.util.UUID appId,
        String fingerprint,
        Instant from,
        Instant to,
        List<JankEventSummary> events,
        String nextCursor,
        String status,
        String dataSource) {
}
