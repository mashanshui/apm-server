package com.shanshui.apmserver.service;

import tools.jackson.databind.ObjectMapper;
import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.domain.CrashPayload;
import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.domain.FrameSceneSummaryPayload;
import com.shanshui.apmserver.domain.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.domain.StackFrame;
import com.shanshui.apmserver.domain.ThrowableNode;
import com.shanshui.apmserver.domain.ValidationIssue;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CrashEventValidator {

    private final IngestProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public CrashEventValidator(IngestProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    CrashEventValidator(IngestProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void validate(EventEnvelope event) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (event == null) {
            issues.add(issue("INVALID_EVENT", "事件不能为空"));
            throw new EventValidationException(issues);
        }
        if ("jank".equals(event.eventType())) {
            issues.add(issue("JANK_ARTIFACT_REQUIRED", "卡顿个例必须通过 /ingest/v1/stack-artifacts:parse 上传"));
            throw new EventValidationException(issues);
        }
        if (event.schemaVersion() == null || event.schemaVersion() != properties.getSupportedSchemaVersion()) {
            issues.add(issue("UNSUPPORTED_SCHEMA_VERSION", "不支持的事件 Schema 版本"));
        }
        require(event.eventId(), "eventId", 128, issues);
        require(event.eventType(), "eventType", 32, issues);
        require(event.sessionId(), "sessionId", 128, issues);
        require(event.anonymousDeviceId(), "anonymousDeviceId", 256, issues);
        require(event.packageName(), "packageName", 255, issues);
        require(event.appVersion(), "appVersion", 128, issues);
        require(event.buildId(), "buildId", 256, issues);
        require(event.environment(), "environment", 64, issues);
        require(event.channel(), "channel", 128, issues);
        require(event.osVersion(), "osVersion", 64, issues);
        require(event.deviceModel(), "deviceModel", 256, issues);
        if (event.versionCode() == null || event.versionCode() < 0) {
            issues.add(issue("MISSING_VERSION_CODE", "versionCode 必须是非负整数"));
        }
        Instant occurredAt = null;
        if (event.occurredAt() == null) {
            issues.add(issue("MISSING_OCCURRED_AT", "occurredAt 不能为空"));
        } else {
            try {
                occurredAt = Instant.ofEpochMilli(event.occurredAt());
            } catch (RuntimeException ex) {
                issues.add(issue("INVALID_OCCURRED_AT", "occurredAt 不是有效时间"));
            }
        }
        if (occurredAt != null) {
            Instant now = Instant.now(clock);
            if (occurredAt.isAfter(now.plus(Duration.ofMinutes(properties.getMaxFutureSkewMinutes())))) {
                issues.add(issue("EVENT_TIME_IN_FUTURE", "事件时间超出允许的未来窗口"));
            }
            if (occurredAt.isBefore(now.minus(Duration.ofDays(properties.getMaxPastDays())))) {
                issues.add(issue("EVENT_TIME_TOO_OLD", "事件时间超出允许的历史窗口"));
            }
        }
        int payloadCount = countPayloads(event);
        if (event.eventType() != null && !isSupportedEventType(event.eventType())) {
            issues.add(issue("UNSUPPORTED_EVENT_TYPE", "不支持的事件类型"));
        } else if (payloadCount > 1) {
            issues.add(issue("MULTIPLE_EVENT_PAYLOADS", "事件只能携带一个专用载荷"));
        } else if ("crash".equals(event.eventType())) {
            if (payloadCount != 1 || event.crash() == null) {
                issues.add(issue("INVALID_CRASH_PAYLOAD", "crash 事件必须且只能携带 crash 载荷"));
            } else {
                validateCrash(event.crash(), issues);
            }
        } else if ("app_start".equals(event.eventType())) {
            if (payloadCount != 0) {
                issues.add(issue("INVALID_EVENT_PAYLOAD", "app_start 不应携带专用载荷"));
            }
        } else if ("frame_scene_summary".equals(event.eventType())) {
            if (!properties.isFrameMetricsEnabled()) {
                issues.add(issue("FRAME_METRICS_DISABLED", "场景帧指标接收开关已关闭"));
            }
            if (payloadCount != 1 || event.frameSceneSummary() == null) {
                issues.add(issue("INVALID_FRAME_PAYLOAD", "frame_scene_summary 事件必须且只能携带 frameSceneSummary 载荷"));
            } else {
                validateFrameScene(event.frameSceneSummary(), issues);
            }
        } else if ("foreground_suspension_summary".equals(event.eventType())) {
            if (!properties.isSuspensionMetricsEnabled()) {
                issues.add(issue("SUSPENSION_METRICS_DISABLED", "前台挂起指标接收开关已关闭"));
            }
            if (payloadCount != 1 || event.foregroundSuspensionSummary() == null) {
                issues.add(issue("INVALID_SUSPENSION_PAYLOAD", "foreground_suspension_summary 事件必须且只能携带 foregroundSuspensionSummary 载荷"));
            } else {
                validateSuspension(event.foregroundSuspensionSummary(), issues);
            }
        }
        try {
            if (objectMapper.writeValueAsBytes(event).length > properties.getMaxEventBytes()) {
                issues.add(issue("EVENT_TOO_LARGE", "单事件超过大小上限"));
            }
        } catch (RuntimeException ex) {
            issues.add(issue("INVALID_EVENT", "事件无法序列化校验"));
        }
        if (!issues.isEmpty()) {
            throw new EventValidationException(issues);
        }
    }

    private void validateCrash(CrashPayload crash, List<ValidationIssue> issues) {
        if (crash == null) {
            issues.add(issue("MISSING_CRASH_PAYLOAD", "crash 载荷不能为空"));
            return;
        }
        if (!"jvm".equals(crash.kind())) {
            issues.add(issue("UNSUPPORTED_CRASH_KIND", "首期仅支持 crash.kind=jvm"));
        }
        if (!Boolean.TRUE.equals(crash.fatal())) {
            issues.add(issue("NON_FATAL_CRASH", "首期仅支持 fatal JVM Crash"));
        }
        if (crash.throwableChain() == null || crash.throwableChain().isEmpty()) {
            issues.add(issue("MISSING_THROWABLE_CHAIN", "throwableChain 不能为空"));
            return;
        }
        if (crash.throwableChain().size() > properties.getMaxThrowableChain()) {
            issues.add(issue("TOO_MANY_THROWABLES", "异常链数量超过上限"));
        }
        int frameCount = 0;
        for (ThrowableNode node : crash.throwableChain()) {
            if (node == null) {
                issues.add(issue("INVALID_THROWABLE", "异常链节点不能为空"));
                continue;
            }
            require(node.type(), "throwable.type", 512, issues);
            if (node.message() != null && node.message().length() > properties.getMaxMessageLength()) {
                issues.add(issue("MESSAGE_TOO_LONG", "异常消息超过长度上限"));
            }
            if (node.frames() == null || node.frames().isEmpty()) {
                issues.add(issue("MISSING_STACK_FRAMES", "异常节点必须包含堆栈帧"));
                continue;
            }
            frameCount += node.frames().size();
            for (StackFrame frame : node.frames()) {
                if (frame == null) {
                    issues.add(issue("INVALID_STACK_FRAME", "堆栈帧不能为空"));
                    continue;
                }
                require(frame.className(), "stack.className", 512, issues);
                require(frame.methodName(), "stack.methodName", 512, issues);
                if (frame.fileName() != null && frame.fileName().length() > 512) {
                    issues.add(issue("STACK_FIELD_TOO_LONG", "堆栈文件名超过长度上限"));
                }
                if (frame.lineNumber() != null && (frame.lineNumber() < -1 || frame.lineNumber() > 1_000_000_000)) {
                    issues.add(issue("INVALID_LINE_NUMBER", "堆栈行号超出范围"));
                }
            }
        }
        if (frameCount == 0) {
            issues.add(issue("MISSING_STACK_FRAMES", "Crash 至少需要一个堆栈帧"));
        }
        if (frameCount > properties.getMaxStackFrames()) {
            issues.add(issue("TOO_MANY_STACK_FRAMES", "堆栈帧数量超过上限"));
        }
    }

    private boolean isSupportedEventType(String eventType) {
        return "crash".equals(eventType)
                || "app_start".equals(eventType)
                || "jank".equals(eventType)
                || "frame_scene_summary".equals(eventType)
                || "foreground_suspension_summary".equals(eventType);
    }

    private int countPayloads(EventEnvelope event) {
        int count = 0;
        if (event.crash() != null) {
            count++;
        }
        if (event.jank() != null) {
            count++;
        }
        if (event.frameSceneSummary() != null) {
            count++;
        }
        if (event.foregroundSuspensionSummary() != null) {
            count++;
        }
        return count;
    }

    private void validateFrameScene(FrameSceneSummaryPayload payload, List<ValidationIssue> issues) {
        require(payload.scene(), "frameSceneSummary.scene", properties.getMaxSceneLength(), issues);
        if (payload.algorithmVersion() == null || payload.algorithmVersion().isBlank()) {
            issues.add(issue("MISSING_FPS_ALGORITHM_VERSION", "frameSceneSummary.algorithmVersion 不能为空"));
        } else if (!properties.getSupportedFpsAlgorithmVersions().contains(payload.algorithmVersion())) {
            issues.add(issue("UNSUPPORTED_FPS_ALGORITHM_VERSION", "不支持的 FPS 算法版本"));
        }
        if (payload.activeDurationMs() == null || payload.activeDurationMs() <= 0) {
            issues.add(issue("INVALID_ACTIVE_DURATION", "activeDurationMs 必须为正数"));
        }
        if (payload.uiRefreshFrameCount() == null || payload.uiRefreshFrameCount() <= 0) {
            issues.add(issue("NO_UI_REFRESH_FRAMES", "统计区间必须包含真实 UI 刷新帧"));
        }
        if (payload.refreshRateHz() == null || payload.refreshRateHz() <= 0 || payload.refreshRateHz() > 1_000) {
            issues.add(issue("INVALID_REFRESH_RATE", "refreshRateHz 超出范围"));
        }
        if (payload.normalizedFps60() == null || payload.normalizedFps60() < 0 || payload.normalizedFps60() > 1_000) {
            issues.add(issue("INVALID_NORMALIZED_FPS", "normalizedFps60 超出范围"));
        }
        if (payload.frameDurationHistogram() != null
                && payload.frameDurationHistogram().size() > properties.getMaxFrameHistogramBuckets()) {
            issues.add(issue("TOO_MANY_FRAME_BUCKETS", "帧耗时分布桶数量超过上限"));
        }
        if (payload.frameDurationHistogram() != null) {
            payload.frameDurationHistogram().forEach((bucket, count) -> {
                if (bucket == null || !bucket.matches("[A-Za-z0-9_.-]{1,64}")) {
                    issues.add(issue("INVALID_FRAME_BUCKET", "帧耗时分布桶名称无效"));
                }
                if (count == null || count < 0) {
                    issues.add(issue("INVALID_FRAME_BUCKET_COUNT", "帧耗时分布桶计数必须为非负整数"));
                }
            });
        }
    }

    private void validateSuspension(ForegroundSuspensionSummaryPayload payload, List<ValidationIssue> issues) {
        if (payload.algorithmVersion() == null || payload.algorithmVersion().isBlank()) {
            issues.add(issue("MISSING_SUSPENSION_ALGORITHM_VERSION", "foregroundSuspensionSummary.algorithmVersion 不能为空"));
        } else if (!properties.getSupportedSuspensionAlgorithmVersions().contains(payload.algorithmVersion())) {
            issues.add(issue("UNSUPPORTED_SUSPENSION_ALGORITHM_VERSION", "不支持的挂起算法版本"));
        }
        if (payload.foregroundDurationMs() == null || payload.foregroundDurationMs() <= 0) {
            issues.add(issue("INVALID_FOREGROUND_DURATION", "foregroundDurationMs 必须为正数"));
        }
        if (payload.suspensionDurationMs() == null || payload.suspensionDurationMs() < 0
                || (payload.foregroundDurationMs() != null && payload.foregroundDurationMs() > 0
                && payload.suspensionDurationMs() > payload.foregroundDurationMs())) {
            issues.add(issue("INVALID_SUSPENSION_DURATION", "suspensionDurationMs 超出范围"));
        }
        if (payload.suspensionCount() == null || payload.suspensionCount() < 0) {
            issues.add(issue("INVALID_SUSPENSION_COUNT", "suspensionCount 必须为非负整数"));
        }
        if (payload.thresholdMs() == null || payload.thresholdMs() <= 0) {
            issues.add(issue("INVALID_SUSPENSION_THRESHOLD", "thresholdMs 必须为正数"));
        }
    }

    private void require(String value, String field, int maxLength, List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(issue("MISSING_" + field.toUpperCase().replace('.', '_'), field + " 不能为空"));
        } else if (value.length() > maxLength) {
            issues.add(issue("FIELD_TOO_LONG", field + " 超过长度上限"));
        }
    }

    private ValidationIssue issue(String code, String message) {
        return new ValidationIssue(code, message);
    }
}
