package com.shanshui.apmserver.crash.api;

import java.time.Instant;

public record CrashEventDetailResponse(
        java.util.UUID appId,
        String eventId,
        String packageName,
        Instant occurredAt,
        Instant receivedAt,
        String sessionId,
        String processId,
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
        /** 单份当前堆栈，不提供旧字段别名。 */ CrashStack stack,
        java.util.UUID symbolFileId,
        Integer symbolFileRevision,
        String symbolicationReason,
        /** 不变 Crash 类型。 */ String crashKind,
        /** 不变致命资格。 */ boolean fatal,
        /** temporary 或 resolved。 */ String groupingKind,
        /** 实际处理来源摘要。 */ String symbolFileSha256,
        /** 实际处理时间。 */ Instant processedAt) {
}
