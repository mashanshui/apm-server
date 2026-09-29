package com.shanshui.apmserver.jank.api;

import com.shanshui.apmserver.platform.api.QueryParams;
import java.util.UUID;

/** 网页与 Agent 共用的 FPS 和挂起率查询契约。 */
public interface JankMetricQueries {
    FpsMetricsResponse fps(UUID appId, String from, String to, QueryParams params);
    SuspensionRateResponse suspensionRate(UUID appId, String from, String to, QueryParams params);
    MetricTrendResponse trend(UUID appId, String metric, String interval, String from, String to, QueryParams params);
    MetricDimensionsResponse dimensions(UUID appId, String metric, String dimension,
                                        String from, String to, QueryParams params);
}
