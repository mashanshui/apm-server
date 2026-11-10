package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashDiagnostics;
import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.telemetry.api.ValidationIssue;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 校验 Native 原始材料及其与共享诊断的关联。 */
final class NativeCrashValidator {
    /** 支持的 Android ABI。 */
    private static final Set<String> ABIS = Set.of("arm64-v8a", "armeabi-v7a", "x86", "x86_64");
    /** 采集状态。 */
    private static final Set<String> STATUSES = Set.of("ok", "partial", "unavailable", "skipped");
    /** 安全原因类别。 */
    private static final Set<String> REASONS = Set.of("permission_denied", "unsupported", "timeout", "io_error",
            "memory_pressure", "interrupted", "size_limit", "count_limit");
    /** JavaScript 可无损表达的非负整数上限。 */
    private static final long SAFE_INTEGER = 9_007_199_254_740_991L;
    /** 与接收器一致的 JSON 编码器。 */
    private final ObjectMapper mapper;

    /** 使用当前 JSON 编码计算线程总预算。 */
    NativeCrashValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 校验核心、主辅帧、模块引用和共享线程预算。 */
    void validate(NativeCrash value, CrashDiagnostics diagnostics, List<ValidationIssue> issues) {
        if (value == null) {
            invalid(issues, "nativeCrash 不能为空");
            return;
        }
        boolean validAbi = value.abi() != null && ABIS.contains(value.abi());
        if (!validAbi) invalid(issues, "Native ABI 不受支持");
        int maxDigits = "arm64-v8a".equals(value.abi()) || "x86_64".equals(value.abi()) ? 16 : 8;
        if (value.systemTid() == null || value.systemTid() <= 0) invalid(issues, "崩溃线程 TID 不合法");
        text(value.threadName(), 512, issues);
        var signal = value.signal();
        if (signal == null || signal.number() == null || signal.number() < 1 || signal.number() > 128
                || signal.name() == null || !signal.name().matches("SIG[A-Z0-9]{1,16}") || signal.code() == null) {
            invalid(issues, "Native 信号字段不合法");
        }
        if (signal != null) {
            text(signal.codeName(), 128, issues);
            text(signal.abortMessage(), 4096, issues);
            hex(signal.faultAddress(), maxDigits, issues);
        }
        var registers = value.registers();
        if (registers == null) {
            invalid(issues, "缺少寄存器采集状态");
        } else {
            partition(registers.status(), registers.reason(), registers.truncated(), registers.capturedAt(), issues);
            Map<String, String> values = registers.values();
            if (values == null || values.size() > 64 || (noContent(registers.status()) && !values.isEmpty())
                    || ("ok".equals(registers.status()) && values.isEmpty())) {
                invalid(issues, "寄存器集合缺失或不合法");
            } else {
                values.forEach((name, address) -> {
                    if (name == null || !name.matches("[a-z][a-z0-9_]{0,15}") || address == null) {
                        invalid(issues, "寄存器名称或数值不合法");
                    } else hex(address, maxDigits, issues);
                });
            }
        }
        Set<String> moduleIds = new HashSet<>();
        if (value.modules() == null || value.modules().size() > 128) {
            invalid(issues, "模块字典缺失或超限");
        } else {
            for (var module : value.modules()) {
                if (module == null || module.id() == null || !module.id().matches("[A-Za-z0-9_-]{1,64}")
                        || !moduleIds.add(module.id()) || module.name() == null || module.name().isBlank()
                        || !java.util.Objects.equals(value.abi(), module.abi())) {
                    invalid(issues, "模块身份不合法或重复");
                    continue;
                }
                text(module.name(), 512, issues);
                if (module.name().contains("/") || module.name().contains("\\")) invalid(issues, "模块名称不能包含路径");
                text(module.path(), 2048, issues);
                if (module.elfBuildId() != null && (!module.elfBuildId().matches("[0-9a-f]{2,128}")
                        || module.elfBuildId().length() % 2 != 0)) invalid(issues, "ELF Build ID 不合法");
            }
        }
        backtrace(value.backtrace(), 128, moduleIds, maxDigits, issues);
        if (diagnostics != null && diagnostics.crashedThread() != null
                && diagnostics.crashedThread().systemTid() != null
                && !diagnostics.crashedThread().systemTid().equals(value.systemTid())) {
            invalid(issues, "崩溃线程 TID 与 Native 核心不一致");
        }
        javaStack(value, diagnostics, issues);
        nativeThreads(value, diagnostics, moduleIds, maxDigits, issues);
    }

