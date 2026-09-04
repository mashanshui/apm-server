package com.shanshui.apmserver.jank.api;

import java.time.Instant;

public record JankEventSummary(
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
        String scene,
        String algorithmVersion,
        String fingerprint,
        String fingerprintVersion,
        Double exactMessageDurationMs,
        Double estimatedDurationMs,
        Double estimatedUnattributedDurationMs,
        Double coveredDurationMs,
        Double uncoveredDurationMs) {
}
