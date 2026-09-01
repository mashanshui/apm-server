package com.shanshui.apmserver.domain;

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
