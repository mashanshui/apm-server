package com.shanshui.apmserver.domain;

import java.time.Instant;

public record CrashOverviewResponse(
        java.util.UUID appId,
        Instant from,
        Instant to,
        CrashStats stats,
        String dataSource) {
}
