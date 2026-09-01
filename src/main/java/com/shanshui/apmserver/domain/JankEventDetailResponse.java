package com.shanshui.apmserver.domain;

import java.time.Instant;

public record JankEventDetailResponse(
        java.util.UUID appId,
        String eventId,
        String packageName,
        Instant occurredAt,
        Instant receivedAt,
        String sessionId,
        String anonymousDeviceId,
        String appVersion,
        Integer versionCode,
        String buildId,
        String channel,
        String environment,
        String osVersion,
        String deviceModel,
        String networkType,
        String scene,
        String algorithmVersion,
        String fingerprint,
        String fingerprintVersion,
        JankPayload jank,
        JankAnalysis analysis) {
}
