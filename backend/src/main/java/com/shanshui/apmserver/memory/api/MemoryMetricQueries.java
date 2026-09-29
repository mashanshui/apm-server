package com.shanshui.apmserver.memory.api;

import java.util.UUID;

/** 网页与 Agent 共用的内存指标查询契约。 */
public interface MemoryMetricQueries {
    MemoryMetricsSummaryResponse summary(UUID appId, String from, String to, MemoryMetricQuery params);
    MemoryTrendResponse trend(UUID appId, String metric, String interval,
                              String from, String to, MemoryMetricQuery params);
}
