package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/** 在主异常成功后使用同一 mapping 有界处理其他线程。 */
final class CrashAuxiliaryStacks {
    /** 单个辅助线程的完整输出上限。 */
    private static final int THREAD_OUTPUT_BYTES = 32 * 1024;
    /** 全部辅助当前栈 JSON 的上限。 */
    private static final int TOTAL_OUTPUT_BYTES = 256 * 1024;
    /** 辅助阶段总等待预算。 */
    private static final long TOTAL_WAIT_NANOS = Duration.ofSeconds(2).toNanos();
    /** 可公开的固定失败原因。 */
    private static final Set<String> REASONS = Set.of("parser_busy", "mapping_unavailable", "timeout", "interrupted",
            "invalid_input", "invalid_stack", "empty_output", "output_limit", "retrace_failed", "budget_exhausted");
    /** 当前 JSON 配置。 */
    private final ObjectMapper mapper;
    /** 固定 mapping 与有界辅助还原入口。 */
    private final SymbolRegistry symbols;
    /** 还原文本的服务端二次脱敏。 */
    private final CrashSanitizer sanitizer;
    /** 可注入单调时间，便于验证总预算。 */
    private final LongSupplier nanoTime;

    /** 使用生产单调时钟。 */
    CrashAuxiliaryStacks(ObjectMapper mapper, SymbolRegistry symbols, CrashSanitizer sanitizer) {
        this(mapper, symbols, sanitizer, System::nanoTime);
    }

    /** 注入可控时钟。 */
    CrashAuxiliaryStacks(ObjectMapper mapper, SymbolRegistry symbols, CrashSanitizer sanitizer, LongSupplier nanoTime) {
        this.mapper = mapper;
        this.symbols = symbols;
        this.sanitizer = sanitizer;
        this.nanoTime = nanoTime;
    }

    /** 保留输入线程边界，逐个固定完整成功或原始失败结果。 */
    String resolve(String payload, SymbolFileLease mapping) {
        if (payload == null || payload.isEmpty()) return "";
        List<CrashThreadStack> original = decode(payload);
        List<CrashThreadStack> result = new ArrayList<>(original);
        long deadline = nanoTime.getAsLong() + TOTAL_WAIT_NANOS;
        for (int index = 0; index < original.size(); index++) {
            CrashThreadStack thread = original.get(index);
            if ("not_applicable".equals(thread.symbolicationStatus())) continue;
            if (!"raw_frames".equals(thread.stack().format())) throw new IllegalArgumentException("辅助线程初始状态不合法");
            long remaining = deadline - nanoTime.getAsLong();
            CrashThreadStack next;
            if (remaining <= 0) {
                next = failed(thread, "budget_exhausted");
            } else {
                SymbolicationResult retraced;
                try {
                    List<StackFrame> frames = ((List<?>) thread.stack().content()).stream()
                            .map(frame -> mapper.convertValue(frame, StackFrame.class)).toList();
                    retraced = symbols.retraceAuxiliary(mapping, CrashStackTraceFormatter.threadLines(frames),
                            THREAD_OUTPUT_BYTES, Duration.ofNanos(remaining));
                } catch (RuntimeException unavailable) {
                    retraced = SymbolicationResult.failed("retrace_failed");
                }
                next = resolved(thread, retraced, mapping);
            }
            result.set(index, next);
            // 不截断完整还原文本；超过总上限时只回退当前线程的原始帧。
            if (encodedBytes(result) > TOTAL_OUTPUT_BYTES) result.set(index, failed(thread, "output_limit"));
        }
        if (encodedBytes(result) > TOTAL_OUTPUT_BYTES) throw new IllegalStateException("辅助线程原始栈超过总预算");
        return mapper.writeValueAsString(result);
    }

    /** 最终发布载荷不足时回退所有辅助结果，原始帧和独立失败状态仍可见。 */
    String fallback(String payload) {
        if (payload == null || payload.isEmpty()) return "";
        return mapper.writeValueAsString(decode(payload).stream().map(thread ->
                "not_applicable".equals(thread.symbolicationStatus()) ? thread : failed(thread, "output_limit")).toList());
    }

    /** 完整输出经脱敏和二次大小校验后才替换原始帧。 */
    private CrashThreadStack resolved(CrashThreadStack original, SymbolicationResult result, SymbolFileLease mapping) {
        if (result == null || result.status() != SymbolicationResult.Status.SYMBOLICATED || result.text() == null
                || result.text().isBlank()) return failed(original, safeReason(result == null ? null : result.reason()));
        String safeText = sanitizer.sanitizeRetrace(result.text());
        if (safeText.isBlank() || safeText.getBytes(StandardCharsets.UTF_8).length > THREAD_OUTPUT_BYTES) {
            return failed(original, "output_limit");
        }
        return new CrashThreadStack(original.javaId(), "symbolicated", null, new CrashThreadStack.Stack("retrace_text", safeText),
                mapping.symbolId(), mapping.revision(), mapping.sha256(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** 失败保留完整原始帧，不借用主异常的成功状态。 */
    private CrashThreadStack failed(CrashThreadStack original, String reason) {
        return new CrashThreadStack(original.javaId(), "failed", safeReason(reason), original.stack(), null, null, null, null);
    }

    /** 不回传第三方或异常原文作为失败原因。 */
    private String safeReason(String reason) { return reason != null && REASONS.contains(reason) ? reason : "retrace_failed"; }

    /** 解码经接收校验的原始辅助栈。 */
    private List<CrashThreadStack> decode(String payload) {
        return Arrays.asList(mapper.readValue(payload, CrashThreadStack[].class));
    }

    /** 按最终 UTF-8 JSON 字节统计辅助输出。 */
    private int encodedBytes(Object value) { return mapper.writeValueAsBytes(value).length; }
}
