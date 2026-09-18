package com.shanshui.apmserver.crash.api;

import java.util.Map;

/** Ingest 模块传给 Crash 模块的不可变接收命令。 */
public record CrashIngestCommand(
        Integer schemaVersion,
        String eventId,
        String eventType,
        Long occurredAt,
        String sessionId,
        String processId,
        String anonymousDeviceId,
        String packageName,
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
