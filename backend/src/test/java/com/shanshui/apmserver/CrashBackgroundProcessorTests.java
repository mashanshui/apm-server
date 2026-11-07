package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.application.*;
import com.shanshui.apmserver.crash.internal.domain.*;
import com.shanshui.apmserver.crash.internal.persistence.*;
import com.shanshui.apmserver.symbol.api.*;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 不启动调度线程，确定性验证单批资源、单条失败隔离和固定文件来源。 */
class CrashBackgroundProcessorTests {
    /** 单条确定性失败不能阻塞后续合法事件，也不能针对同 mapping 热重试。 */
    @Test
    void processesBoundedBatchWithOneMappingAndContinuesAfterFailure() throws Exception {
        // 使用当前存储参考实现；管理租约和文件边界由 Mock 精确控制。
        ObjectMapper mapper = new ObjectMapper();
        InMemoryCrashStateStore states = new InMemoryCrashStateStore(CrashTestSupport.storageProperties(), mapper);
        CrashSymbolicationJobs jobs = mock(CrashSymbolicationJobs.class);
        CrashSymbolicationPublications publications = mock(CrashSymbolicationPublications.class);
        SymbolRegistry symbols = mock(SymbolRegistry.class);
        SymbolFileLease file = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        UUID symbol = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var lease = new CrashSymbolicationJobs.Lease(app, "build", UUID.randomUUID(), 1, 1);
        when(jobs.claim(any(), any())).thenReturn(Optional.of(lease));
        when(jobs.renew(eq(lease), any())).thenReturn(true);
        when(symbols.acquire(app, "build")).thenReturn(Optional.of(file));
        when(file.symbolId()).thenReturn(symbol);
        when(file.revision()).thenReturn(1);
        when(file.sha256()).thenReturn("a".repeat(64));
        // 两条当前事件中第一条输出超限；第三条保持在下一批。
        for (int index = 0; index < 3; index++) {
            EventMetadata metadata = new EventMetadata(app, "com.example", "event-" + index, "crash", now.minusSeconds(10 - index),
                    now, 2, "session", "11111111-1111-4111-8111-111111111111", "device", "1", 1, "build", "production", "official", "16", "test", "wifi", Map.of(), Map.of());
            var raw = CrashTestSupport.crash("java.lang.RuntimeException", "test", 1, "a");
            CrashState pending = new CrashState(app, metadata.eventId(), "build", metadata.occurredAt(), 0, "pending", "mapping_missing", "raw_chain",
                    mapper.writeValueAsString(raw), "java.lang.RuntimeException", "tmp", "tmp-v1", "temporary", null, null, null, null, null);
            states.accept(new CrashFact(metadata, "jvm", true), pending);
        }
        when(symbols.retrace(eq(file), anyList())).thenReturn(SymbolicationResult.failed("output_limit"),
                new SymbolicationResult("java.lang.RuntimeException\n\tat com.example.Main.run(Main.java:1)", SymbolicationResult.Status.SYMBOLICATED, null));
        AtomicLong version = new AtomicLong();
        when(publications.failureVersion(eq(lease), anyString())).thenAnswer(call -> Optional.of(version.incrementAndGet()));
        when(publications.existing(eq(app), anyString())).thenReturn(Optional.empty());
        when(publications.register(eq(lease), any())).thenAnswer(call -> {
            CrashState computed = call.getArgument(1);
            CrashState fixed = CrashSymbolicationPublications.versioned(computed, version.incrementAndGet());
            return Optional.of(new CrashSymbolicationPublications.Publication(app, fixed.eventId(), "build", fixed.stateVersion(), "digest",
                    new CrashStateJson(mapper).encode(fixed), now.plusSeconds(3600)));
        });
        when(publications.publish(eq(lease), any())).thenAnswer(call -> {
            CrashSymbolicationPublications.Publication fixed = call.getArgument(1);
            states.replay(fixed.payload());
            return true;
        });
        CrashBackgroundProcessor processor = new CrashBackgroundProcessor(jobs, states, publications, symbols,
                new CrashFingerprintService(), new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 2);
        processor.runOnce();
        assertEquals("failed", states.current(app, "event-0").orElseThrow().status());
        assertEquals("output_limit", states.current(app, "event-0").orElseThrow().reason());
        assertEquals("symbolicated", states.current(app, "event-1").orElseThrow().status());
        assertEquals(symbol, states.current(app, "event-1").orElseThrow().symbolId());
        assertEquals("pending", states.current(app, "event-2").orElseThrow().status());
        assertEquals(1, states.candidates(app, "build", symbol, 1, now.minusSeconds(86400), now, 100).size());
        assertEquals(2, states.candidates(app, "build", UUID.randomUUID(), 1, now.minusSeconds(86400), now, 100).size());
        verify(symbols).acquire(app, "build");
        verify(symbols, times(2)).retrace(eq(file), anyList());
        verify(file).close();
        verify(jobs).release(lease, "queued", null, Duration.ZERO);
        // 大载荷触发响应预算后最多有限减半，不抬高资源预算或丢弃该事件。
        InMemoryCrashStateStore bounded = spy(states);
        doAnswer(call -> {
            int limit = call.getArgument(6);
            if (limit > 25) throw new QueryValidationException("QUERY_RESOURCE_LIMIT", "测试响应预算", 422);
            return call.callRealMethod();
        }).when(bounded).candidates(eq(app), eq("build"), eq(symbol), eq(1), any(), any(), anyInt());
        CrashBackgroundProcessor adaptive = new CrashBackgroundProcessor(jobs, bounded, publications, symbols,
                new CrashFingerprintService(), new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100);
        adaptive.runOnce();
        assertEquals("symbolicated", states.current(app, "event-2").orElseThrow().status());
        verify(bounded).candidates(eq(app), eq("build"), eq(symbol), eq(1), any(), any(), eq(100));
        verify(bounded).candidates(eq(app), eq("build"), eq(symbol), eq(1), any(), any(), eq(50));
        verify(bounded).candidates(eq(app), eq("build"), eq(symbol), eq(1), any(), any(), eq(25));
        // 正常停止后没有新领取或新计算，剩余 pending 仍可由下次进程恢复。
        processor.stop();
        processor.runOnce();
        adaptive.stop();
        adaptive.runOnce();
        verify(jobs, times(2)).claim(any(), any());
        assertThrows(IllegalArgumentException.class, () -> new CrashBackgroundProcessor(jobs, states, publications, symbols,
                new CrashFingerprintService(), new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 101));
    }