    /** 校验线程回溯状态、顺序、位宽及模块引用。 */
    private void backtrace(NativeCrash.Backtrace trace, int maximum, Set<String> moduleIds, int maxDigits,
                           List<ValidationIssue> issues) {
        if (trace == null) {
            invalid(issues, "缺少回溯采集状态");
            return;
        }
        partition(trace.status(), trace.reason(), trace.truncated(), trace.capturedAt(), issues);
        if (trace.frames() == null || trace.frames().size() > maximum) {
            invalid(issues, "Native 回溯帧缺失或超限");
            return;
        }
        if (noContent(trace.status()) && !trace.frames().isEmpty()) invalid(issues, "未采集回溯不能携带帧");
        if ("ok".equals(trace.status()) && trace.frames().isEmpty()) invalid(issues, "成功回溯不能缺少帧");
        for (int index = 0; index < trace.frames().size(); index++) {
            var frame = trace.frames().get(index);
            if (frame == null || frame.index() == null || frame.index() != index) {
                invalid(issues, "Native 帧序号不合法");
                continue;
            }
            if (frame.moduleId() != null && !moduleIds.contains(frame.moduleId())) invalid(issues, "Native 帧引用未知模块");
            hex(frame.relativePc(), maxDigits, issues);
            hex(frame.functionOffset(), maxDigits, issues);
            text(frame.functionName(), 2048, issues);
        }
    }

    /** 不把无法可靠关联的 Java 线程当作崩溃线程。 */
    private void javaStack(NativeCrash value, CrashDiagnostics diagnostics, List<ValidationIssue> issues) {
        var stack = value.javaStack();
        if (stack == null) return;
        partition(stack.status(), stack.reason(), stack.truncated(), stack.capturedAt(), issues);
        if (stack.frames() == null || stack.frames().size() > 128) {
            invalid(issues, "崩溃线程 Java 帧缺失或超限");
            return;
        }
        if (!stack.frames().isEmpty()) {
            var crashed = diagnostics == null ? null : diagnostics.crashedThread();
            if (crashed == null || crashed.javaId() == null || value.systemTid() == null
                    || !value.systemTid().equals(crashed.systemTid())) {
                invalid(issues, "崩溃线程 Java 栈缺少可靠关联");
            }
        }
        if (noContent(stack.status()) && !stack.frames().isEmpty()) invalid(issues, "未采集 Java 栈不能携带帧");
        for (StackFrame frame : stack.frames()) {
            if (frame == null || frame.className() == null || frame.className().isBlank()
                    || frame.methodName() == null || frame.methodName().isBlank()) {
                invalid(issues, "Java 帧缺少类或方法");
            } else {
                text(frame.className(), 512, issues);
                text(frame.methodName(), 512, issues);
                text(frame.fileName(), 512, issues);
                if (frame.lineNumber() != null && (frame.lineNumber() < -1 || frame.lineNumber() > 1_000_000_000)) {
                    invalid(issues, "Java 帧行号不合法");
                }
            }
        }
    }

