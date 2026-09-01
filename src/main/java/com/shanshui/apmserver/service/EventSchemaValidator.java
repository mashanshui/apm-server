package com.shanshui.apmserver.service;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 对批次 JSON 执行资源 Schema 所要求的严格字段边界检查。
 * Jackson 3 默认可能兼容性地忽略未知字段，因此接收入口在绑定领域模型前显式执行本检查。
 */
@Service
public class EventSchemaValidator {

    private static final Set<String> BATCH_FIELDS = Set.of("requestId", "events");
    private static final Set<String> EVENT_FIELDS = Set.of(
            "schemaVersion", "eventId", "eventType", "occurredAt", "sessionId", "anonymousDeviceId",
            "packageName", "appVersion", "versionCode", "buildId", "environment", "channel", "osVersion",
            "deviceModel", "networkType", "measurements", "attributes", "crash", "jank",
            "frameSceneSummary", "foregroundSuspensionSummary");
    private static final Set<String> CRASH_FIELDS = Set.of("kind", "fatal", "throwableChain");
    private static final Set<String> THROWABLE_FIELDS = Set.of("type", "message", "frames");
    private static final Set<String> FRAME_FIELDS = Set.of("className", "methodName", "fileName", "lineNumber", "applicationFrame");
    private static final Set<String> FRAME_SCENE_FIELDS = Set.of("scene", "algorithmVersion", "activeDurationMs",
            "uiRefreshFrameCount", "refreshRateHz", "normalizedFps60", "frameDurationHistogram");
    private static final Set<String> SUSPENSION_FIELDS = Set.of("algorithmVersion", "foregroundDurationMs",
            "suspensionDurationMs", "suspensionCount", "thresholdMs");

    public void validateBatch(JsonNode root) {
        requireObject(root, "批次");
        rejectUnknown(root, BATCH_FIELDS, "批次");
        requireText(root, "requestId");
        JsonNode events = root.get("events");
        if (events == null || !events.isArray()) {
            throw new InvalidBatchException("events 必须是数组");
        }
        for (JsonNode event : events) {
            validateEvent(event);
        }
    }

    public void validateEvent(JsonNode event) {
        requireObject(event, "事件");
        rejectUnknown(event, EVENT_FIELDS, "事件");
        requireText(event, "eventId");
        requireText(event, "eventType");
        requireInteger(event, "schemaVersion");
        requireInteger(event, "occurredAt");
        requireText(event, "sessionId");
        requireText(event, "anonymousDeviceId");
        requirePresentText(event, "packageName");
        requireText(event, "appVersion");
        requireInteger(event, "versionCode");
        requireText(event, "buildId");
        requireText(event, "environment");
        requireText(event, "channel");
        requireText(event, "osVersion");
        requireText(event, "deviceModel");
        requireText(event, "networkType");
        validateOptionalObject(event, "measurements");
        validateOptionalObject(event, "attributes");
        validateCrash(event.get("crash"));
        validateFrameScene(event.get("frameSceneSummary"));
        validateSuspension(event.get("foregroundSuspensionSummary"));
    }

    private void validateCrash(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return;
        }
        requireObject(payload, "crash");
        rejectUnknown(payload, CRASH_FIELDS, "crash");
        requireText(payload, "kind");
        requireBoolean(payload, "fatal");
        JsonNode chain = payload.get("throwableChain");
        if (chain == null || chain.isNull()) {
            return;
        }
        if (!chain.isArray()) {
            throw new InvalidBatchException("throwableChain 必须是数组");
        }
        for (JsonNode throwable : chain) {
            requireObject(throwable, "throwable");
            rejectUnknown(throwable, THROWABLE_FIELDS, "throwable");
            requireText(throwable, "type");
            requireText(throwable, "message");
            JsonNode frames = throwable.get("frames");
            if (frames != null && !frames.isNull() && !frames.isArray()) {
                throw new InvalidBatchException("frames 必须是数组");
            }
            if (frames != null && frames.isArray()) {
                for (JsonNode frame : frames) {
                    requireObject(frame, "stack frame");
                    rejectUnknown(frame, FRAME_FIELDS, "stack frame");
                    requireText(frame, "className");
                    requireText(frame, "methodName");
                    requireText(frame, "fileName");
                    requireInteger(frame, "lineNumber");
                    requireBoolean(frame, "applicationFrame");
                }
            }
        }
    }

    private void validateFrameScene(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return;
        }
        requireObject(payload, "frameSceneSummary");
        rejectUnknown(payload, FRAME_SCENE_FIELDS, "frameSceneSummary");
        requireText(payload, "scene");
        requireText(payload, "algorithmVersion");
        requireInteger(payload, "activeDurationMs");
        requireInteger(payload, "uiRefreshFrameCount");
        requireNumber(payload, "refreshRateHz");
        requireNumber(payload, "normalizedFps60");
        validateOptionalObject(payload, "frameDurationHistogram");
        JsonNode histogram = payload.get("frameDurationHistogram");
        if (histogram != null && histogram.isObject()) {
            histogram.properties().forEach(entry -> requireIntegerValue(entry.getValue(),
                    "frameDurationHistogram." + entry.getKey()));
        }
    }

    private void validateSuspension(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return;
        }
        requireObject(payload, "foregroundSuspensionSummary");
        rejectUnknown(payload, SUSPENSION_FIELDS, "foregroundSuspensionSummary");
        requireText(payload, "algorithmVersion");
        requireInteger(payload, "foregroundDurationMs");
        requireInteger(payload, "suspensionDurationMs");
        requireInteger(payload, "suspensionCount");
        requireInteger(payload, "thresholdMs");
    }

    private void validateOptionalObject(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value != null && !value.isNull() && !value.isObject()) {
            throw new InvalidBatchException(name + " 必须是对象");
        }
    }

    private void requireObject(JsonNode value, String name) {
        if (value == null || !value.isObject()) {
            throw new InvalidBatchException(name + " 必须是对象");
        }
    }

    private void rejectUnknown(JsonNode object, Set<String> allowed, String name) {
        Set<String> unknown = new HashSet<>();
        object.properties().forEach(entry -> {
            String field = entry.getKey();
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        });
        if (!unknown.isEmpty()) {
            throw new InvalidBatchException(name + " 包含未知字段: " + unknown.iterator().next());
        }
    }

    private void requireText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value != null && !value.isNull() && !value.isString()) {
            throw new InvalidBatchException(name + " 必须是字符串");
        }
    }

    private void requirePresentText(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull() || !value.isString() || value.asText().isBlank()) {
            throw new InvalidBatchException(name + " 必须是非空字符串");
        }
    }

    private void requireInteger(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value != null && !value.isNull() && !value.isIntegralNumber()) {
            throw new InvalidBatchException(name + " 必须是整数");
        }
    }

    private void requireIntegerValue(JsonNode value, String name) {
        if (value != null && !value.isNull() && !value.isIntegralNumber()) {
            throw new InvalidBatchException(name + " 必须是整数");
        }
    }

    private void requireNumber(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value != null && !value.isNull() && !value.isNumber()) {
            throw new InvalidBatchException(name + " 必须是数字");
        }
    }

    private void requireBoolean(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value != null && !value.isNull() && !value.isBoolean()) {
            throw new InvalidBatchException(name + " 必须是布尔值");
        }
    }
}