    /** 缺少 mapping 时不扫描堆栈，不在等待期间调用 R8。 */
    @Test
    void waitsWithoutMapping() {
        ObjectMapper mapper = new ObjectMapper();
        InMemoryCrashStateStore states = spy(new InMemoryCrashStateStore(CrashTestSupport.storageProperties(), mapper));
        CrashSymbolicationJobs jobs = mock(CrashSymbolicationJobs.class);
        CrashSymbolicationPublications publications = mock(CrashSymbolicationPublications.class);
        SymbolRegistry symbols = mock(SymbolRegistry.class);
        UUID app = UUID.randomUUID();
        var lease = new CrashSymbolicationJobs.Lease(app, "build", UUID.randomUUID(), 1, 1);
        when(jobs.claim(any(), any())).thenReturn(Optional.of(lease));
        when(symbols.acquire(app, "build")).thenReturn(Optional.empty());
        CrashBackgroundProcessor processor = new CrashBackgroundProcessor(jobs, states, publications, symbols,
                new CrashFingerprintService(), new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100);
        processor.runOnce();
        verify(jobs).release(lease, "waiting_mapping", "mapping_missing", Duration.ZERO);
        verify(states, never()).candidates(any(), any(), any(), anyInt(), any(), any(), anyInt());
        verify(symbols, never()).retrace(any(), any());
    }
    /** 并发调度调用在实例门禁串行领取；租约过期后不得启动 R8。 */
    @Test
    void serializesConcurrentBatchesAndStopsAfterLeaseLoss() throws Exception {
        var mapper = new ObjectMapper();
        var states = mock(com.shanshui.apmserver.crash.internal.port.CrashStatePort.class);
        var jobs = mock(CrashSymbolicationJobs.class);
        var publications = mock(CrashSymbolicationPublications.class);
        var symbols = mock(SymbolRegistry.class);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var secondStarted = new java.util.concurrent.CountDownLatch(1);
        when(jobs.claim(any(), any())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return Optional.empty();
        });
        var processor = new CrashBackgroundProcessor(jobs, states, publications, symbols, new CrashFingerprintService(),
                new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(processor::runOnce);
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var second = executor.submit(() -> { secondStarted.countDown(); processor.runOnce(); });
            assertTrue(secondStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            verify(jobs, times(1)).claim(any(), any());
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS); second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(jobs, times(2)).claim(any(), any());
        UUID app = UUID.randomUUID();
        var lease = new CrashSymbolicationJobs.Lease(app, "build", UUID.randomUUID(), 1, 1);
        var mapping = mock(SymbolFileLease.class);
        when(mapping.symbolId()).thenReturn(UUID.randomUUID());
        when(mapping.revision()).thenReturn(1);
        when(symbols.acquire(app, "build")).thenReturn(Optional.of(mapping));
        when(jobs.claim(any(), any())).thenReturn(Optional.of(lease));
        when(jobs.renew(eq(lease), any())).thenReturn(false);
        var pending = new CrashState(app, "event", "build", Instant.now().minusSeconds(1), 0, "pending", "awaiting_processing", "raw_chain", "{}",
                "Exception", "tmp", "tmp-v1", "temporary", null, null, null, null, null);
        when(states.candidates(eq(app), eq("build"), any(), eq(1), any(), any(), eq(100)))
                .thenReturn(java.util.List.of(new CrashWorkItem(pending, "com.example")));
        processor.runOnce();
        verify(symbols, never()).retrace(any(), anyList());
        verify(publications, never()).register(any(), any());
        verify(mapping).close();
    }

}
