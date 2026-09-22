package com.shanshui.apmserver;

import com.shanshui.apmserver.symbol.api.SymbolParserBusyException;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.internal.application.SymbolOperationLimiter;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import com.shanshui.apmserver.symbol.internal.retrace.R8RetraceEngine;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用外部 release mapping 单独测量典型与最大上传体积的 Retrace 资源开销。 */
class SymbolMappingPerformanceTests {

    /** 符号文件首版允许的最大字节数，与生产默认配置一致。 */
    private static final long MAX_MAPPING_BYTES = 32L * 1024 * 1024;
    /** 性能验收使用的真实 mapping 路径属性。 */
    private static final String REAL_MAPPING_PROPERTY = "apm.real.mapping";
    /** 单独启用本类，避免普通单元测试意外解析最大文件。 */
    private static final String BENCHMARK_PROPERTY = "apm.symbol.benchmark";
    /** 与真实 Performance release mapping 相符的混淆堆栈帧。 */
    private static final List<String> SAMPLE_STACK = List.of(
            "at l.d0.c(StandardMenuPopup.java:25)",
            "at w1.w.<init>(PerformanceSdk.kt)");

    /** 最大体积合法 mapping 的隔离临时目录。 */
    @TempDir
    Path temporaryDirectory;

    /** 输出冷解析、重复详情、堆内存采样和并发饱和的可复现测量值。 */
    @Test
    void measuresTypicalAndMaximumMappingWorkloads() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(BENCHMARK_PROPERTY),
                "设置 -D" + BENCHMARK_PROPERTY + "=true 才运行符号表性能专项");
        Path typicalMapping = configuredRealMapping();
        long typicalBytes = Files.size(typicalMapping);
        Assumptions.assumeTrue(typicalBytes <= MAX_MAPPING_BYTES,
                "真实样本已经超过当前 mapping 大小上限");

        // 单独运行本类时首次触发 R8 mapping supplier，表示 JVM 冷解析，不代表冷 OS 文件缓存。
        R8RetraceEngine engine = new R8RetraceEngine();
        Measurement<Void> typicalValidation = measure(() -> engine.validate(typicalMapping));
        List<Measurement<SymbolicationResult>> typicalDetails = retraceSamples(engine, typicalMapping, 5);

        // 只追加合法空白行到 32 MiB，保留真实 mapping 定义并覆盖文件读取上限。
        Path maximumMapping = temporaryDirectory.resolve("maximum-release-mapping.txt");
        Files.copy(typicalMapping, maximumMapping);
        padWithBlankLines(maximumMapping, MAX_MAPPING_BYTES);
        assertEquals(MAX_MAPPING_BYTES, Files.size(maximumMapping));
        Measurement<Void> maximumValidation = measure(() -> engine.validate(maximumMapping));
        List<Measurement<SymbolicationResult>> maximumDetails = retraceSamples(engine, maximumMapping, 3);
        SaturationResult saturation = measureSaturation(engine, maximumMapping);

        System.out.printf(Locale.ROOT,
                "SYMBOL_BENCHMARK typicalBytes=%d maxBytes=%d typicalColdValidateMs=%.2f "
                        + "typicalDetailMedianMs=%.2f typicalDetailP95Ms=%.2f "
                        + "typicalPeakHeapDeltaMiB=%.2f typicalThreadAllocatedMiB=%.2f "
                        + "maxValidateMs=%.2f maxDetailMedianMs=%.2f maxDetailP95Ms=%.2f "
                        + "maxPeakHeapDeltaMiB=%.2f maxThreadAllocatedMiB=%.2f "
                        + "saturationAccepted=%d saturationBusy=%d saturationBusyP95Ms=%.2f permitsReleased=%s%n",
                typicalBytes, MAX_MAPPING_BYTES,
                milliseconds(typicalValidation.elapsedNanos()),
                medianMillis(typicalDetails), percentile95Millis(typicalDetails),
                mebibytes(typicalValidation.peakHeapIncreaseBytes()),
                mebibytes(typicalValidation.threadAllocatedBytes()),
                milliseconds(maximumValidation.elapsedNanos()),
                medianMillis(maximumDetails), percentile95Millis(maximumDetails),
                mebibytes(maximumValidation.peakHeapIncreaseBytes()),
                mebibytes(maximumValidation.threadAllocatedBytes()),
                saturation.accepted(), saturation.busy(), saturation.busyP95Millis(), saturation.permitsReleased());
    }

    /** 对 mapping 执行多次真实 R8 详情还原并确认每次均能安全返回。 */
    private List<Measurement<SymbolicationResult>> retraceSamples(
            R8RetraceEngine engine, Path mapping, int iterations) {
        List<Measurement<SymbolicationResult>> samples = new ArrayList<>();
        for (int index = 0; index < iterations; index++) {
            Measurement<SymbolicationResult> sample = measure(
                    () -> engine.retrace(mapping, SAMPLE_STACK, SymbolProperties.DEFAULT_MAX_OUTPUT_BYTES));
            assertEquals(SymbolicationResult.Status.SYMBOLICATED, sample.value().status());
            samples.add(sample);
        }
        return samples;
    }

    /** 以八个同步请求验证双许可饱和时快速拒绝、执行并发數和 finally 释放。 */
    private SaturationResult measureSaturation(R8RetraceEngine engine, Path mapping) throws Exception {
        int workerCount = 8;
        int permittedCount = SymbolProperties.DEFAULT_MAX_CONCURRENT_OPERATIONS;
        SymbolProperties properties = new SymbolProperties();
        properties.setMaxConcurrentOperations(permittedCount);
        SymbolOperationLimiter limiter = new SymbolOperationLimiter(properties);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch admitted = new CountDownLatch(permittedCount);
        CountDownLatch rejected = new CountDownLatch(workerCount - permittedCount);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger acceptedCount = new AtomicInteger();
        AtomicInteger busyCount = new AtomicInteger();
        List<Long> busyDurations = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> requests = new ArrayList<>();

        try {
            for (int index = 0; index < workerCount; index++) {
                requests.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    long startedAt = System.nanoTime();
                    try (SymbolOperationLimiter.Permit ignored = limiter.acquire()) {
                        acceptedCount.incrementAndGet();
                        admitted.countDown();
                        if (!release.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("并发性能测试等待释放超时");
                        }
                        SymbolicationResult result = engine.retrace(
                                mapping, SAMPLE_STACK, SymbolProperties.DEFAULT_MAX_OUTPUT_BYTES);
                        assertEquals(SymbolicationResult.Status.SYMBOLICATED, result.status());
                    } catch (SymbolParserBusyException ex) {
                        busyCount.incrementAndGet();
                        busyDurations.add(System.nanoTime() - startedAt);
                        rejected.countDown();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发任务未能就绪");
            start.countDown();
            assertTrue(admitted.await(10, TimeUnit.SECONDS), "双许可未能被并发请求取得");
            assertTrue(rejected.await(10, TimeUnit.SECONDS), "饱和请求没有按预期快速失败");
            release.countDown();
            for (Future<?> request : requests) {
                request.get(60, TimeUnit.SECONDS);
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        // 两个后续读取均能成功取得许可，第三个仍按上限失败，证明请求结束时已释放许可。
        try (SymbolOperationLimiter.Permit ignoredFirst = limiter.acquire();
             SymbolOperationLimiter.Permit ignoredSecond = limiter.acquire()) {
            assertEquals(permittedCount, acceptedCount.get());
            assertEquals(workerCount - permittedCount, busyCount.get());
            assertThrowsBusy(limiter);
        }
        return new SaturationResult(acceptedCount.get(), busyCount.get(),
                percentile95RawMillis(busyDurations), true);
    }

    /** 以 32 MiB 合法空白行补齐真实样本，避免合成映射内容改变 R8 定义。 */
    private void padWithBlankLines(Path mapping, long targetBytes) throws IOException {
        long remaining = targetBytes - Files.size(mapping);
        byte[] blankLines = new byte[8192];
        Arrays.fill(blankLines, (byte) '\n');
        try (var output = Files.newOutputStream(mapping, StandardOpenOption.APPEND)) {
            while (remaining > 0) {
                int count = (int) Math.min(blankLines.length, remaining);
                output.write(blankLines, 0, count);
                remaining -= count;
            }
        }
    }

    /** 在操作期间采样 JVM 堆并统计当前线程分配量和耗时。 */
    private <T> Measurement<T> measure(Supplier<T> operation) {
        System.gc();
        java.lang.management.MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean allocationBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().getId();
        long baselineHeapBytes = memoryBean.getHeapMemoryUsage().getUsed();
        long allocatedBefore = allocationBean.isThreadAllocatedMemorySupported()
                ? allocationBean.getThreadAllocatedBytes(threadId) : -1;
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicLong peakHeapBytes = new AtomicLong(baselineHeapBytes);
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                long used = memoryBean.getHeapMemoryUsage().getUsed();
                peakHeapBytes.accumulateAndGet(used, Math::max);
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }, "symbol-benchmark-heap-sampler");
        sampler.setDaemon(true);
        sampler.start();
        long startedAt = System.nanoTime();
        try {
            T value = operation.get();
            long elapsedNanos = System.nanoTime() - startedAt;
            long allocatedAfter = allocationBean.isThreadAllocatedMemorySupported()
                    ? allocationBean.getThreadAllocatedBytes(threadId) : allocatedBefore;
            return new Measurement<>(value, elapsedNanos,
                    Math.max(0, peakHeapBytes.get() - baselineHeapBytes),
                    allocatedBefore < 0 ? 0 : Math.max(0, allocatedAfter - allocatedBefore));
        } finally {
            sampling.set(false);
            try {
                sampler.join(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 测量无返回值的 mapping 校验操作。 */
    private Measurement<Void> measure(Runnable operation) {
        return measure(() -> {
            operation.run();
            return null;
        });
    }

    /** 确认所有解析许可已释放后，空余许可达到配置上限。 */
    private void assertThrowsBusy(SymbolOperationLimiter limiter) {
        try (SymbolOperationLimiter.Permit ignored = limiter.acquire()) {
            throw new AssertionError("并发许可上限被突破");
        } catch (SymbolParserBusyException expected) {
            // 所有许可被再次占满时应立刻拒绝下一项解析。
        }
    }

    /** 读取外部提供的真实 mapping 样本，不存在时跳过专项性能测试。 */
    private Path configuredRealMapping() {
        String configured = System.getProperty(REAL_MAPPING_PROPERTY);
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "未设置 -D" + REAL_MAPPING_PROPERTY + "，跳过外部 release mapping 性能专项");
        Path mapping = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(mapping), "真实 mapping 文件不存在");
        return mapping;
    }

    /** 计算一组纳秒耗时的中位数毫秒值。 */
    private double medianMillis(List<Measurement<SymbolicationResult>> samples) {
        List<Long> durations = samples.stream().map(Measurement::elapsedNanos).sorted().toList();
        return milliseconds(durations.get(durations.size() / 2));
    }

    /** 计算一组纳秒耗时的 P95 毫秒值。 */
    private double percentile95Millis(List<Measurement<SymbolicationResult>> samples) {
        return percentile95RawMillis(samples.stream().map(Measurement::elapsedNanos).toList());
    }

    /** 计算并发饱和请求拒绝延迟的 P95 毫秒值。 */
    private double percentile95RawMillis(List<Long> durations) {
        List<Long> sorted = durations.stream().sorted().toList();
        return milliseconds(sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1)));
    }

    /** 将纳秒换算为毫秒。 */
    private double milliseconds(long nanos) {
        return nanos / 1_000_000.0;
    }

    /** 将字节换算为 MiB。 */
    private double mebibytes(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    /** 一次操作的延迟、堆变化及当前线程分配量。 */
    private record Measurement<T>(T value, long elapsedNanos,
                                  long peakHeapIncreaseBytes, long threadAllocatedBytes) {
    }

    /** 并发限流的接受数、繁忙拒绝数和拒绝延迟。 */
    private record SaturationResult(int accepted, int busy, double busyP95Millis, boolean permitsReleased) {
    }
}
