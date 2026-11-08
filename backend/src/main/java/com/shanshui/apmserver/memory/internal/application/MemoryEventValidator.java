package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryIngestCommand;
import com.shanshui.apmserver.memory.api.MemoryMetricsConfiguration;
import com.shanshui.apmserver.memory.api.MemoryPeakSummaryPayload;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import com.shanshui.apmserver.telemetry.api.ProcessIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 校验内存采样的公共字段、采样值范围及进程上下文。 */
@Service
public class MemoryEventValidator {

    private final MemoryMetricsConfiguration properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public MemoryEventValidator(MemoryMetricsConfiguration properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    public MemoryEventValidator(MemoryMetricsConfiguration properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 校验运行峰值公共信封及各项业务边界。 */
    public void validate(MemoryIngestCommand event) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (event == null) {
            throw new EventValidationException(List.of(issue("INVALID_EVENT", "事件不能为空")));
        }
        if (event.schemaVersion() == null || event.schemaVersion() != properties.getSupportedSchemaVersion()) {
            issues.add(issue("UNSUPPORTED_SCHEMA_VERSION", "不支持的事件 Schema 版本"));
        }
        require(event.eventId(), "eventId", 128, issues);
        require(event.eventType(), "eventType", 32, issues);
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
        if (!"memory_peak_summary".equals(event.eventType())) {
            issues.add(issue("UNSUPPORTED_EVENT_TYPE", "内存领域仅支持 memory_peak_summary 事件"));
        }
        validateTime(event.occurredAt(), issues);
        validateSample(event.memoryPeakSummary(), issues);
        if (event.memoryPeakSummary() != null && !java.util.Objects.equals(event.occurredAt(), event.memoryPeakSummary().lastSampledAt())) {
            issues.add(issue("INVALID_MEMORY_TIME", "occurredAt 必须等于 lastSampledAt"));
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

    /** 校验完整运行区间和独立峰值。 */
    private void validateSample(MemoryPeakSummaryPayload sample, List<ValidationIssue> issues) {
        if (sample == null) {
            issues.add(issue("MISSING_MEMORY_SAMPLE", "memoryPeakSummary 载荷不能为空"));
            return;
        }
        if (sample.pssBytes() == null && sample.vssBytes() == null && sample.javaHeapUsedBytes() == null) {
            issues.add(issue("MISSING_MEMORY_METRIC", "至少需要提供一项内存指标"));
        }
        validateValue(sample.pssBytes(), "pssBytes", issues);
        validateValue(sample.vssBytes(), "vssBytes", issues);
        validateValue(sample.javaHeapUsedBytes(), "javaHeapUsedBytes", issues);
        require(sample.processName(), "processName", properties.getMaxProcessNameLength(), issues);
        if (sample.collectionStartedAt() == null || sample.lastSampledAt() == null
                || sample.collectionStartedAt() < 0 || sample.lastSampledAt() < sample.collectionStartedAt()) {
            issues.add(issue("INVALID_MEMORY_TIME", "采集起止时间缺失或倒置"));
            return;
        }
        validatePeak(sample.pss(), sample, issues);
        validatePeak(sample.vss(), sample, issues);
        validatePeak(sample.javaHeap(), sample, issues);
    }

    /** 校验单项峰值自身的完整上下文，缺失指标不补值。 */
    private void validatePeak(com.shanshui.apmserver.memory.api.MemoryPeak peak, MemoryPeakSummaryPayload sample,
                              List<ValidationIssue> issues) {
        if (peak == null) return;
        if (peak.bytes() == null || peak.foreground() == null || peak.occurredAt() == null) {
            issues.add(issue("INVALID_MEMORY_PEAK", "峰值必须包含 bytes、occurredAt 和 foreground"));
            return;
        }
        if (peak.occurredAt() < sample.collectionStartedAt() || peak.occurredAt() > sample.lastSampledAt()) {
            issues.add(issue("INVALID_MEMORY_TIME", "峰值时间必须位于采集区间内"));
        }
        if (peak.scene() != null && (peak.scene().isBlank() || peak.scene().length() > properties.getMaxSceneLength())) {
            issues.add(issue("INVALID_SCENE", "scene(Activity 名称)为空或超过长度上限"));
        }
    }

    private void validateValue(Long value, String field, List<ValidationIssue> issues) {
        if (value == null) {
            return;
        }
        if (value < 0) {
            issues.add(issue("INVALID_MEMORY_VALUE", field + " 必须是非负整数"));
        } else if (value > properties.getMaxMemoryMetricBytes()) {
            issues.add(issue("MEMORY_VALUE_TOO_LARGE", field + " 超过安全整数上限"));
        }
    }

    /** 校验峰值专属历史窗口及公共未来容差。 */
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
        if (!occurredAt.isAfter(now.minus(Duration.ofDays(properties.getMaxMemoryPeakPastDays())))) {
            issues.add(issue("EVENT_TIME_TOO_OLD", "事件时间超出允许的历史窗口"));
        }
    }

    private void require(String value, String field, int maxLength, List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(issue("MISSING_" + field.toUpperCase().replace('.', '_'), field + " 不能为空"));
        } else if (value.length() > maxLength) {
            issues.add(issue("FIELD_TOO_LONG", field + " 超过长度上限"));
        }
    }

    /** 校验进程实例身份，内存采样也必须绑定到明确的进程生命周期。 */
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
