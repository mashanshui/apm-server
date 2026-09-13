package com.shanshui.apmserver.memory.internal.domain;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 一份 SDK 报告事实；原始 report 保留用于审计，服务端不解析 HPROF。 */
public record MemoryLeakReport(UUID appId, UUID eventId, Instant occurredAt, Instant receivedAt,
                               String packageName, String appVersion, long versionCode,
                               String anonymousDeviceId, String processName, String sessionId,
                               String buildId, String environment, String channel,
                               String deviceModel, String scene, String manufacturer,
                               Integer sdkInt, String dumpReason, JsonNode report,
                               List<MemoryLeakPath> paths, String payloadHash,
                               String attachmentDigest, String attachmentPath,
                               long attachmentBytes) {
}
