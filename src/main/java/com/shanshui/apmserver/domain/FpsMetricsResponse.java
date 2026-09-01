package com.shanshui.apmserver.domain;

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
