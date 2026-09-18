package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryIngestCommand;
import com.shanshui.apmserver.memory.api.MemoryMetricsConfiguration;
import com.shanshui.apmserver.memory.api.MemorySamplePayload;
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
        if (!"memory_sample".equals(event.eventType())) {
            issues.add(issue("UNSUPPORTED_EVENT_TYPE", "内存领域仅支持 memory_sample 事件"));
        }
        validateTime(event.occurredAt(), issues);
        validateSample(event.memorySample(), issues);
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

    private void validateSample(MemorySamplePayload sample, List<ValidationIssue> issues) {
        if (sample == null) {
            issues.add(issue("MISSING_MEMORY_SAMPLE", "memorySample 载荷不能为空"));
            return;
        }
        if (sample.pssBytes() == null && sample.vssBytes() == null && sample.javaHeapUsedBytes() == null) {
            issues.add(issue("MISSING_MEMORY_METRIC", "至少需要提供一项内存指标"));
        }
        validateValue(sample.pssBytes(), "pssBytes", issues);
        validateValue(sample.vssBytes(), "vssBytes", issues);
        validateValue(sample.javaHeapUsedBytes(), "javaHeapUsedBytes", issues);
        require(sample.processName(), "processName", properties.getMaxProcessNameLength(), issues);
        if (sample.foreground() == null) {
            issues.add(issue("MISSING_FOREGROUND", "foreground 必须是布尔值"));
        }
        if (sample.scene() != null && sample.scene().isBlank()) {
            issues.add(issue("INVALID_SCENE", "scene(Activity 名称)不能为空字符串"));
        } else if (sample.scene() != null && sample.scene().length() > properties.getMaxSceneLength()) {
            issues.add(issue("FIELD_TOO_LONG", "scene(Activity 名称)超过长度上限"));
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
