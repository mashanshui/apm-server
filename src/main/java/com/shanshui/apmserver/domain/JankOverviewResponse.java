package com.shanshui.apmserver.domain;

import java.time.Instant;

public record JankOverviewResponse(
        java.util.UUID appId,
        Instant from,
        Instant to,
        JankStats stats,
        String dataSource) {
}
