package com.shanshui.apmserver.domain;

import java.time.Instant;
import java.util.List;

public record MetricDimensionsResponse(
        java.util.UUID appId,
        Instant from,
        Instant to,
        String metric,
        String dimension,
        List<MetricDimensionPoint> points,
        String status,
        String dataSource) {
}