    /** 按 TID 验证 Native 快照，并和 Java 快照共享总数与字节上限。 */
    private void nativeThreads(NativeCrash value, CrashDiagnostics diagnostics, Set<String> moduleIds, int maxDigits,
                               List<ValidationIssue> issues) {
        if (diagnostics == null) return;
        var javaThreads = diagnostics.otherThreads();
        var nativeThreads = diagnostics.nativeThreads();
        int javaCount = javaThreads == null || javaThreads.threads() == null ? 0 : javaThreads.threads().size();
        int nativeCount = nativeThreads == null || nativeThreads.threads() == null ? 0 : nativeThreads.threads().size();
        if ((long) javaCount + nativeCount > 64) invalid(issues, "Native 与 Java 线程总数超过上限");
        try {
            long bytes = (javaThreads == null ? 0 : mapper.writeValueAsBytes(javaThreads).length)
                    + (nativeThreads == null ? 0 : mapper.writeValueAsBytes(nativeThreads).length);
            if (bytes > 48 * 1024) invalid(issues, "Native 与 Java 线程合计超过大小上限");
        } catch (RuntimeException ex) {
            invalid(issues, "线程诊断无法序列化");
        }
        if (javaThreads != null && javaThreads.threads() != null) for (var thread : javaThreads.threads()) {
            if (thread != null && (thread.relationToCrash() == null
                    || !Set.of("other", "unknown").contains(thread.relationToCrash()))) {
                invalid(issues, "Java 线程与 Native 崩溃的关系缺失或不合法");
            }
            if (thread != null && thread.systemTid() != null && thread.systemTid().equals(value.systemTid())) {
                invalid(issues, "Java 辅助线程不能包含已关联的崩溃 TID");
            }
        }
        if (nativeThreads == null) return;
        partition(nativeThreads.status(), nativeThreads.reason(), nativeThreads.truncated(), nativeThreads.capturedAt(), issues);
        if (nativeThreads.threads() == null || nativeCount > 64) {
            invalid(issues, "Native 线程缺失或超限");
            return;
        }
        if (noContent(nativeThreads.status()) && nativeCount != 0) invalid(issues, "未采集 Native 线程不能携带明细");
        if (nativeThreads.observedCount() != null && nativeThreads.observedCount() < nativeCount
                || nativeThreads.omittedCount() != null && nativeThreads.omittedCount() < 0
                || nativeThreads.observedCount() != null && nativeThreads.omittedCount() != null
                && (long) nativeThreads.omittedCount() + nativeCount > nativeThreads.observedCount()) {
            invalid(issues, "Native 线程观测或遗漏数量不合法");
        }
        Set<Integer> tids = new HashSet<>();
        for (var thread : nativeThreads.threads()) {
            if (thread == null || thread.systemTid() == null || thread.systemTid() <= 0
                    || thread.systemTid().equals(value.systemTid()) || !tids.add(thread.systemTid())) {
                invalid(issues, "Native 线程 TID 不合法或重复");
                continue;
            }
            text(thread.name(), 512, issues);
            backtrace(thread.backtrace(), 32, moduleIds, maxDigits, issues);
        }
    }

    /** 校验采集状态，不将不可用视为成功空结果。 */
    private void partition(String status, String reason, Boolean truncated, Long capturedAt, List<ValidationIssue> issues) {
        if (status == null || !STATUSES.contains(status) || truncated == null
                || reason != null && !REASONS.contains(reason) || !"ok".equals(status) && reason == null
                || "ok".equals(status) && reason != null || Boolean.TRUE.equals(truncated) && "ok".equals(status)
                || capturedAt != null && (capturedAt < 0 || capturedAt > SAFE_INTEGER)) {
            invalid(issues, "Native 采集状态不合法");
        }
    }

    /** 判断未采集状态。 */
    private boolean noContent(String status) {
        return "unavailable".equals(status) || "skipped".equals(status);
    }

    /** 保留零地址并限制规范编码和架构位宽。 */
    private void hex(String value, int maxDigits, List<ValidationIssue> issues) {
        if (value != null && !value.matches("0x(?:0|[1-9a-f][0-9a-f]{0," + (maxDigits - 1) + "})")) {
            invalid(issues, "Native 地址或偏移不是有效的架构位宽十六进制字符串");
        }
    }

    /** 限定可选文本的 Unicode 字符数。 */
    private void text(String value, int maximum, List<ValidationIssue> issues) {
        if (value != null && value.codePointCount(0, value.length()) > maximum) invalid(issues, "Native 文本超过长度上限");
    }

    /** 返回固定错误类别，不回显不可信现场。 */
    private void invalid(List<ValidationIssue> issues, String message) {
        issues.add(new ValidationIssue("INVALID_NATIVE_CRASH", message));
    }
}
