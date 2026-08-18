package com.shanshui.apmserver.domain;

import java.time.Instant;

public record CrashQueryFilter(
        String projectId,
        Instant from,
        Instant to,
        String appVersion,
        String channel,
        String environment,
        String osVersion,
        String deviceModel,
        String fingerprint,
        int limit,
        String cursor,
        long timeoutMs) {
}
