package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 独立还原 Native 关联 Java 栈和 Java 快照，固定各栈首次成功。 */
@Component
public class NativeJavaStackBatch {
    /** 日志不输出 Java 栈、文件路径或原始异常。 */
    private static final Logger LOG = LoggerFactory.getLogger(NativeJavaStackBatch.class);
    /** 单条完整还原文本的最大字节数。 */
    private static final int MAX_BYTES = 32 * 1024;
    /** 同一轮关联 Java 栈与其他线程新增完整文本的 UTF-8 字节上限。 */
    private static final int MAX_TOTAL_BYTES = 256 * 1024;
    /** 可以安全对外发布的 R8 原因。 */
    private static final Set<String> REASONS = Set.of("parser_busy", "mapping_unavailable", "timeout", "interrupted",
            "invalid_input", "invalid_stack", "empty_output", "output_limit", "retrace_failed");
    /** 版本选择器与有界完整栈还原。 */
    private final SymbolRegistry symbols;
    /** 从持久化结构化值读取 Java 采集帧。 */
    private final ObjectMapper mapper;
    /** 对还原文本执行接收时同等脱敏。 */
    private final CrashSanitizer sanitizer;
    /** 独立 Java 阶段的单调时钟。 */
    private final java.util.function.LongSupplier nanoTime;
    /** Java 栈尝试及重试时间。 */
    private final java.time.Clock clock;

    /** 注入现有符号域、JSON 解析器和脱敏器。 */
    @org.springframework.beans.factory.annotation.Autowired
    public NativeJavaStackBatch(SymbolRegistry symbols, ObjectMapper mapper, CrashSanitizer sanitizer) {
        this(symbols, mapper, sanitizer, System::nanoTime, java.time.Clock.systemUTC());
    }

    /** 注入可控时钟以验证有限预算的轮转顺序。 */
    NativeJavaStackBatch(SymbolRegistry symbols, ObjectMapper mapper, CrashSanitizer sanitizer,
                         java.util.function.LongSupplier nanoTime, java.time.Clock clock) {
        this.symbols = symbols;
        this.mapper = mapper;
        this.sanitizer = sanitizer;
        this.nanoTime = nanoTime;
        this.clock = clock;
    }

