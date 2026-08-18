package com.shanshui.apmserver.domain;

import java.time.Instant;
import java.util.Map;

public record StoredEvent(
        String projectId,
        String appId,
        String eventId,
        String eventType,
        Instant occurredAt,
        Instant receivedAt,
        int schemaVersion,
        String sessionId,
        String anonymousDeviceId,
        String appVersion,
        Integer versionCode,
        String buildId,
        String environment,
        String channel,
        String osVersion,
        String deviceModel,
        String networkType,
        Map<String, Object> measurements,
        Map<String, Object> attributes,
        String crashKind,
        Boolean crashFatal,
        String crashExceptionType,
        String crashFingerprint,
        String fingerprintVersion,
        String symbolicationStatus,
        CrashPayload crash) {

    public boolean isCrash() {
        return "crash".equals(eventType);
    }

    public boolean isAppStart() {
        return "app_start".equals(eventType);
    }
}
