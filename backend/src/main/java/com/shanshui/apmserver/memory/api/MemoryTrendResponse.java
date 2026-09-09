package com.shanshui.apmserver.memory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 内存指标趋势响应；一次返回全部分位数，页面只切换展示列。 */
public record MemoryTrendResponse(
        UUID appId,
        Instant from,
        Instant to,
        String metric,
        String interval,
        List<MemoryTrendPoint> points,
        String status,
        String dataSource) {

    public MemoryTrendResponse {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
