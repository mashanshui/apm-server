package com.shanshui.apmserver.jank.api;

import java.time.Instant;
import java.util.List;

public record JankIssueResponse(
        java.util.UUID appId,
        Instant from,
        Instant to,
        List<JankIssueSummary> issues,
        String nextCursor,
        String status,
        String dataSource) {
}
