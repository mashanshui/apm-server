package com.shanshui.apmserver.anr.internal.application;

import com.shanshui.apmserver.crash.api.DiagnosticSupport;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** 有界还原 Matrix 主线程栈和 ART trace 中可辨识的 Java 帧。 */
@Component
public class AnrJavaRetrace {
    /** 只记录状态和计数。 */
    private static final Logger LOG = LoggerFactory.getLogger(AnrJavaRetrace.class);
    /** 单个连续帧段最多调用一次的帧数。 */
    private static final int MAX_GROUP_FRAMES = 64;
    /** 单线程最多处理的帧数。 */
    private static final int MAX_THREAD_FRAMES = 256;
    /** 两个分区合计最多处理的帧数。 */
    private static final int MAX_TOTAL_FRAMES = 2048;
    /** 单次 R8 辅助输出上限。 */
    private static final int MAX_GROUP_OUTPUT = 32 * 1024;
    /** 单线程所有已还原片段的输出上限。 */
    private static final int MAX_THREAD_OUTPUT = 64 * 1024;
    /** 每个分区输入上限。 */
    private static final int MAX_INPUT = 128 * 1024;
    /** 两个分区派生文本合计上限。 */
    private static final int MAX_RESULT = 256 * 1024;
    /** 两个分区共用的后台处理时间。 */
    private static final long TOTAL_NANOS = Duration.ofSeconds(2).toNanos();
    /** 可以安全展示的受控失败原因。 */
    private static final Set<String> REASONS = Set.of("parser_busy", "mapping_unavailable", "timeout", "interrupted",
            "invalid_input", "invalid_stack", "empty_output", "output_limit", "retrace_failed", "budget_exhausted");
    /** 只接受完整的 Java 栈帧。 */
    private static final Pattern FRAME = Pattern.compile("^(?:at\\s+)?([\\p{L}\\p{N}_$]+(?:\\.[\\p{L}\\p{N}_$<>]+)+\\([^()\\r\\n]+\\))$");
    /** ART 线程头必须带线程标识，避免把片段中的任意文本误当线程栈。 */
    private static final Pattern THREAD = Pattern.compile("^\\\"[^\\r\\n]*\\\"\\s+prio=\\d+\\s+tid=\\d+\\b.*$");
    /** 有界共享 R8 入口。 */
    private final SymbolRegistry symbols;
    /** 已有诊断脱敏规则。 */
    private final DiagnosticSupport diagnostics;
    /** 单调时钟，测试可替换。 */
    private final LongSupplier nanoTime;

    /** 生产环境使用系统单调时钟。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AnrJavaRetrace(SymbolRegistry symbols, DiagnosticSupport diagnostics) {
        this(symbols, diagnostics, System::nanoTime);
    }

    /** 使用指定单调时钟测试总预算。 */
    AnrJavaRetrace(SymbolRegistry symbols, DiagnosticSupport diagnostics, LongSupplier nanoTime) {
        this.symbols = symbols;
        this.diagnostics = diagnostics;
        this.nanoTime = nanoTime;
    }

    /** 两个分区共用总时间、帧数和派生字节预算。 */
    public Result resolve(String mainStack, String trace, boolean traceTruncated, SymbolFileLease mapping) {
        var budget = new Budget(nanoTime.getAsLong() + TOTAL_NANOS);
        Section main = section(mainStack, false, false, mapping, budget);
        Section art = section(trace, true, traceTruncated, mapping, budget);
        LOG.debug("ANR Java 还原结束，main={} trace={} frames={}", main.status(), art.status(), budget.frames);
        return new Result(main, art);
    }

