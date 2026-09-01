package com.shanshui.apmserver.domain;

import java.time.Instant;

/** FPS 与前台挂起率查询使用的公共筛选条件。 */
public record MetricQueryFilter(
        java.util.UUID appId,
        Instant from,
        Instant to,
        String appVersion,
        String channel,
        String environment,
        String osVersion,
        String deviceModel,
        String scene,
        String algorithmVersion,
        int limit,
        long timeoutMs) {
}
