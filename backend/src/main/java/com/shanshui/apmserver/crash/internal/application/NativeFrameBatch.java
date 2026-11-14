package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** 对主线程和辅助线程中同一模块的原物理帧执行有界批量解析。 */
@Component
public class NativeFrameBatch {
    /** 不记录地址、路径或原始堆栈。 */
    private static final Logger LOG = LoggerFactory.getLogger(NativeFrameBatch.class);
    /** 同一轮主辅栈新增完整帧结果的 UTF-8 JSON 字节上限。 */
    private static final int MAX_NEW_OUTPUT_BYTES = 512 * 1024;
    /** 用于精确选择并固定 SO 修订的符号域边界。 */
    private final SymbolRegistry symbols;
    /** 使用持久化相同的编码器计量完整结果。 */
    private final tools.jackson.databind.ObjectMapper outputMapper;
    /** 单调时钟用于控制真实执行预算。 */
    private final java.util.function.LongSupplier nanoTime;
    /** 墙上时钟用于尝试排序与重试时间。 */
    private final java.time.Clock clock;

    /** 绑定符号模块公开接口。 */
    @org.springframework.beans.factory.annotation.Autowired
    public NativeFrameBatch(SymbolRegistry symbols, tools.jackson.databind.ObjectMapper outputMapper) {
        this(symbols, outputMapper, System::nanoTime, java.time.Clock.systemUTC());
    }

    /** 测试或独立调用使用默认 JSON 编码。 */
    public NativeFrameBatch(SymbolRegistry symbols) {
        this(symbols, new tools.jackson.databind.ObjectMapper(), System::nanoTime, java.time.Clock.systemUTC());
    }

    /** 注入时钟以验证预算和公平轮转。 */
    NativeFrameBatch(SymbolRegistry symbols, java.util.function.LongSupplier nanoTime, java.time.Clock clock) {
        this(symbols, new tools.jackson.databind.ObjectMapper(), nanoTime, clock);
    }

    /** 显式注入编码器与时钟以保持预算统计和持久化一致。 */
    NativeFrameBatch(SymbolRegistry symbols, tools.jackson.databind.ObjectMapper outputMapper,
                     java.util.function.LongSupplier nanoTime, java.time.Clock clock) {
        this.symbols = symbols;
        this.outputMapper = outputMapper;
        this.nanoTime = nanoTime;
        this.clock = clock;
    }

    /** 同一轮主栈和辅助线程的完整结果。 */
    public record Stacks(
            /** 原采集字段不变的主栈。 */ NativeStack main,
            /** 原线程身份与顺序不变的辅助栈。 */ com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks auxiliary) { }

    /** 跨主栈和其他 Native 线程统一按模块去重，再还原原来的线程边界。 */
    public Stacks resolveStacks(UUID appId, String appVersion, NativeStack main,
                                com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks auxiliary, Duration budget) {
        if (main == null || auxiliary == null) throw new IllegalArgumentException("Native 主辅栈缺失");
        var input = new ArrayList<>(main.backtrace().frames());
        auxiliary.nativeThreads().forEach(thread -> input.addAll(thread.backtrace().frames()));
        var output = resolve(appId, appVersion, main.modules(), input, budget);
        int cursor = main.backtrace().frames().size();
        var mainTrace = withFrames(main.backtrace(), output.subList(0, cursor));
        var threads = new ArrayList<com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks.NativeThread>();
        for (var thread : auxiliary.nativeThreads()) {
            int end = cursor + thread.backtrace().frames().size();
            threads.add(new com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks.NativeThread(thread.systemTid(),
                    withFrames(thread.backtrace(), output.subList(cursor, end))));
            cursor = end;
        }
        var updated = new NativeStack(main.abi(), main.systemTid(), main.threadName(), main.mainThread(), main.signal(),
                main.registers(), main.modules(), mainTrace, main.javaStack());
        return new Stacks(updated, new com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks(auxiliary.javaThreads(), threads));
    }

    /** 只替换当前还原结果，不修改回溯采集状态。 */
    private static NativeStack.Backtrace withFrames(NativeStack.Backtrace trace, List<NativeStack.Frame> frames) {
        return new NativeStack.Backtrace(trace.status(), trace.reason(), trace.truncated(), trace.capturedAt(), frames);
    }

