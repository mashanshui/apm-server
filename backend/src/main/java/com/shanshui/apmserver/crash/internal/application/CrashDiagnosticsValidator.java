package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashDiagnostics;
import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 校验不可信客户端提交的诊断资源边界，不在接收时静默裁剪。 */
final class CrashDiagnosticsValidator {
    /** JavaScript 可无损表达的非负整数上限。 */
    private static final long SAFE_INTEGER = 9_007_199_254_740_991L;
    /** 允许的采集状态。 */
    private static final Set<String> STATUSES = Set.of("ok", "partial", "unavailable", "skipped");
    /** 不包含异常原文的安全原因类别。 */
    private static final Set<String> REASONS = Set.of("permission_denied", "unsupported", "timeout", "io_error",
            "memory_pressure", "interrupted", "size_limit", "count_limit");
    /** Java 线程状态。 */
    private static final Set<String> THREAD_STATES = Set.of("NEW", "RUNNABLE", "BLOCKED", "WAITING",
            "TIMED_WAITING", "TERMINATED");
    /** 与服务端接收一致的 JSON 序列化器。 */
    private final ObjectMapper mapper;

    /** 使用同一 JSON 编码计算诊断分区上限。 */
    CrashDiagnosticsValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 校验各分区结构、关联计数和实际编码字节。 */
    void validate(CrashDiagnostics diagnostics, List<ValidationIssue> issues) {
        if (diagnostics == null) return;
        var core = diagnostics.coreTruncation();
        if (core != null && (core.truncated() == null || (Boolean.TRUE.equals(core.truncated()) && !"size_limit".equals(core.reason()))
                || (Boolean.FALSE.equals(core.truncated()) && core.reason() != null))) {
            invalid(issues, "coreTruncation 不合法");
        }
        Set<String> threadIds = new HashSet<>();
        var crashed = diagnostics.crashedThread();
        if (crashed != null) {
            partition(crashed.status(), crashed.reason(), crashed.truncated(), crashed.capturedAt(), issues);
            if (crashed.javaId() != null && !threadId(crashed.javaId(), threadIds)) invalid(issues, "崩溃线程 Java ID 不合法或重复");
            if (("ok".equals(crashed.status()) || "partial".equals(crashed.status()))
                    && (crashed.javaId() == null || crashed.name() == null || crashed.state() == null)) {
                invalid(issues, "已采集崩溃线程缺少身份");
            }
            text(crashed.name(), 512, issues);
            state(crashed.state(), issues);
            priority(crashed.priority(), issues);
            if (crashed.systemTid() != null && crashed.systemTid() <= 0) invalid(issues, "系统 TID 不合法");
        }
        var threads = diagnostics.otherThreads();
        if (threads != null) {
            partition(threads.status(), threads.reason(), threads.truncated(), threads.capturedAt(), issues);
            if (threads.threads() == null || threads.threads().size() > 64) {
                invalid(issues, "其他线程数量超过上限或缺失");
            } else {
                for (var thread : threads.threads()) validateThread(thread, threadIds, issues);
                if (noContent(threads.status()) && !threads.threads().isEmpty()) invalid(issues, "未采集线程不能携带堆栈");
                if (threads.observedCount() != null && threads.observedCount() < threads.threads().size()) {
                    invalid(issues, "其他线程观测数量小于已保存数量");
                }
            }
            nonNegative(threads.observedCount(), issues);
            nonNegative(threads.omittedCount(), issues);
            if (threads.observedCount() != null && threads.omittedCount() != null && threads.threads() != null
                    && (long) threads.omittedCount() + threads.threads().size() > threads.observedCount()) {
                invalid(issues, "其他线程数量与遗漏数不一致");
            }
            limit(threads, 48 * 1024, "其他线程", issues);
            if (threads.threads() != null && threads.threads().size() <= 64
                    && threads.threads().stream().allMatch(thread -> thread != null && thread.javaId() != null
                    && thread.javaId().matches("[1-9][0-9]{0,18}") && thread.frames() != null)) {
                List<CrashThreadStack> initialStacks = threads.threads().stream().map(thread -> new CrashThreadStack(
                        thread.javaId(), thread.frames().isEmpty() ? "not_applicable" : "pending", null,
                        new CrashThreadStack.Stack("raw_frames", thread.frames()), null, null, null, null)).toList();
                limit(initialStacks, 48 * 1024, "原始线程栈", issues);
            }
        }
        var logcat = diagnostics.logcat();
        if (logcat != null) {
            partition(logcat.status(), logcat.reason(), logcat.truncated(), logcat.capturedAt(), issues);
            logBuffer(logcat.main(), 200, issues);
            logBuffer(logcat.system(), 50, issues);
            logBuffer(logcat.events(), 50, issues);
            limit(logcat, 48 * 1024, "日志", issues);
        }
        var memory = diagnostics.memory();
        if (memory != null) {
            partition(memory.status(), memory.reason(), memory.truncated(), memory.capturedAt(), issues);
            for (Long value : new Long[]{memory.javaHeapUsedBytes(), memory.javaHeapMaxBytes(), memory.javaHeapPssBytes(),
                    memory.nativeHeapPssBytes(), memory.nativeHeapAllocatedBytes(), memory.totalPssBytes(),
                    memory.rssBytes(), memory.vssBytes(), memory.swapBytes()}) safeInteger(value, issues);
            text(memory.meminfo(), 16384, issues);
            text(memory.statusText(), 16384, issues);
            text(memory.limits(), 16384, issues);
            limit(memory, 16 * 1024, "内存", issues);
        }
        var files = diagnostics.openFiles();
        if (files != null) {
            partition(files.status(), files.reason(), files.truncated(), files.capturedAt(), issues);
            nonNegative(files.count(), issues);
            if (files.entries() == null || files.entries().size() > 128) {
                invalid(issues, "文件描述符明细缺失或超过上限");
            } else {
                if (noContent(files.status()) && !files.entries().isEmpty()) invalid(issues, "未采集 FD 不能携带明细");
                Set<Integer> fds = new HashSet<>();
                for (var file : files.entries()) {
                    if (file == null || file.fd() == null || file.fd() < 0 || !fds.add(file.fd())) {
                        invalid(issues, "文件描述符编号不合法或重复");
                    } else text(file.target(), 2048, issues);
                }
                if (files.count() != null && files.count() < files.entries().size()) invalid(issues, "FD 总数小于已保存数量");
            }
            limit(files, 12 * 1024, "文件描述符", issues);
        }
        var process = diagnostics.process();
        if (process != null) {
            partition(process.status(), process.reason(), process.truncated(), process.capturedAt(), issues);
            if (process.pid() != null && process.pid() <= 0) invalid(issues, "进程 PID 不合法");
            text(process.name(), 512, issues);
            safeInteger(process.startedAt(), issues);
            safeInteger(process.sdkInitializedAt(), issues);
        }
        var device = diagnostics.device();
        if (device != null) {
            partition(device.status(), device.reason(), device.truncated(), device.capturedAt(), issues);
            if (device.apiLevel() != null && (device.apiLevel() < 1 || device.apiLevel() > 10000)) invalid(issues, "API level 不合法");
            if (device.abis() == null || device.abis().size() > 16) invalid(issues, "ABI 列表缺失或超限");
            else for (String abi : device.abis()) { if (abi == null) invalid(issues, "ABI 不能为空"); else text(abi, 128, issues); }
            text(device.manufacturer(), 256, issues);
            text(device.brand(), 256, issues);
            text(device.buildFingerprint(), 1024, issues);
        }
        if ((long) encodedBytes(crashed) + encodedBytes(process) + encodedBytes(device) > 4 * 1024) {
            invalid(issues, "线程、进程和设备元数据超过大小上限");
        }
        limit(diagnostics, 128 * 1024, "诊断", issues);
    }

