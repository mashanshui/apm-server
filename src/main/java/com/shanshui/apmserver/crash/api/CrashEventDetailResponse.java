package com.shanshui.apmserver.crash.api;

import java.time.Instant;

public record CrashEventDetailResponse(
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
        String exceptionType,
        String fingerprint,
        String fingerprintVersion,
        String symbolicationStatus,
        CrashPayload rawCrash) {
}
