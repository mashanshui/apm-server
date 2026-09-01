package com.shanshui.apmserver.domain;

import java.time.Instant;
import java.util.Map;

public record StoredEvent(
        java.util.UUID appId,
        String packageName,
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
        CrashPayload crash,
        JankPayload jank,
        JankAnalysis jankAnalysis,
        FrameSceneSummaryPayload frameSceneSummary,
        ForegroundSuspensionSummaryPayload foregroundSuspensionSummary) {

    /** 兼容现有 Crash 存储适配器和测试的构造签名。 */
    public StoredEvent(java.util.UUID appId,
                       String packageName,
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
        this(appId, packageName, eventId, eventType, occurredAt, receivedAt, schemaVersion,
                sessionId, anonymousDeviceId, appVersion, versionCode, buildId, environment,
                channel, osVersion, deviceModel, networkType, measurements, attributes,
                crashKind, crashFatal, crashExceptionType, crashFingerprint, fingerprintVersion,
                symbolicationStatus, crash, null, null, null, null);
    }

    public boolean isCrash() {
        return "crash".equals(eventType);
    }

    public boolean isAppStart() {
        return "app_start".equals(eventType);
    }

    public boolean isJank() {
        return "jank".equals(eventType);
    }

    public boolean isFrameSceneSummary() {
        return "frame_scene_summary".equals(eventType);
    }

    public boolean isForegroundSuspensionSummary() {
        return "foreground_suspension_summary".equals(eventType);
    }
}
