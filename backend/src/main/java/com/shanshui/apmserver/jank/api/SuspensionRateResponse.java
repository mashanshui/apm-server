package com.shanshui.apmserver.jank.api;

import java.time.Instant;
import java.util.List;

public record SuspensionRateResponse(
        java.util.UUID appId,
        Instant from,
        Instant to,
        List<SuspensionRateStats> metrics,
        String status,
        String dataSource) {
}