    /** 校验一个其他线程的身份、状态、帧数及帧字段。 */
    private void validateThread(CrashDiagnostics.OtherThread thread, Set<String> threadIds, List<ValidationIssue> issues) {
        if (thread == null) { invalid(issues, "其他线程不能为空"); return; }
        if (!threadId(thread.javaId(), threadIds)) invalid(issues, "其他线程 Java ID 不合法或重复");
        if (thread.name() == null || thread.state() == null || thread.priority() == null
                || thread.daemon() == null || thread.mainThread() == null || thread.truncated() == null) {
            invalid(issues, "其他线程字段缺失");
        }
        text(thread.name(), 512, issues);
        state(thread.state(), issues);
        priority(thread.priority(), issues);
        if (thread.frames() == null || thread.frames().size() > 32) {
            invalid(issues, "其他线程帧缺失或超限");
            return;
        }
        for (StackFrame frame : thread.frames()) {
            if (frame == null || frame.className() == null || frame.className().isBlank()
                    || frame.methodName() == null || frame.methodName().isBlank()) {
                invalid(issues, "其他线程帧缺少类或方法");
                continue;
            }
            text(frame.className(), 512, issues);
            text(frame.methodName(), 512, issues);
            text(frame.fileName(), 512, issues);
            if (frame.lineNumber() != null && (frame.lineNumber() < -1 || frame.lineNumber() > 1_000_000_000)) invalid(issues, "其他线程帧行号不合法");
        }
    }

