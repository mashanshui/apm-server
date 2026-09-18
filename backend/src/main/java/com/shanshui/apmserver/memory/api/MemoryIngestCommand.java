package com.shanshui.apmserver.memory.api;

import java.util.Map;

/** Ingest 模块传给内存领域边界的不可变接收命令。 */
public record MemoryIngestCommand(
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
        MemorySamplePayload memorySample) {
}
