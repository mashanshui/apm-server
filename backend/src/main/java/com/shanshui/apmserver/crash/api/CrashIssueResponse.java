package com.shanshui.apmserver.crash.api;

import java.time.Instant;
import java.util.List;

public record CrashIssueResponse(
        java.util.UUID appId,
        Instant from,
        Instant to,
        List<CrashIssueSummary> issues,
        String nextCursor,
        String dataSource) {
}
