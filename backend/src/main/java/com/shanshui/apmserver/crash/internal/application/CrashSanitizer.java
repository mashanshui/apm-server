package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashIngestConfiguration;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.api.CrashDiagnostics;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class CrashSanitizer {

    private static final int MAX_ATTRIBUTE_COUNT = 32;
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern UUID = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s]+(?:\\?[^\\s]*)?");
    private static final Pattern HOME_PATH = Pattern.compile("(?i)(?:[A-Z]:\\\\Users\\\\|/Users/|/home/)[^\\s:]+", Pattern.UNICODE_CASE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d -]{7,}\\d)(?!\\d)");
    /** 私有路径可能含空格或用户文件名，因此移除该行剩余部分。 */
    private static final Pattern PRIVATE_PATH = Pattern.compile("(?i)(?:/data/(?:user(?:_de)?/\\d+|data)/|/storage/emulated/\\d+/|/sdcard/|/mnt/user/\\d+/|/Users/|/home/)[^\\r\\n]*");
    /** 凭据请求头可能含多个 cookie 项。 */
    private static final Pattern CREDENTIAL_HEADER = Pattern.compile("(?i)\\b(?:authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]*");
    /** 独立 Bearer 值。 */
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");
    /** 其他常见凭据赋值形式。 */
    private static final Pattern SECRET = Pattern.compile("(?i)\\b(token|password|secret|api[-_]?key)\\s*[:=]\\s*[^\\s,;]+");

    private final CrashIngestConfiguration properties;

    public CrashSanitizer(CrashIngestConfiguration properties) {
        this.properties = properties;
    }

    public CrashIngestCommand sanitize(CrashIngestCommand event) {
        CrashPayload crash = event.crash() == null ? null : sanitizeCrash(event.crash());
        return new CrashIngestCommand(
                event.schemaVersion(),
                sanitizeIdentifier(event.eventId(), 128),
                sanitizeIdentifier(event.eventType(), 32),
                event.occurredAt(),
                sanitizeIdentifier(event.sessionId(), 128),
                event.processId(),
                hashDeviceId(event.anonymousDeviceId()),
                sanitizeIdentifier(event.packageName(), 255),
                sanitizeText(event.appVersion(), 128),
                event.versionCode(),
                sanitizeIdentifier(event.buildId(), 256),
                sanitizeIdentifier(event.environment(), 64),
                sanitizeIdentifier(event.channel(), 128),
                sanitizeIdentifier(event.osVersion(), 64),
                sanitizeText(event.deviceModel(), 256),
                sanitizeIdentifier(event.networkType(), 32),
                sanitizeMap(event.measurements(), false, true),
                sanitizeMap(event.attributes(), true, false),
                crash);
    }

    public CrashPayload sanitizeCrash(CrashPayload crash) {
        List<ThrowableNode> chain = crash.throwableChain().stream()
                .map(node -> new ThrowableNode(
                        sanitizeText(node.type(), 512),
                        sanitizeText(node.message(), properties.getMaxMessageLength()),
                        node.frames().stream().map(this::sanitizeFrame).toList()))
                .toList();
        return new CrashPayload(sanitizeIdentifier(crash.kind(), 32), crash.fatal(), chain,
                sanitizeDiagnostics(crash.diagnostics()));
    }

    /** 对不可信客户端提交的全部诊断文本再次脱敏，结构化数值和身份保持原值。 */
    public CrashDiagnostics sanitizeDiagnostics(CrashDiagnostics value) {
        if (value == null) return null;
        var crashed = value.crashedThread();
        var safeCrashed = crashed == null ? null : new CrashDiagnostics.CrashedThread(
                crashed.status(), crashed.reason(), crashed.truncated(), crashed.capturedAt(), crashed.javaId(),
                sanitizeText(crashed.name(), 512), crashed.state(), crashed.priority(), crashed.daemon(),
                crashed.mainThread(), crashed.systemTid());
        var threads = value.otherThreads();
        var safeThreads = threads == null ? null : new CrashDiagnostics.OtherThreads(
                threads.status(), threads.reason(), threads.truncated(), threads.capturedAt(),
                threads.observedCount(), threads.omittedCount(), threads.threads().stream().map(thread ->
                new CrashDiagnostics.OtherThread(thread.javaId(), sanitizeText(thread.name(), 512), thread.state(),
                        thread.priority(), thread.daemon(), thread.mainThread(), thread.truncated(),
                        thread.frames().stream().map(this::sanitizeDiagnosticFrame).toList())).toList());
        var logs = value.logcat();
        var safeLogs = logs == null ? null : new CrashDiagnostics.Logcat(logs.status(), logs.reason(), logs.truncated(),
                logs.capturedAt(), sanitizeBuffer(logs.main()), sanitizeBuffer(logs.system()), sanitizeBuffer(logs.events()));
        var memory = value.memory();
        var safeMemory = memory == null ? null : new CrashDiagnostics.Memory(memory.status(), memory.reason(),
                memory.truncated(), memory.capturedAt(), memory.javaHeapUsedBytes(), memory.javaHeapMaxBytes(),
                memory.javaHeapPssBytes(), memory.nativeHeapPssBytes(), memory.nativeHeapAllocatedBytes(),
                memory.totalPssBytes(), memory.rssBytes(), memory.vssBytes(), memory.swapBytes(),
                sanitizeText(memory.meminfo(), 16384), sanitizeText(memory.statusText(), 16384),
                sanitizeText(memory.limits(), 16384));
        var files = value.openFiles();
        var safeFiles = files == null ? null : new CrashDiagnostics.OpenFiles(files.status(), files.reason(),
                files.truncated(), files.capturedAt(), files.count(), files.entries().stream().map(file ->
                new CrashDiagnostics.OpenFile(file.fd(), safeFileTarget(file.target()))).toList());
        var process = value.process();
        var safeProcess = process == null ? null : new CrashDiagnostics.Process(process.status(), process.reason(),
                process.truncated(), process.capturedAt(), process.pid(), sanitizeText(process.name(), 512),
                process.foreground(), process.startedAt(), process.sdkInitializedAt());
        var device = value.device();
        var safeDevice = device == null ? null : new CrashDiagnostics.Device(device.status(), device.reason(),
                device.truncated(), device.capturedAt(), device.apiLevel(),
                device.abis().stream().map(abi -> sanitizeText(abi, 128)).toList(),
                sanitizeText(device.manufacturer(), 256), sanitizeText(device.brand(), 256),
                sanitizeText(device.buildFingerprint(), 1024));
        return new CrashDiagnostics(value.coreTruncation(), safeCrashed, safeThreads, safeLogs, safeMemory,
                safeFiles, safeProcess, safeDevice);
    }

    /** 清洗单个日志缓冲区，不改变采集状态和时间。 */
    private CrashDiagnostics.LogcatBuffer sanitizeBuffer(CrashDiagnostics.LogcatBuffer buffer) {
        return buffer == null ? null : new CrashDiagnostics.LogcatBuffer(buffer.status(), buffer.reason(),
                buffer.truncated(), buffer.capturedAt(), buffer.lines().stream().map(line -> sanitizeText(line, 2048)).toList());
    }

    /** FD 不保留路径、inode 或匿名对象名称，只保留安全资源类别。 */
    private String safeFileTarget(String target) {
        if (target == null) return null;
        if (target.equals("[socket]") || target.startsWith("socket:")) return "[socket]";
        if (target.equals("[pipe]") || target.startsWith("pipe:")) return "[pipe]";
        if (target.equals("[anonymous]") || target.startsWith("anon_inode:")) return "[anonymous]";
        if (target.equals("[device]") || target.startsWith("/dev/")) return "[device]";
        if (target.equals("[path]") || target.startsWith("/")) return "[path]";
        return "[other]";
    }

    public String sanitizeText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value
                .replace('\u0000', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "?");
        sanitized = PRIVATE_PATH.matcher(sanitized).replaceAll("[path]");
        sanitized = CREDENTIAL_HEADER.matcher(sanitized).replaceAll("[credential]");
        sanitized = BEARER.matcher(sanitized).replaceAll("Bearer [credential]");
        sanitized = SECRET.matcher(sanitized).replaceAll("$1=[redacted]");
        sanitized = HOME_PATH.matcher(sanitized).replaceAll("[path]");
        sanitized = URL.matcher(sanitized).replaceAll("[url]");
        sanitized = EMAIL.matcher(sanitized).replaceAll("[email]");
        sanitized = UUID.matcher(sanitized).replaceAll("[id]");
        sanitized = PHONE.matcher(sanitized).replaceAll("[phone]");
        return truncate(sanitized, maxLength);
    }

    /** 完整还原输出只脱敏，不用截断伪造成功；输出上限仍由 R8 有界收集器负责。 */
    public String sanitizeRetrace(String text) {
        return sanitizeText(text, Integer.MAX_VALUE);
    }

    public String hashDeviceId(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return sha256(properties.getDeviceHashSalt() + ":" + value.trim());
    }

    private StackFrame sanitizeFrame(StackFrame frame) {
        return new StackFrame(
                sanitizeText(frame.className(), 512),
                sanitizeText(frame.methodName(), 512),
                sanitizeText(frame.fileName(), 512),
                frame.lineNumber(),
                Boolean.TRUE.equals(frame.applicationFrame()));
    }

    /** 辅助帧保留未提供的 applicationFrame，避免脱敏隐式扩张诊断载荷。 */
    private StackFrame sanitizeDiagnosticFrame(StackFrame frame) {
        return new StackFrame(sanitizeText(frame.className(), 512), sanitizeText(frame.methodName(), 512),
                sanitizeText(frame.fileName(), 512), frame.lineNumber(), frame.applicationFrame());
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> values, boolean redact, boolean numericOnly) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.entrySet().stream().limit(MAX_ATTRIBUTE_COUNT).forEach(entry -> {
            if (entry.getKey() == null || !entry.getKey().matches("[A-Za-z0-9_.-]{1,64}")) {
                return;
            }
            Object value = entry.getValue();
            if (numericOnly && !(value instanceof Number)) {
                return;
            }
            if (value instanceof Number || value instanceof Boolean) {
                result.put(entry.getKey(), value);
            } else if (value != null) {
                result.put(entry.getKey(), redact
                        ? sanitizeText(String.valueOf(value), 512)
                        : truncate(String.valueOf(value), 512));
            }
        });
        return Map.copyOf(result);
    }

    public String sanitizeIdentifier(String value, int maxLength) {
        return value == null ? null : truncate(value.trim().replaceAll("[\\r\\n\\t]", "?"), maxLength);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 必须支持 SHA-256", ex);
        }
    }
}
