package com.shanshui.apmserver.ingest.internal.protocol;

import com.shanshui.apmserver.ingest.api.InvalidBatchException;
import com.shanshui.apmserver.telemetry.api.ProcessIdentity;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对批次 JSON 执行资源 Schema 所要求的严格字段边界检查。
 * Jackson 3 默认可能兼容性地忽略未知字段，因此接收入口在绑定领域模型前显式执行本检查。
 */
@Service
public class EventSchemaValidator {

    private static final Set<String> BATCH_FIELDS = Set.of("requestId", "events");
    private static final Set<String> EVENT_FIELDS = Set.of(
            "schemaVersion", "eventId", "eventType", "occurredAt", "sessionId", "processId", "anonymousDeviceId",
            "packageName", "appVersion", "versionCode", "buildId", "environment", "channel", "osVersion",
            "deviceModel", "networkType", "measurements", "attributes", "crash", "jank",
            "frameSceneSummary", "foregroundSuspensionSummary", "memoryPeakSummary");
    private static final Set<String> CRASH_FIELDS = Set.of("kind", "fatal", "throwableChain", "diagnostics", "nativeCrash");
    /** Native 数据仅接受明确字段，不能携带完整 tombstone 或内存转储。 */
    private static final Set<String> NATIVE_CRASH_FIELDS = Set.of("abi", "systemTid", "threadName", "mainThread", "signal", "registers", "modules", "backtrace", "javaStack");
    /** 信号和故障信息。 */
    private static final Set<String> NATIVE_SIGNAL_FIELDS = Set.of("number", "name", "code", "codeName", "faultAddress", "abortMessage");
    /** 寄存器采集结果。 */
    private static final Set<String> NATIVE_REGISTERS_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "values");
    /** 事件内有限模块字典。 */
    private static final Set<String> NATIVE_MODULE_FIELDS = Set.of("id", "name", "path", "abi", "elfBuildId");
    /** 原始帧。 */
    private static final Set<String> NATIVE_FRAME_FIELDS = Set.of("index", "moduleId", "relativePc", "functionName", "functionOffset");
    /** 主辅回溯和 Java 栈的通用分区字段。 */
    private static final Set<String> NATIVE_TRACE_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "frames");
    /** Native 线程集合。 */
    private static final Set<String> NATIVE_THREADS_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "observedCount", "omittedCount", "threads");
    /** 按系统 TID 区分的 Native 线程。 */
    private static final Set<String> NATIVE_THREAD_FIELDS = Set.of("systemTid", "name", "mainThread", "backtrace");
    private static final Set<String> THROWABLE_FIELDS = Set.of("type", "message", "frames");
    private static final Set<String> FRAME_FIELDS = Set.of("className", "methodName", "fileName", "lineNumber", "applicationFrame");
    /** 诊断各分区的严格字段，防止 Jackson 静默丢弃未知内容。 */
    private static final Set<String> DIAGNOSTICS_FIELDS = Set.of("coreTruncation", "crashedThread", "otherThreads", "nativeThreads", "logcat", "memory", "openFiles", "process", "device");
    private static final Set<String> CORE_TRUNCATION_FIELDS = Set.of("truncated", "reason");
    private static final Set<String> CRASHED_THREAD_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "javaId", "name", "state", "priority", "daemon", "mainThread", "systemTid");
    private static final Set<String> OTHER_THREADS_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "observedCount", "omittedCount", "threads");
    private static final Set<String> OTHER_THREAD_FIELDS = Set.of("javaId", "name", "state", "priority", "daemon", "mainThread", "truncated", "frames", "systemTid", "relationToCrash");
    private static final Set<String> LOGCAT_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "main", "system", "events");
    private static final Set<String> LOG_BUFFER_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "lines");
    private static final Set<String> CRASH_MEMORY_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "javaHeapUsedBytes", "javaHeapMaxBytes", "javaHeapPssBytes", "nativeHeapPssBytes", "nativeHeapAllocatedBytes", "totalPssBytes", "rssBytes", "vssBytes", "swapBytes", "meminfo", "statusText", "limits");
    private static final Set<String> OPEN_FILES_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "count", "entries");
    private static final Set<String> OPEN_FILE_FIELDS = Set.of("fd", "target");
    private static final Set<String> CRASH_PROCESS_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "pid", "name", "foreground", "startedAt", "sdkInitializedAt");
    private static final Set<String> CRASH_DEVICE_FIELDS = Set.of("status", "reason", "truncated", "capturedAt", "apiLevel", "abis", "manufacturer", "brand", "buildFingerprint");
    private static final Set<String> FRAME_SCENE_FIELDS = Set.of("scene", "algorithmVersion", "activeDurationMs",
            "uiRefreshFrameCount", "refreshRateHz", "normalizedFps60", "frameDurationHistogram");
    private static final Set<String> SUSPENSION_FIELDS = Set.of("algorithmVersion", "foregroundDurationMs",
            "suspensionDurationMs", "suspensionCount", "thresholdMs");
    /** 运行汇总与单项峰值各自使用严格字段白名单。 */
    private static final Set<String> MEMORY_FIELDS = Set.of("processName", "collectionStartedAt", "lastSampledAt", "pss", "vss", "javaHeap");
    /** 每项峰值不能借用其他指标的上下文。 */
    private static final Set<String> PEAK_FIELDS = Set.of("bytes", "occurredAt", "foreground", "scene");

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
        requireUuidV4(event, "processId");
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
        validateMemory(event.get("memoryPeakSummary"));
    }

    private void validateCrash(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return;
        }
        requireObject(payload, "crash");
        rejectUnknown(payload, CRASH_FIELDS, "crash");
        requireText(payload, "kind");
        requireBoolean(payload, "fatal");
        validateCrashDiagnostics(payload.get("diagnostics"));
        validateNativeCrash(payload.get("nativeCrash"));
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
                    validateStackFrame(frame);
                }
            }
        }
    }

    /** 校验诊断 JSON 的结构与字段类型；数量和语义由事件校验器逐事件处理。 */
    private void validateCrashDiagnostics(JsonNode diagnostics) {
        if (diagnostics == null || diagnostics.isNull()) return;
        requireObject(diagnostics, "diagnostics");
        rejectUnknown(diagnostics, DIAGNOSTICS_FIELDS, "diagnostics");
        JsonNode core = optionalObject(diagnostics, "coreTruncation");
        if (core != null) {
            rejectUnknown(core, CORE_TRUNCATION_FIELDS, "coreTruncation");
            requireBoolean(core, "truncated");
            requireText(core, "reason");
        }
        JsonNode crashed = optionalPartition(diagnostics, "crashedThread", CRASHED_THREAD_FIELDS);
        if (crashed != null) {
            threadFields(crashed);
            requireInteger(crashed, "systemTid");
        }
        JsonNode other = optionalPartition(diagnostics, "otherThreads", OTHER_THREADS_FIELDS);
        if (other != null) {
            requireInteger(other, "observedCount");
            requireInteger(other, "omittedCount");
            JsonNode threads = optionalArray(other, "threads");
            if (threads != null) for (JsonNode thread : threads) {
                requireObject(thread, "otherThread");
                rejectUnknown(thread, OTHER_THREAD_FIELDS, "otherThread");
                threadFields(thread);
                requireBoolean(thread, "truncated");
                requireInteger(thread, "systemTid");
                requireText(thread, "relationToCrash");
                JsonNode frames = optionalArray(thread, "frames");
                if (frames != null) for (JsonNode frame : frames) validateStackFrame(frame);
            }
        }
        JsonNode nativeThreads = optionalPartition(diagnostics, "nativeThreads", NATIVE_THREADS_FIELDS);
        if (nativeThreads != null) {
            requireInteger(nativeThreads, "observedCount");
            requireInteger(nativeThreads, "omittedCount");
            JsonNode threads = optionalArray(nativeThreads, "threads");
            if (threads != null) for (JsonNode thread : threads) {
                requireObject(thread, "nativeThread");
                rejectUnknown(thread, NATIVE_THREAD_FIELDS, "nativeThread");
                requireInteger(thread, "systemTid");
                requireText(thread, "name");
                requireBoolean(thread, "mainThread");
                validateNativeTrace(thread, "backtrace", false);
            }
        }
        JsonNode logcat = optionalPartition(diagnostics, "logcat", LOGCAT_FIELDS);
        if (logcat != null) for (String name : List.of("main", "system", "events")) {
            JsonNode buffer = optionalPartition(logcat, name, LOG_BUFFER_FIELDS);
            if (buffer != null) textArray(buffer, "lines");
        }
        JsonNode memory = optionalPartition(diagnostics, "memory", CRASH_MEMORY_FIELDS);
        if (memory != null) {
            for (String name : List.of("javaHeapUsedBytes", "javaHeapMaxBytes", "javaHeapPssBytes", "nativeHeapPssBytes",
                    "nativeHeapAllocatedBytes", "totalPssBytes", "rssBytes", "vssBytes", "swapBytes")) requireInteger(memory, name);
            for (String name : List.of("meminfo", "statusText", "limits")) requireText(memory, name);
        }
        JsonNode files = optionalPartition(diagnostics, "openFiles", OPEN_FILES_FIELDS);
        if (files != null) {
            requireInteger(files, "count");
            JsonNode entries = optionalArray(files, "entries");
            if (entries != null) for (JsonNode entry : entries) {
                requireObject(entry, "openFile");
                rejectUnknown(entry, OPEN_FILE_FIELDS, "openFile");
                requireInteger(entry, "fd");
                requireText(entry, "target");
            }
        }
        JsonNode process = optionalPartition(diagnostics, "process", CRASH_PROCESS_FIELDS);
        if (process != null) {
            for (String name : List.of("pid", "startedAt", "sdkInitializedAt")) requireInteger(process, name);
            requireText(process, "name");
            requireBoolean(process, "foreground");
        }
        JsonNode device = optionalPartition(diagnostics, "device", CRASH_DEVICE_FIELDS);
        if (device != null) {
            requireInteger(device, "apiLevel");
            textArray(device, "abis");
            for (String name : List.of("manufacturer", "brand", "buildFingerprint")) requireText(device, name);
        }
    }

    /** 在模型绑定前检查 Native 对象的类型和递归白名单。 */
    private void validateNativeCrash(JsonNode value) {
        if (value == null || value.isNull()) return;
        requireObject(value, "nativeCrash");
        rejectUnknown(value, NATIVE_CRASH_FIELDS, "nativeCrash");
        requireText(value, "abi");
        requireInteger(value, "systemTid");
        requireText(value, "threadName");
        requireBoolean(value, "mainThread");
        JsonNode signal = optionalObject(value, "signal");
        if (signal != null) {
            rejectUnknown(signal, NATIVE_SIGNAL_FIELDS, "signal");
            requireInteger(signal, "number");
            requireInteger(signal, "code");
            for (String field : List.of("name", "codeName", "faultAddress", "abortMessage")) requireText(signal, field);
        }
        JsonNode registers = optionalPartition(value, "registers", NATIVE_REGISTERS_FIELDS);
        if (registers != null) {
            JsonNode values = optionalObject(registers, "values");
            if (values != null) values.properties().forEach(entry -> {
                if (!entry.getValue().isString()) throw new InvalidBatchException("寄存器数值必须是字符串");
            });
        }
        JsonNode modules = optionalArray(value, "modules");
        if (modules != null) for (JsonNode module : modules) {
            requireObject(module, "nativeModule");
            rejectUnknown(module, NATIVE_MODULE_FIELDS, "nativeModule");
            for (String field : List.of("id", "name", "path", "abi", "elfBuildId")) requireText(module, field);
        }
        validateNativeTrace(value, "backtrace", false);
        validateNativeTrace(value, "javaStack", true);
    }

    /** 校验回溯分区字段，具体数量和语义由事件级校验处理。 */
    private void validateNativeTrace(JsonNode parent, String name, boolean javaFrames) {
        JsonNode trace = optionalPartition(parent, name, NATIVE_TRACE_FIELDS);
        if (trace == null) return;
        JsonNode frames = optionalArray(trace, "frames");
        if (frames == null) return;
        for (JsonNode frame : frames) {
            if (javaFrames) {
                validateStackFrame(frame);
            } else {
                requireObject(frame, "nativeFrame");
                rejectUnknown(frame, NATIVE_FRAME_FIELDS, "nativeFrame");
                requireInteger(frame, "index");
                for (String field : List.of("moduleId", "relativePc", "functionName", "functionOffset")) requireText(frame, field);
            }
        }
    }

    /** 校验线程身份字段的 JSON 类型，不混用 Java ID 与数值 TID。 */
    private void threadFields(JsonNode thread) {
        for (String name : List.of("javaId", "name", "state")) requireText(thread, name);
        requireInteger(thread, "priority");
        requireBoolean(thread, "daemon");
        requireBoolean(thread, "mainThread");
    }

    /** 校验主异常与辅助线程共用的帧对象。 */
    private void validateStackFrame(JsonNode frame) {
        requireObject(frame, "stack frame");
        rejectUnknown(frame, FRAME_FIELDS, "stack frame");
        requireText(frame, "className");
        requireText(frame, "methodName");
        requireText(frame, "fileName");
        requireInteger(frame, "lineNumber");
        requireBoolean(frame, "applicationFrame");
    }

    /** 分区共享状态字段，缺失与取值规则由事件校验器负责。 */
    private JsonNode optionalPartition(JsonNode parent, String name, Set<String> fields) {
        JsonNode value = optionalObject(parent, name);
        if (value == null) return null;
        rejectUnknown(value, fields, name);
        requireText(value, "status");
        requireText(value, "reason");
        requireBoolean(value, "truncated");
        requireInteger(value, "capturedAt");
        return value;
    }

    /** 获取可选对象；空值与字段缺失等价。 */
    private JsonNode optionalObject(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || value.isNull()) return null;
        requireObject(value, name);
        return value;
    }

    /** 获取可选数组，不接受其他 JSON 类型。 */
    private JsonNode optionalArray(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isArray()) throw new InvalidBatchException(name + " 必须是数组");
        return value;
    }

    /** 数组元素必须为文本，不能被反序列化器隐式转换。 */
    private void textArray(JsonNode parent, String name) {
        JsonNode values = optionalArray(parent, name);
        if (values != null) for (JsonNode value : values) {
            if (!value.isString()) throw new InvalidBatchException(name + " 元素必须是字符串");
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

    private void validateMemory(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return;
        }
        requireObject(payload, "memoryPeakSummary");
        rejectUnknown(payload, MEMORY_FIELDS, "memoryPeakSummary");
        requirePresentText(payload, "processName");
        requireInteger(payload, "collectionStartedAt");
        requireInteger(payload, "lastSampledAt");
        for (String metric : new String[]{"pss", "vss", "javaHeap"}) {
            JsonNode peak = payload.get(metric);
            if (peak == null || peak.isNull()) continue;
            requireObject(peak, metric);
            rejectUnknown(peak, PEAK_FIELDS, metric);
            requireInteger(peak, "bytes");
            requireInteger(peak, "occurredAt");
            requireBoolean(peak, "foreground");
            requireText(peak, "scene");
        }
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

    /** 校验必须使用标准连字符 UUID v4 的进程实例标识，不为缺失值生成替代身份。 */
    private void requireUuidV4(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull() || !value.isString() || !ProcessIdentity.isUuidV4(value.asText())) {
            throw new InvalidBatchException(name + " 必须是 UUID v4");
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
