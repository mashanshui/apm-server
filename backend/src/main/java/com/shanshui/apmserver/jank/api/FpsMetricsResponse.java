package com.shanshui.apmserver.jank.api;

import java.time.Instant;
import java.util.List;

public record FpsMetricsResponse(
        java.util.UUID appId,
        Instant from,
        Instant to,
        List<FpsMetricStats> metrics,
        String status,
        String dataSource) {
}
