package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.telemetry.api.EventValidationException;

import tools.jackson.databind.ObjectMapper;
import com.shanshui.apmserver.crash.api.CrashIngestConfiguration;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.telemetry.api.ProcessIdentity;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CrashEventValidator {

    private final CrashIngestConfiguration properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public CrashEventValidator(CrashIngestConfiguration properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.systemUTC());
    }

    CrashEventValidator(CrashIngestConfiguration properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void validate(CrashIngestCommand event) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (event == null) {
            issues.add(issue("INVALID_EVENT", "事件不能为空"));
            throw new EventValidationException(issues);
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
                || "app_start".equals(eventType);
    }

    private int countPayloads(CrashIngestCommand event) {
        return event.crash() == null ? 0 : 1;
    }

    private void require(String value, String field, int maxLength, List<ValidationIssue> issues) {
        if (value == null || value.isBlank()) {
            issues.add(issue("MISSING_" + field.toUpperCase().replace('.', '_'), field + " 不能为空"));
        } else if (value.length() > maxLength) {
            issues.add(issue("FIELD_TOO_LONG", field + " 超过长度上限"));
        }
    }

    /** 校验进程实例身份，空值与非 UUID v4 值都必须作为永久错误拒绝。 */
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
