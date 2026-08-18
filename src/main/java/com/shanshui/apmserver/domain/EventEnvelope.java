package com.shanshui.apmserver.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record EventEnvelope(
        Integer schemaVersion,
        String eventId,
        String eventType,
        Long occurredAt,
        String sessionId,
        String anonymousDeviceId,
        String appId,
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
        CrashPayload crash) {
}
