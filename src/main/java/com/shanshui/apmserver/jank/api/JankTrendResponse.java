package com.shanshui.apmserver.jank.api;

import java.time.Instant;
import java.util.List;

public record JankTrendResponse(
        java.util.UUID appId,
        Instant from,
        Instant to,
        String interval,
        List<JankTrendPoint> points,
        String status,
        String dataSource) {
}
