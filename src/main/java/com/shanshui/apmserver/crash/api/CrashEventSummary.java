package com.shanshui.apmserver.crash.api;

import java.time.Instant;

public record CrashEventSummary(
        String eventId,
        Instant occurredAt,
        String appVersion,
        Integer versionCode,
        String buildId,
        String channel,
        String environment,
        String osVersion,
        String deviceModel,
        String sessionId,
        String anonymousDeviceId,
        String exceptionType,
        String fingerprint,
        String symbolicationStatus) {
}
