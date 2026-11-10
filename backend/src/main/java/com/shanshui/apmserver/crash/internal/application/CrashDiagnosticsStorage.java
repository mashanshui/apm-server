package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashDiagnostics;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.crash.api.NativeAuxiliaryStacks;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 分离不可变上下文、辅助当前栈和不含接收时间的确定摘要。 */
final class CrashDiagnosticsStorage {
    /** 当前 JSON 配置。 */
    private final ObjectMapper mapper;

    /** 注入接收链路使用的 JSON 编码器。 */
    CrashDiagnosticsStorage(ObjectMapper mapper) { this.mapper = mapper; }

    /** 将已脱敏事件拆成事实和初始状态，重试时编码顺序保持稳定。 */
    Encoded encode(CrashIngestCommand event) {
        CrashDiagnostics value = event.crash().diagnostics();
        String diagnostics = "";
        String stacks = "";
        if (value != null) {
            var threads = value.otherThreads();
            CrashDiagnostics.OtherThreads metadata = null;
            List<CrashThreadStack> threadStacks = List.of();
            if (threads != null) {
                // 事实只保留线程身份，原始帧只存在于当前状态。
                var threadMetadata = threads.threads().stream().map(thread -> new CrashDiagnostics.OtherThread(
                        thread.javaId(), thread.name(), thread.state(), thread.priority(), thread.daemon(),
                        thread.mainThread(), thread.truncated(), null, thread.systemTid(), thread.relationToCrash())).toList();
                metadata = new CrashDiagnostics.OtherThreads(threads.status(), threads.reason(), threads.truncated(),
                        threads.capturedAt(), threads.observedCount(), threads.omittedCount(), threadMetadata);
                threadStacks = threads.threads().stream().map(thread -> new CrashThreadStack(
                        thread.javaId(), thread.frames().isEmpty() ? "not_applicable" : "pending",
                        "native".equals(event.crash().kind()) && !thread.frames().isEmpty() ? "native_symbolication_deferred" : null,
                        new CrashThreadStack.Stack("raw_frames", thread.frames().stream().map(this::frameFields).toList()),
                        null, null, null, null)).toList();
            }
            var nativeThreads = value.nativeThreads();
            CrashDiagnostics.NativeThreads nativeMetadata = null;
            if (nativeThreads != null) {
                // 事实只保存 Native 线程身份，实际回溯与主栈使用同一状态版本。
                nativeMetadata = new CrashDiagnostics.NativeThreads(nativeThreads.status(), nativeThreads.reason(),
                        nativeThreads.truncated(), nativeThreads.capturedAt(), nativeThreads.observedCount(),
                        nativeThreads.omittedCount(), nativeThreads.threads().stream().map(thread ->
                        new CrashDiagnostics.NativeThread(thread.systemTid(), thread.name(), thread.mainThread(), null)).toList());
            }
            if ("native".equals(event.crash().kind()) && (threads != null || nativeThreads != null)) {
                var nativeStacks = nativeThreads == null ? List.<NativeAuxiliaryStacks.NativeThreadStack>of()
                        : nativeThreads.threads().stream().map(thread -> new NativeAuxiliaryStacks.NativeThreadStack(
                        thread.systemTid(), thread.backtrace())).toList();
                stacks = mapper.writeValueAsString(new NativeAuxiliaryStacks(threadStacks, nativeStacks));
            } else if (threads != null) {
                stacks = mapper.writeValueAsString(threadStacks);
            }
            diagnostics = mapper.writeValueAsString(new CrashDiagnostics(value.coreTruncation(), value.crashedThread(),
                    metadata, value.logcat(), value.memory(), value.openFiles(), value.process(), value.device(), nativeMetadata));
        }
        // 将进程、会话等不可变身份也纳入摘要，防止状态单侧写入后用另一进程身份补事实。
        Map<String, Object> frozen = new LinkedHashMap<>();
        frozen.put("schemaVersion", event.schemaVersion());
        frozen.put("eventId", event.eventId());
        frozen.put("eventType", event.eventType());
        frozen.put("occurredAt", event.occurredAt());
        frozen.put("sessionId", event.sessionId());
        frozen.put("processId", event.processId());
        frozen.put("anonymousDeviceId", event.anonymousDeviceId());
        frozen.put("packageName", event.packageName());
        frozen.put("appVersion", event.appVersion());
        frozen.put("versionCode", event.versionCode());
        frozen.put("buildId", event.buildId());
        frozen.put("environment", event.environment());
        frozen.put("channel", event.channel());
        frozen.put("osVersion", event.osVersion());
        frozen.put("deviceModel", event.deviceModel());
        frozen.put("networkType", event.networkType());
        frozen.put("measurements", sorted(event.measurements()));
        frozen.put("attributes", sorted(event.attributes()));
        frozen.put("crash", event.crash());
        return new Encoded(diagnostics, stacks, sha256(mapper.writeValueAsBytes(frozen)));
    }

    /** 对可选属性按键排序，避免 Map 迭代顺序影响重试摘要。 */
    private Map<String, Object> sorted(Map<String, Object> value) {
        return value == null ? Map.of() : new TreeMap<>(value);
    }

    /** 与客户端一样省略帧中未提供的可选字段，避免初始辅助栈无谓扩张。 */
    private Map<String, Object> frameFields(com.shanshui.apmserver.telemetry.api.StackFrame frame) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("className", frame.className());
        fields.put("methodName", frame.methodName());
        if (frame.fileName() != null) fields.put("fileName", frame.fileName());
        if (frame.lineNumber() != null) fields.put("lineNumber", frame.lineNumber());
        if (frame.applicationFrame() != null) fields.put("applicationFrame", frame.applicationFrame());
        return fields;
    }

    /** 摘要只用于内容一致性，不记录原始事件。 */
    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("JVM 必须支持 SHA-256", ex); }
    }

    /** 三份确定的持久化材料。 */
    record Encoded(
            /** 不含辅助帧的上下文 JSON。 */ String diagnosticsPayload,
            /** 其他线程当前栈 JSON。 */ String threadStacksPayload,
            /** 脱敏核心与现场的稳定摘要。 */ String captureSha256) { }
}
