package com.shanshui.apmserver.jank.api;

import java.time.Instant;
import java.util.List;

public record MetricTrendResponse(
        java.util.UUID appId,
        Instant from,
        Instant to,
        String metric,
        String interval,
        List<MetricTrendPoint> points,
        String status,
        String dataSource) {
}
