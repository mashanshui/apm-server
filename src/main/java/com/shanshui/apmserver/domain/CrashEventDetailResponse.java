package com.shanshui.apmserver.domain;

import java.time.Instant;

public record CrashEventDetailResponse(
        String projectId,
        String eventId,
        String appId,
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
        String exceptionType,
        String fingerprint,
        String fingerprintVersion,
        String symbolicationStatus,
        CrashPayload rawCrash) {
}
