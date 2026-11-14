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
        /** 实际处理时间。 */ Instant processedAt,
        /** 诊断缺失时为 null，不影响主异常详情。 */ CrashDiagnosticsResponse diagnostics) {
    /** Native 主栈逐状态物理帧数量；其他格式不输出。 */
    @com.fasterxml.jackson.annotation.JsonProperty(value = "nativeFrameCounts", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public NativeFrameCounts nativeFrameCounts() {
        if (!"native_stack".equals(stack.format()) || !(stack.content() instanceof NativeStack nativeStack)) return null;
        var frames = nativeStack.backtrace().frames();
        return new NativeFrameCounts(frames.stream().filter(frame -> "symbolicated".equals(frame.resolution().status())).count(),
                frames.stream().filter(frame -> "pending".equals(frame.resolution().status())).count(),
                frames.stream().filter(frame -> "failed".equals(frame.resolution().status())).count(),
                frames.stream().filter(frame -> "not_applicable".equals(frame.resolution().status())).count());
    }

    /** 主栈计数只按原物理帧统计，不把内联项计为新帧。 */
    public record NativeFrameCounts(
            /** 已成功物理帧。 */ long symbolicated,
            /** 等待或暂时延期帧。 */ long pending,
            /** 确定性失败帧。 */ long failed,
            /** 不可处理帧。 */ long notApplicable) { }
}