    /** 主关联栈与辅助线程各自保留结果和原线程身份。 */
    public NativeFrameBatch.Stacks resolve(UUID appId, String appVersion, String buildId, NativeStack main,
                                           NativeCurrentAuxiliaryStacks auxiliary, Duration budget) {
        if (appId == null || appVersion == null || buildId == null || main == null || auxiliary == null
                || budget == null || budget.isNegative()) throw new IllegalArgumentException("Java 栈批次参数无效");
        long start = nanoTime.getAsLong();
        /** 此预算只统计本轮新增成功结果，不重计此前固定的栈。 */
        int newOutputBytes = 0;
        var stacks = new ArrayList<NativeStack.JavaStack>();
        if (main.javaStack() != null) stacks.add(main.javaStack());
        auxiliary.javaThreads().forEach(thread -> stacks.add(thread.stack()));
        var updated = new ArrayList<>(stacks);
        if (stacks.stream().noneMatch(NativeJavaStackBatch::eligible) || budget.isZero()) return new NativeFrameBatch.Stacks(main, auxiliary);
        try {
            var selection = symbols.acquireVersioned(appId, appVersion, buildId);
            if (selection.isEmpty()) return new NativeFrameBatch.Stacks(main, auxiliary);
            try (var lease = selection.get()) {
                var source = new NativeStack.Source("MAPPING", lease.symbolId(), lease.revision(), lease.sha256());
                var order = java.util.stream.IntStream.range(0, stacks.size()).boxed().sorted(
                        java.util.Comparator.comparing((Integer index) -> stacks.get(index).processedAt() != null)
                                .thenComparing(index -> stacks.get(index).processedAt(), java.util.Comparator.nullsFirst(Instant::compareTo))).toList();
                for (int index : order) {
                    var stack = stacks.get(index);
                    if (!eligible(stack) || stack.retryAt() != null && stack.retryAt().isAfter(clock.instant())
                            || "failed".equals(stack.symbolicationStatus()) && stack.retryAt() == null && source.equals(stack.attemptedSource())) continue;
                    long nanos = budget.toNanos() - (nanoTime.getAsLong() - start);
                    if (nanos <= 0) break;
                    SymbolicationResult resolved;
                    try {
                        List<StackFrame> frames = ((List<?>) stack.stack().content()).stream().map(value -> mapper.convertValue(value, StackFrame.class)).toList();
                        resolved = symbols.retraceAuxiliary(lease, CrashStackTraceFormatter.threadLines(frames), MAX_BYTES, Duration.ofNanos(nanos));
                    } catch (RuntimeException failure) {
                        resolved = SymbolicationResult.failed("retrace_failed");
                    }
                    Instant attempted = clock.instant();
                    String text = resolved != null && resolved.status() == SymbolicationResult.Status.SYMBOLICATED && resolved.text() != null
                            ? sanitizer.sanitizeRetrace(resolved.text()) : "";
                    /** 完整文本按实际 UTF-8 字节计量，不能截断后伪装成功。 */
                    int bytes = text.getBytes(StandardCharsets.UTF_8).length;
                    if (!text.isBlank() && bytes <= MAX_BYTES && bytes <= MAX_TOTAL_BYTES - newOutputBytes) {
                        newOutputBytes += bytes;
                        updated.set(index, new NativeStack.JavaStack(stack.status(), stack.reason(), stack.truncated(), stack.capturedAt(),
                                "symbolicated", null, new CrashThreadStack.Stack("retrace_text", text), source, source, attempted, null));
                    } else {
                        String reason = resolved == null || resolved.reason() == null || !REASONS.contains(resolved.reason())
                                ? "retrace_failed" : resolved.reason();
                        if (!text.isBlank()) reason = "output_limit";
                        /** 累计预算不足只影响当前轮，退避后可用相同来源继续。 */
                        boolean aggregateLimit = !text.isBlank() && bytes <= MAX_BYTES && bytes > MAX_TOTAL_BYTES - newOutputBytes;
                        boolean temporary = aggregateLimit || Set.of("parser_busy", "mapping_unavailable", "timeout", "interrupted", "retrace_failed").contains(reason);
                        if (aggregateLimit) LOG.warn("Native Java 栈本轮新增结果达到字节上限，保留原始栈等待重试");
                        updated.set(index, new NativeStack.JavaStack(stack.status(), stack.reason(), stack.truncated(), stack.capturedAt(),
                                aggregateLimit ? "failed" : temporary ? "pending" : "failed", reason, stack.stack(), null, source, attempted,
                                temporary ? attempted.plusSeconds(30) : null));
                    }
                }
                LOG.info("Native Java 栈批次已处理，栈数量={}", stacks.size());
            }
        } catch (SymbolStoreUnavailableException failure) {
            Instant attempted = clock.instant();
            for (int index = 0; index < stacks.size(); index++) {
                var stack = stacks.get(index);
                if (!eligible(stack) || stack.retryAt() != null && stack.retryAt().isAfter(attempted)) continue;
                updated.set(index, new NativeStack.JavaStack(stack.status(), stack.reason(), stack.truncated(), stack.capturedAt(),
                        "pending", "mapping_unavailable", stack.stack(), null, stack.attemptedSource(), attempted, attempted.plusSeconds(30)));
            }
            LOG.warn("Native Java 栈 mapping 暂不可读，等待后续处理");
        } catch (IOException failure) {
            LOG.warn("Native Java 栈 mapping 租约释放失败");
        }
        int cursor = 0;
        NativeStack.JavaStack mainJava = main.javaStack() == null ? null : updated.get(cursor++);
        var threads = new ArrayList<NativeCurrentAuxiliaryStacks.JavaThread>();
        for (var thread : auxiliary.javaThreads()) threads.add(new NativeCurrentAuxiliaryStacks.JavaThread(thread.javaId(), updated.get(cursor++)));
        return new NativeFrameBatch.Stacks(new NativeStack(main.abi(), main.systemTid(), main.threadName(), main.mainThread(), main.signal(),
                main.registers(), main.modules(), main.backtrace(), mainJava), new NativeCurrentAuxiliaryStacks(threads, auxiliary.nativeThreads()));
    }

    /** 已成功及空栈永久排除。 */
    private static boolean eligible(NativeStack.JavaStack stack) {
        return !"symbolicated".equals(stack.symbolicationStatus()) && !"not_applicable".equals(stack.symbolicationStatus());
    }
}
