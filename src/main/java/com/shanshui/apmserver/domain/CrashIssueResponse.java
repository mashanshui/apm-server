package com.shanshui.apmserver.domain;

import java.time.Instant;
import java.util.List;

public record CrashIssueResponse(
        String projectId,
        Instant from,
        Instant to,
        List<CrashIssueSummary> issues,
        String nextCursor,
        String dataSource) {
}