    /** 批量返回对应位置的当前帧；未开始单元保持原样。 */
    public List<NativeStack.Frame> resolve(UUID appId, String appVersion, List<NativeCrash.Module> modules,
                                           List<NativeStack.Frame> frames, Duration budget) {
        if (appId == null || appVersion == null || modules == null || frames == null || budget == null || budget.isNegative()) {
            throw new IllegalArgumentException("Native 批次参数无效");
        }
        long start = nanoTime.getAsLong();
        /** 仅累计本轮新增成功帧；此前固定结果不占用本轮预算。 */
        int newOutputBytes = 0;
        var result = new ArrayList<>(frames);
        var moduleById = new LinkedHashMap<String, NativeCrash.Module>();
        modules.forEach(module -> moduleById.put(module.id(), module));
        var groups = new LinkedHashMap<Identity, List<Integer>>();
        for (int index = 0; index < frames.size(); index++) {
            var frame = frames.get(index);
            if ("symbolicated".equals(frame.resolution().status()) || "not_applicable".equals(frame.resolution().status())) continue;
            if (frame.resolution().retryAt() != null && frame.resolution().retryAt().isAfter(clock.instant())) continue;
            var module = moduleById.get(frame.moduleId());
            if (module == null || module.abi() == null || module.elfBuildId() == null || frame.relativePc() == null) continue;
            groups.computeIfAbsent(new Identity(module.abi(), module.elfBuildId()), ignored -> new ArrayList<>()).add(index);
        }
        var orderedGroups = new ArrayList<>(groups.entrySet());
        orderedGroups.sort(java.util.Comparator.comparing((java.util.Map.Entry<Identity, List<Integer>> group) ->
                        group.getValue().stream().noneMatch(index -> frames.get(index).resolution().processedAt() == null))
                .thenComparing(group -> group.getValue().stream().map(index -> frames.get(index).resolution().processedAt())
                        .filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(Instant.MIN)));
        for (var group : orderedGroups) {
            Duration remaining = remaining(start, budget);
            if (remaining.isZero()) break;
            try {
                var selection = symbols.acquireNative(appId, appVersion, group.getKey().abi(), group.getKey().buildId());
                if (selection.isEmpty()) {
                    for (int index : group.getValue()) {
                        var old = frames.get(index);
                        var prior = old.resolution();
                        result.set(index, withResolution(old, new NativeStack.Resolution("pending", "missing_so", null,
                                null, prior.attemptedSource(), prior.processedAt(), null)));
                    }
                    continue;
                }
                try (var lease = selection.get()) {
                    var source = new NativeStack.Source("NATIVE_SO", lease.symbolId(), lease.revision(), lease.sha256());
                    var eligible = new ArrayList<Integer>();
                    for (int index : group.getValue()) {
                        var old = frames.get(index).resolution();
                        if (old.retryAt() != null && old.retryAt().isAfter(clock.instant())) continue;
                        if ("failed".equals(old.status()) && old.retryAt() == null && source.equals(old.attemptedSource())) continue;
                        eligible.add(index);
                    }
                    remaining = remaining(start, budget);
                    if (eligible.isEmpty() || remaining.isZero()) continue;
                    var addresses = eligible.stream().map(index -> frames.get(index).relativePc()).distinct().toList();
                    var batch = symbols.symbolizeNative(lease, addresses, remaining);
                    Instant attempted = clock.instant();
                    for (int index : eligible) {
                        var old = frames.get(index);
                        var item = batch.frames().get(old.relativePc());
                        NativeStack.Resolution resolution;
                        if (batch.reason() != null || item == null) {
                            String reason = batch.reason() == null ? "native_invalid_output_or_request" : batch.reason();
                            resolution = new NativeStack.Resolution("pending", reason, null, null, source, attempted, attempted.plusSeconds(30));
                        } else if (item.reason() != null) {
                            resolution = new NativeStack.Resolution("failed", item.reason(), null, null, source, attempted, null);
                        } else {
                            var inline = item.inline().stream().map(value -> new NativeStack.Inline(value.function(), value.file(), value.line(), value.column())).toList();
                            /** 同地址的每个物理帧均独立保存结果，必须分别计入预算。 */
                            var successful = new NativeStack.Resolution("symbolicated", null, inline, source, source, attempted, null);
                            int bytes = outputMapper.writeValueAsBytes(successful).length;
                            if (bytes > MAX_NEW_OUTPUT_BYTES - newOutputBytes) {
                                resolution = new NativeStack.Resolution("failed", "native_output_limit", null, null, source,
                                        attempted, attempted.plusSeconds(30));
                                LOG.warn("Native 事件本轮新增结果达到字节上限，保留原始帧等待重试");
                            } else {
                                newOutputBytes += bytes;
                                resolution = successful;
                            }
                        }
                        result.set(index, withResolution(old, resolution));
                    }
                    LOG.info("Native 模块批次已处理，物理帧数={}，地址数={}", eligible.size(), addresses.size());
                }
            } catch (SymbolStoreUnavailableException failure) {
                Instant attempted = clock.instant();
                for (int index : group.getValue()) {
                    var old = frames.get(index);
                    var prior = old.resolution();
                    if (prior.retryAt() != null && prior.retryAt().isAfter(attempted)) continue;
                    result.set(index, withResolution(old, new NativeStack.Resolution("pending", "native_store_unavailable", null,
                            null, prior.attemptedSource(), attempted, attempted.plusSeconds(30))));
                }
                LOG.warn("Native 模块来源暂不可读，待后续重试");
            } catch (IOException failure) {
                LOG.warn("Native 模块租约释放失败");
            }
        }
        return List.copyOf(result);
    }

    /** 同一精确 ABI 与完整 ELF Build ID 共用一次租约和工具进程。 */
    private record Identity(String abi, String buildId) { }

    /** 保留原物理帧身份，只替换其独立处理结果。 */
    private static NativeStack.Frame withResolution(NativeStack.Frame frame, NativeStack.Resolution resolution) {
        return new NativeStack.Frame(frame.index(), frame.moduleId(), frame.relativePc(), frame.functionName(), frame.functionOffset(), resolution);
    }

    /** 整批共享期限，超时后不伪造未开始单元的失败。 */
    private Duration remaining(long start, Duration budget) {
        long left = budget.toNanos() - (nanoTime.getAsLong() - start);
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }
}
