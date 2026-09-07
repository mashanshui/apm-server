package com.shanshui.apmserver.telemetry.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 多种 APM 信号共同使用且不包含信号专属载荷的事件元数据。 */
public record EventMetadata(
        UUID appId,
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
        Map<String, Object> attributes) {

    public EventMetadata {
        measurements = measurements == null ? Map.of() : Map.copyOf(measurements);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