    /** 校验单个日志缓冲区的行数和每行编码字节。 */
    private void logBuffer(CrashDiagnostics.LogcatBuffer buffer, int maxLines, List<ValidationIssue> issues) {
        if (buffer == null) return;
        partition(buffer.status(), buffer.reason(), buffer.truncated(), buffer.capturedAt(), issues);
        if (buffer.lines() == null || buffer.lines().size() > maxLines) {
            invalid(issues, "日志行数缺失或超限");
            return;
        }
        if (noContent(buffer.status()) && !buffer.lines().isEmpty()) invalid(issues, "未采集日志不能携带日志行");
        for (String line : buffer.lines()) {
            if (line == null || encodedBytes(line) > 2 * 1024) invalid(issues, "日志行为空或超过字节上限");
        }
    }

    /** 校验各分区共享状态及原采集时间。 */
    private void partition(String status, String reason, Boolean truncated, Long capturedAt, List<ValidationIssue> issues) {
        if (status == null || !STATUSES.contains(status) || truncated == null
                || (reason != null && !REASONS.contains(reason))
                || (!"ok".equals(status) && reason == null)
                || ("ok".equals(status) && reason != null)
                || (Boolean.TRUE.equals(truncated) && "ok".equals(status))) {
            invalid(issues, "诊断分区状态不合法");
        }
        safeInteger(capturedAt, issues);
    }

    /** 未尝试或不可用时不能伪造已采集的明细。 */
    private boolean noContent(String status) { return "unavailable".equals(status) || "skipped".equals(status); }

    /** 校验正十进制 Java ID，避免同名线程或崩溃线程被合并。 */
    private boolean threadId(String value, Set<String> ids) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0 && ids.add(value); }
        catch (NumberFormatException ex) { return false; }
    }

    /** 校验可选 Java 线程状态。 */
    private void state(String value, List<ValidationIssue> issues) {
        if (value != null && !THREAD_STATES.contains(value)) invalid(issues, "Java 线程状态不合法");
    }

    /** 校验可选 Java 线程优先级。 */
    private void priority(Integer value, List<ValidationIssue> issues) {
        if (value != null && (value < 1 || value > 10)) invalid(issues, "Java 线程优先级不合法");
    }

    /** 校验可选文本的字符上限。 */
    private void text(String value, int maximum, List<ValidationIssue> issues) {
        if (value != null && value.codePointCount(0, value.length()) > maximum) invalid(issues, "诊断文本超过长度上限");
    }

    /** 校验可选非负计数。 */
    private void nonNegative(Integer value, List<ValidationIssue> issues) {
        if (value != null && value < 0) invalid(issues, "诊断计数不能为负数");
    }

    /** 校验可无损传递到前端的非负整数。 */
    private void safeInteger(Long value, List<ValidationIssue> issues) {
        if (value != null && (value < 0 || value > SAFE_INTEGER)) invalid(issues, "诊断数值超出安全范围");
    }

    /** 校验经过 JSON 转义后的实际 UTF-8 大小。 */
    private void limit(Object value, int maxBytes, String label, List<ValidationIssue> issues) {
        if (encodedBytes(value) > maxBytes) invalid(issues, label + "超过编码大小上限");
    }

    /** 序列化失败时按超限处理，避免静默丢失诊断。 */
    private int encodedBytes(Object value) {
        if (value == null) return 0;
        try {
            // 客户端 Gson 省略对象中的 null；堆栈帧复用模型尚未统一声明该策略。
            JsonNode tree = mapper.valueToTree(value);
            removeNullProperties(tree);
            return mapper.writeValueAsBytes(tree).length;
        }
        catch (RuntimeException ex) { return Integer.MAX_VALUE; }
    }

    /** 按客户端编码省略对象空字段，数组中的 null 仍由结构校验拒绝。 */
    private void removeNullProperties(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> emptyFields = new ArrayList<>();
            object.properties().forEach(entry -> {
                if (entry.getValue().isNull()) emptyFields.add(entry.getKey());
                else removeNullProperties(entry.getValue());
            });
            emptyFields.forEach(object::remove);
        } else if (node.isArray()) {
            node.forEach(this::removeNullProperties);
        }
    }

    /** 只返回固定类别和安全说明，不回显客户端诊断原文。 */
    private void invalid(List<ValidationIssue> issues, String message) {
        issues.add(new ValidationIssue("INVALID_CRASH_DIAGNOSTICS", message));
    }
}
