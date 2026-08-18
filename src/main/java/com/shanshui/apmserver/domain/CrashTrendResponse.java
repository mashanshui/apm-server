package com.shanshui.apmserver.domain;

import java.time.Instant;
import java.util.List;

public record CrashTrendResponse(
        String projectId,
        Instant from,
        Instant to,
        String interval,
        List<CrashTrendPoint> points,
        String dataSource) {
}
