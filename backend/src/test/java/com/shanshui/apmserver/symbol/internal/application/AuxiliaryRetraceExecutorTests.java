package com.shanshui.apmserver.symbol.internal.application;

import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.symbol.internal.config.SymbolProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 验证辅助还原的唯一工作者、共享许可与超时后文件保活。 */
class AuxiliaryRetraceExecutorTests {
    /** 忽略中断的 R8 不会产生替代线程，超时后仍保留原 mapping 引用。 */
    @Test
    void timeoutKeepsOriginalMappingUntilWorkerActuallyStops() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var retainedClosed = new CountDownLatch(1);
        var observedPath = new AtomicReference<Path>();
        var original = new FakeLease(new AtomicInteger(1), retainedClosed, Path.of("old.mapping"), false);
        var executor = new AuxiliaryRetraceExecutor(limiter(), (path, lines, maxBytes) -> {
            observedPath.set(path);
            entered.countDown();
            boolean waiting = true;
            while (waiting) {
                try { release.await(); waiting = false; }
                catch (InterruptedException ignored) { /* 模拟 R8 暂时忽略取消。 */ }
            }
            return new SymbolicationResult("at A.run(A.java:1)", SymbolicationResult.Status.SYMBOLICATED, null);
        });
        var caller = Executors.newSingleThreadExecutor();
        var otherExecutor = new AuxiliaryRetraceExecutor(limiter(), (path, lines, max) -> {
            throw new AssertionError("全进程不能创建第二个在途辅助工作");
        });
        try {
            var timed = caller.submit(() -> executor.retrace(original, List.of("at a.a(A.java:1)"), 32768, Duration.ofMillis(100)));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var result = timed.get(1, TimeUnit.SECONDS);
            assertEquals("timeout", result.reason());
            original.close();
            assertEquals(1, original.references.get());
            assertEquals("parser_busy", executor.retrace(original, List.of("at b.b(B.java:1)"), 100, Duration.ofSeconds(1)).reason());
            assertEquals("parser_busy", otherExecutor.retrace(original, List.of("at c.c(C.java:1)"), 100, Duration.ofSeconds(1)).reason());
            assertEquals(Path.of("old.mapping"), observedPath.get());
            release.countDown();
            assertTrue(retainedClosed.await(1, TimeUnit.SECONDS));
            assertEquals(0, original.references.get());
            assertEquals("timeout", result.reason());
        } finally {
            release.countDown();
            executor.close();
            otherExecutor.close();
            caller.shutdownNow();
        }
    }

    /** 共享许可满载立即失败；释放后完整输出成功，超限输出不能伪装成功。 */
    @Test
    void sharesPermitAndRejectsOversizedOutput() throws Exception {
        var limiter = limiter();
        var lease = new FakeLease(new AtomicInteger(1), new CountDownLatch(1), Path.of("fixed.mapping"), false);
        var executor = new AuxiliaryRetraceExecutor(limiter,
                (path, lines, max) -> new SymbolicationResult("full result", SymbolicationResult.Status.SYMBOLICATED, null));
        try {
            try (var permit = limiter.acquire()) {
                assertEquals("parser_busy", executor.retrace(lease, List.of("at a.a(A.java:1)"), 100, Duration.ofSeconds(1)).reason());
            }
            assertEquals(SymbolicationResult.Status.SYMBOLICATED,
                    executor.retrace(lease, List.of("at a.a(A.java:1)"), 100, Duration.ofSeconds(1)).status());
            assertEquals("output_limit", executor.retrace(lease, List.of("at a.a(A.java:1)"), 4, Duration.ofSeconds(1)).reason());
        } finally {
            executor.close();
            lease.close();
        }
    }

    /** 只配置一个共享许可，覆盖资源饱和边界。 */
    private SymbolOperationLimiter limiter() {
        var properties = new SymbolProperties();
        properties.setMaxConcurrentOperations(1);
        return new SymbolOperationLimiter(properties);
    }

    /** 独立引用共享同一固定路径、版本和引用计数。 */
    private static final class FakeLease implements SymbolFileLease {
        /** 文件引用计数。 */ final AtomicInteger references;
        /** 独立引用释放通知。 */ final CountDownLatch retainedClosed;
        /** 固定文件路径。 */ final Path path;
        /** 此实例是否为派生引用。 */ final boolean retained;
        /** 防止重复释放。 */ boolean closed;
        /** 固定符号表身份。 */ final UUID id;

        /** 创建合成引用。 */
        FakeLease(AtomicInteger references, CountDownLatch retainedClosed, Path path, boolean retained) {
            this(references, retainedClosed, path, retained, UUID.randomUUID());
        }
        /** 创建同一身份的独立引用。 */
        FakeLease(AtomicInteger references, CountDownLatch retainedClosed, Path path, boolean retained, UUID id) {
            this.references = references; this.retainedClosed = retainedClosed; this.path = path; this.retained = retained; this.id = id;
        }
        /** 固定符号身份。 */ @Override public UUID symbolId() { return id; }
        /** 固定版本。 */ @Override public int revision() { return 1; }
        /** 固定摘要。 */ @Override public String sha256() { return "a".repeat(64); }
        /** 固定路径。 */ @Override public Path path() { return path; }
        /** 增加独立引用。 */
        @Override public synchronized SymbolFileLease retain() {
            if (closed) throw new IllegalStateException();
            references.incrementAndGet();
            return new FakeLease(references, retainedClosed, path, true, id);
        }
        /** 引用实际释放后通知测试。 */
        @Override public synchronized void close() {
            if (!closed) {
                closed = true;
                references.decrementAndGet();
                if (retained) retainedClosed.countDown();
            }
        }
    }
}