    /** 按线程和连续帧段还原，所有其他行按原值复制。 */
    private Section section(String original, boolean trace, boolean truncated, SymbolFileLease mapping, Budget budget) {
        if (original == null || original.isEmpty()) return new Section("not_applicable", null, null);
        int originalBytes = bytes(original);
        if (originalBytes > MAX_INPUT || budget.resultBytes + originalBytes > MAX_RESULT) {
            return new Section("failed", "budget_exhausted", null);
        }
        budget.resultBytes += originalBytes;
        String[] lines = original.split("\n", -1);
        List<String> output = new ArrayList<>(List.of(lines));
        boolean inThread = !trace;
        int threadFrames = 0;
        int threadOutput = trace ? 0 : originalBytes;
        boolean changed = false;
        String failure = null;
        int position = 0;
        while (position < lines.length) {
            String line = lines[position];
            if (trace && THREAD.matcher(line.trim()).matches()) {
                inThread = true;
                threadFrames = 0;
                threadOutput = threadBytes(lines, position);
                position++;
                continue;
            }
            if (trace && (line.isBlank() || line.startsWith("----- end"))) inThread = false;
            boolean incompleteTail = truncated && position == lines.length - 1 && !original.endsWith("\n");
            if (!inThread || !frame(line, trace) || incompleteTail) {
                if (inThread && (looksIncomplete(line) || incompleteTail && frame(line, trace))) failure = "invalid_input";
                position++;
                continue;
            }
            int start = position;
            List<String> input = new ArrayList<>();
            while (position < lines.length && frame(lines[position], trace) && input.size() < MAX_GROUP_FRAMES
                    && !(truncated && position == lines.length - 1 && !original.endsWith("\n"))) {
                input.add("    at " + frameText(lines[position]));
                position++;
            }
            threadFrames += input.size();
            budget.frames += input.size();
            long remaining = budget.deadline - nanoTime.getAsLong();
            if (threadFrames > MAX_THREAD_FRAMES || threadOutput > MAX_THREAD_OUTPUT
                    || budget.frames > MAX_TOTAL_FRAMES || remaining <= 0) {
                failure = "budget_exhausted";
                continue;
            }
            SymbolicationResult result;
            try {
                result = symbols.retraceAuxiliary(mapping, input, MAX_GROUP_OUTPUT, Duration.ofNanos(remaining));
            } catch (RuntimeException unavailable) {
                result = SymbolicationResult.failed("retrace_failed");
            }
            if (result == null || result.status() != SymbolicationResult.Status.SYMBOLICATED
                    || result.text() == null || result.text().isBlank()) {
                failure = safeReason(result == null ? null : result.reason());
                continue;
            }
            String safe = diagnostics.sanitizeText(result.text(), Integer.MAX_VALUE);
            if (sameFrames(input, safe)) continue;
            int oldBytes = bytes(String.join("\n", java.util.Arrays.copyOfRange(lines, start, position)));
            int newBytes = bytes(safe);
            if (newBytes > MAX_GROUP_OUTPUT || threadOutput - oldBytes + newBytes > MAX_THREAD_OUTPUT
                    || budget.resultBytes - oldBytes + newBytes > MAX_RESULT) {
                failure = "output_limit";
                continue;
            }
            output.set(start, safe);
            for (int index = start + 1; index < position; index++) output.set(index, null);
            budget.resultBytes += newBytes - oldBytes;
            threadOutput += newBytes - oldBytes;
            changed = true;
        }
        if (!changed) return failure == null ? new Section("not_applicable", null, null) : new Section("failed", failure, null);
        return new Section(failure == null ? "symbolicated" : "partial", failure,
                String.join("\n", output.stream().filter(java.util.Objects::nonNull).toList()));
    }

    /** 按 Java 栈帧的完整语法判断是否可交给 R8。 */
    private boolean frame(String line, boolean trace) {
        String trimmed = line.trim();
        return (!trace || trimmed.startsWith("at ")) && FRAME.matcher(trimmed).matches();
    }

    /** 计算一个 ART 线程的原始字节，避免派生结果越过线程预算。 */
    private int threadBytes(String[] lines, int start) {
        int end = start + 1;
        while (end < lines.length && !lines[end].isBlank() && !lines[end].startsWith("----- end")
                && !THREAD.matcher(lines[end].trim()).matches()) end++;
        return bytes(String.join("\n", java.util.Arrays.copyOfRange(lines, start, end)));
    }

    /** 移除可能存在的 at 前缀，适配 Matrix 不带前缀的主线程栈。 */
    private String frameText(String line) {
        String text = line.trim();
        return text.startsWith("at ") ? text.substring(3).trim() : text;
    }

    /** 不完整的显式 Java 帧保留原文并标记该分区未完全处理。 */
    private boolean looksIncomplete(String line) {
        String text = line.trim();
        return text.startsWith("at ") && !frame(line, false);
    }

    /** 忽略缩进和 at 前缀，避免把未变化的输出称为已还原。 */
    private boolean sameFrames(List<String> input, String output) {
        List<String> before = input.stream().map(this::frameText).toList();
        List<String> after = output.lines().map(this::frameText).toList();
        return before.equals(after);
    }

    /** 失败原因使用固定枚举，避免暴露 mapping 或诊断内容。 */
    private String safeReason(String value) {
        return value != null && REASONS.contains(value) ? value : "retrace_failed";
    }

    /** 计算实际 UTF-8 字节。 */
    private int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 两个分区的独立结果。 */
    public record Result(Section mainThread, Section trace) { }

    /** 分区状态与可用时的派生文本。 */
    public record Section(String status, String reason, String text) { }

    /** 两个分区共用的资源计数。 */
    private static final class Budget {
        /** 本次处理截止时间。 */
        private final long deadline;
        /** 已处理帧数。 */
        private int frames;
        /** 已保留的派生文本字节。 */
        private int resultBytes;

        /** 创建指定截止时间的预算。 */
        private Budget(long deadline) {
            this.deadline = deadline;
        }
    }
}
