package com.shanshui.apmserver.domain;

import java.time.Instant;

public record CrashOverviewResponse(
        String projectId,
        Instant from,
        Instant to,
        CrashStats stats,
        String dataSource) {
}
