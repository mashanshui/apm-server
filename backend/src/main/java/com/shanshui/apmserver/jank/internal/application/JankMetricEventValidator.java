package com.shanshui.apmserver.jank.internal.application;

import com.shanshui.apmserver.jank.api.JankMetricsConfiguration;
import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.api.JankMetricIngestCommand;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import com.shanshui.apmserver.telemetry.api.ProcessIdentity;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 只校验帧指标和前台挂起指标，不处理 Crash 或卡顿个例。 */
@Service
public class JankMetricEventValidator {

    private final JankMetricsConfiguration properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public JankMetricEventValidator(JankMetricsConfiguration properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    JankMetricEventValidator(JankMetricsConfiguration properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void validate(JankMetricIngestCommand event) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (event == null) {
            throw new EventValidationException(List.of(issue("INVALID_EVENT", "事件不能为空")));
        }
        if (event.schemaVersion() == null || event.schemaVersion() != properties.getSupportedSchemaVersion()) {
            issues.add(issue("UNSUPPORTED_SCHEMA_VERSION", "不支持的事件 Schema 版本"));
        }
        require(event.eventId(), "eventId", 128, issues);
        require(event.sessionId(), "sessionId", 128, issues);
        requireProcessId(event.processId(), issues);
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
        validateTime(event.occurredAt(), issues);
        if ("frame_scene_summary".equals(event.eventType())) {
            if (!properties.isFrameMetricsEnabled()) {
                issues.add(issue("FRAME_METRICS_DISABLED", "场景帧指标接收开关已关闭"));
            }
            if (event.frameSceneSummary() == null || event.foregroundSuspensionSummary() != null) {
                issues.add(issue("INVALID_FRAME_PAYLOAD", "frame_scene_summary 事件必须且只能携带 frameSceneSummary 载荷"));
            } else {
                validateFrameScene(event.frameSceneSummary(), issues);
            }
        } else if ("foreground_suspension_summary".equals(event.eventType())) {
            if (!properties.isSuspensionMetricsEnabled()) {
                issues.add(issue("SUSPENSION_METRICS_DISABLED", "前台挂起指标接收开关已关闭"));
            }
            if (event.foregroundSuspensionSummary() == null || event.frameSceneSummary() != null) {
                issues.add(issue("INVALID_SUSPENSION_PAYLOAD", "foreground_suspension_summary 事件必须且只能携带 foregroundSuspensionSummary 载荷"));
            } else {
                validateSuspension(event.foregroundSuspensionSummary(), issues);
            }
        } else {
            issues.add(issue("UNSUPPORTED_EVENT_TYPE", "不支持的事件类型"));
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

    private void validateTime(Long epochMillis, List<ValidationIssue> issues) {
        if (epochMillis == null) {
            issues.add(issue("MISSING_OCCURRED_AT", "occurredAt 不能为空"));
            return;
        }
        Instant occurredAt;
        try {
            occurredAt = Instant.ofEpochMilli(epochMillis);
        } catch (RuntimeException ex) {
            issues.add(issue("INVALID_OCCURRED_AT", "occurredAt 不是有效时间"));
            return;
        }
        Instant now = Instant.now(clock);
        if (occurredAt.isAfter(now.plus(Duration.ofMinutes(properties.getMaxFutureSkewMinutes())))) {
            issues.add(issue("EVENT_TIME_IN_FUTURE", "事件时间超出允许的未来窗口"));
        }
        if (occurredAt.isBefore(now.minus(Duration.ofDays(properties.getMaxPastDays())))) {
            issues.add(issue("EVENT_TIME_TOO_OLD", "事件时间超出允许的历史窗口"));
        }
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
                || (payload.foregroundDurationMs() != null && payload.suspensionDurationMs() > payload.foregroundDurationMs())) {
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

    /** 校验进程实例身份，避免把数字 PID 或其他文本当作进程生命周期标识。 */
    private void requireProcessId(String value, List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(issue("MISSING_PROCESS_ID", "processId 不能为空"));
        } else if (!ProcessIdentity.isUuidV4(value)) {
            issues.add(issue("INVALID_PROCESS_ID", "processId 必须是 UUID v4"));
        }
    }

    private ValidationIssue issue(String code, String message) {
        return new ValidationIssue(code, message);
    }
}
