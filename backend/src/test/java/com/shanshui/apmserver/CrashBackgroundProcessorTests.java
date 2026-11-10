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
            String auxiliary = index == 0 ? mapper.writeValueAsString(java.util.List.of(
                    new com.shanshui.apmserver.crash.api.CrashThreadStack("1", "pending", null,
                            new com.shanshui.apmserver.crash.api.CrashThreadStack.Stack("raw_frames", java.util.List.of(
                                    new com.shanshui.apmserver.telemetry.api.StackFrame("a.A", "run", "A.java", 1, true))),
                            null, null, null, null))) : "";
            CrashState pending = new CrashState(app, metadata.eventId(), "build", metadata.occurredAt(), 0, "pending", "mapping_missing", "raw_chain",
                    mapper.writeValueAsString(raw), "java.lang.RuntimeException", "tmp", "tmp-v1", "temporary", null, null, null, null, null,
                    "", auxiliary);
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
        assertTrue(states.current(app, "event-0").orElseThrow().threadStacksPayload().contains("raw_frames"));
        assertEquals("symbolicated", states.current(app, "event-1").orElseThrow().status());
        assertEquals(symbol, states.current(app, "event-1").orElseThrow().symbolId());
        assertEquals("pending", states.current(app, "event-2").orElseThrow().status());
        assertEquals(1, states.candidates(app, "build", symbol, 1, now.minusSeconds(86400), now, 100).size());
        assertEquals(2, states.candidates(app, "build", UUID.randomUUID(), 1, now.minusSeconds(86400), now, 100).size());
        verify(symbols).acquire(app, "build");
        verify(symbols, times(2)).retrace(eq(file), anyList());
        verify(symbols, never()).retraceAuxiliary(any(), anyList(), anyLong(), any());
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

    /** mapping 的存在、替换、删除与后台重启都不能修改同构建 Native 现场。 */
    @Test
    void ignoresNativeAcrossMappingChangesAndRestarts() throws Exception {
        var mapper = new ObjectMapper();
        var states = new InMemoryCrashStateStore(CrashTestSupport.storageProperties(), mapper);
        var jobs = mock(CrashSymbolicationJobs.class);
        var publications = mock(CrashSymbolicationPublications.class);
        var symbols = mock(SymbolRegistry.class);
        var firstMapping = mock(SymbolFileLease.class);
        var replacement = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var metadata = new EventMetadata(app, "com.example", "native", "crash", now.minusSeconds(1), now, 2,
                "session", CrashTestSupport.PROCESS_ID, "device", "1", 1, "build", "test", "test", "35", "test",
                "wifi", Map.of(), Map.of());
        var nativeState = new CrashState(app, "native", "build", metadata.occurredAt(), 0, "pending",
                "native_symbolication_deferred", "raw_native", "{}", "SIGSEGV", "fp", "native-tmp-v1",
                "temporary", null, null, null, null, null, "a".repeat(64), "{}");
        states.accept(new CrashFact(metadata, "native", true, "{}", "a".repeat(64)), nativeState);
        var lease = new CrashSymbolicationJobs.Lease(app, "build", UUID.randomUUID(), 1, 1);
        when(jobs.claim(any(), any())).thenReturn(Optional.of(lease));
        when(symbols.acquire(app, "build")).thenReturn(Optional.of(firstMapping), Optional.of(replacement),
                Optional.empty(), Optional.of(replacement));
        when(firstMapping.symbolId()).thenReturn(UUID.randomUUID());
        when(replacement.symbolId()).thenReturn(UUID.randomUUID());
        when(firstMapping.revision()).thenReturn(1);
        when(replacement.revision()).thenReturn(2);
        var processor = new CrashBackgroundProcessor(jobs, states, publications, symbols, new CrashFingerprintService(),
                new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100);
        processor.runOnce();
        processor.runOnce();
        processor.runOnce();
        processor.stop();
        var restarted = new CrashBackgroundProcessor(jobs, states, publications, symbols, new CrashFingerprintService(),
                new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100);
        restarted.runOnce();
        // 即使存储适配器错误返回 Native 候选，后台入口也必须直接跳过。
        var incorrectStore = spy(states);
        doReturn(java.util.List.of(new CrashWorkItem(nativeState, metadata.packageName())))
                .when(incorrectStore).candidates(eq(app), eq("build"), any(), anyInt(), any(), any(), anyInt());
        var guarded = new CrashBackgroundProcessor(jobs, incorrectStore, publications, symbols, new CrashFingerprintService(),
                new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100);
        guarded.runOnce();
        assertEquals(nativeState, states.current(app, "native").orElseThrow());
        verify(symbols, never()).retrace(any(), anyList());
        verify(symbols, never()).retraceAuxiliary(any(), anyList(), anyLong(), any());
        verify(publications, never()).register(any(), any());
        verify(publications, never()).failureVersion(any(), anyString());
        verify(publications, never()).existing(any(), anyString());
    }

    /** 主异常相同时辅助线程内容不同，正式指纹仍一致且各线程状态独立固定。 */
    @Test
    void symbolicatesAuxiliaryStacksWithoutChangingMainFingerprint() {
        var mapper = new ObjectMapper();
        var states = new InMemoryCrashStateStore(CrashTestSupport.storageProperties(), mapper);
        var jobs = mock(CrashSymbolicationJobs.class);
        var publications = mock(CrashSymbolicationPublications.class);
        var symbols = mock(SymbolRegistry.class);
        var mapping = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        UUID symbol = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var lease = new CrashSymbolicationJobs.Lease(app, "build", UUID.randomUUID(), 1, 1);
        when(jobs.claim(any(), any())).thenReturn(Optional.of(lease));
        when(jobs.renew(eq(lease), any())).thenReturn(true);
        when(symbols.acquire(app, "build")).thenReturn(Optional.of(mapping));
        when(mapping.symbolId()).thenReturn(symbol);
        when(mapping.revision()).thenReturn(1);
        when(mapping.sha256()).thenReturn("a".repeat(64));
        for (int index = 0; index < 2; index++) {
            String eventId = "aux-" + index;
            EventMetadata metadata = new EventMetadata(app, "com.example", eventId, "crash", now.minusSeconds(10 - index),
                    now, 2, "session", CrashTestSupport.PROCESS_ID, "device", "1", 1, "build", "production", "official",
                    "16", "test", "wifi", Map.of(), Map.of());
            var frame = new com.shanshui.apmserver.telemetry.api.StackFrame("a.A", "run" + index, "A.java", 1, true);
            var thread = new com.shanshui.apmserver.crash.api.CrashThreadStack("1", "pending", null,
                    new com.shanshui.apmserver.crash.api.CrashThreadStack.Stack("raw_frames", java.util.List.of(frame)), null, null, null, null);
            var empty = new com.shanshui.apmserver.crash.api.CrashThreadStack("2", "not_applicable", null,
                    new com.shanshui.apmserver.crash.api.CrashThreadStack.Stack("raw_frames", java.util.List.of()), null, null, null, null);
            String digest = (index == 0 ? "b" : "c").repeat(64);
            CrashState pending = new CrashState(app, eventId, "build", metadata.occurredAt(), 0, "pending", "mapping_missing", "raw_chain",
                    mapper.writeValueAsString(CrashTestSupport.crash("java.lang.RuntimeException", "same", 1, "a")),
                    "java.lang.RuntimeException", "tmp", "tmp-v1", "temporary", null, null, null, null, null,
                    digest, mapper.writeValueAsString(java.util.List.of(thread, empty)));
            states.accept(new CrashFact(metadata, "jvm", true, "{}", digest), pending);
        }
        when(symbols.retrace(eq(mapping), anyList())).thenReturn(new SymbolicationResult(
                "java.lang.RuntimeException\n\tat com.example.Main.run(Main.java:1)", SymbolicationResult.Status.SYMBOLICATED, null));
        when(symbols.retraceAuxiliary(eq(mapping), anyList(), eq(32768L), any())).thenReturn(
                new SymbolicationResult("\tat com.example.Worker.run(Worker.java:8)\n\tat com.example.Inline.call(Inline.java:2)",
                        SymbolicationResult.Status.SYMBOLICATED, null), SymbolicationResult.failed("timeout"));
        when(publications.existing(eq(app), anyString())).thenReturn(Optional.empty());
        AtomicLong version = new AtomicLong();
        when(publications.register(eq(lease), any())).thenAnswer(call -> {
            CrashState fixed = CrashSymbolicationPublications.versioned(call.getArgument(1), version.incrementAndGet());
            return Optional.of(new CrashSymbolicationPublications.Publication(app, fixed.eventId(), "build", fixed.stateVersion(), "digest",
                    new CrashStateJson(mapper).encode(fixed), now.plusSeconds(3600)));
        });
        when(publications.publish(eq(lease), any())).thenAnswer(call -> {
            CrashSymbolicationPublications.Publication fixed = call.getArgument(1);
            states.replay(fixed.payload());
            return true;
        });
        var processor = new CrashBackgroundProcessor(jobs, states, publications, symbols, new CrashFingerprintService(),
                new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100);
        processor.runOnce();
        CrashState first = states.current(app, "aux-0").orElseThrow();
        CrashState second = states.current(app, "aux-1").orElseThrow();
        assertEquals("symbolicated", first.status());
        assertEquals("symbolicated", second.status());
        assertEquals(first.fingerprint(), second.fingerprint());
        var firstThreads = mapper.readValue(first.threadStacksPayload(), com.shanshui.apmserver.crash.api.CrashThreadStack[].class);
        var secondThreads = mapper.readValue(second.threadStacksPayload(), com.shanshui.apmserver.crash.api.CrashThreadStack[].class);
        assertEquals("symbolicated", firstThreads[0].symbolicationStatus());
        assertEquals(symbol, firstThreads[0].symbolFileId());
        assertEquals("not_applicable", firstThreads[1].symbolicationStatus());
        assertEquals("failed", secondThreads[0].symbolicationStatus());
        assertEquals("timeout", secondThreads[0].symbolicationReason());
        assertEquals("raw_frames", secondThreads[0].stack().format());
        assertTrue(states.candidates(app, "build", UUID.randomUUID(), 1, now.minusSeconds(86400), now, 100).isEmpty());
    }

    /** 发布登记超限时回退辅助还原，主异常成功结果与指纹仍可发布。 */
    @Test
    void fallsBackToRawAuxiliaryFramesWhenPublicationIsTooLarge() {
        var mapper = new ObjectMapper();
        var states = new InMemoryCrashStateStore(CrashTestSupport.storageProperties(), mapper);
        var jobs = mock(CrashSymbolicationJobs.class);
        var publications = mock(CrashSymbolicationPublications.class);
        var symbols = mock(SymbolRegistry.class);
        var mapping = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        UUID symbol = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var lease = new CrashSymbolicationJobs.Lease(app, "build", UUID.randomUUID(), 1, 1);
        when(jobs.claim(any(), any())).thenReturn(Optional.of(lease));
        when(jobs.renew(eq(lease), any())).thenReturn(true);
        when(symbols.acquire(app, "build")).thenReturn(Optional.of(mapping));
        when(mapping.symbolId()).thenReturn(symbol);
        when(mapping.revision()).thenReturn(1);
        when(mapping.sha256()).thenReturn("a".repeat(64));
        var metadata = new EventMetadata(app, "com.example", "fallback", "crash", now.minusSeconds(1), now, 2,
                "session", CrashTestSupport.PROCESS_ID, "device", "1", 1, "build", "test", "test", "35", "test", null, Map.of(), Map.of());
        var frame = new com.shanshui.apmserver.telemetry.api.StackFrame("a.A", "run", "A.java", 1, true);
        var thread = new com.shanshui.apmserver.crash.api.CrashThreadStack("1", "pending", null,
                new com.shanshui.apmserver.crash.api.CrashThreadStack.Stack("raw_frames", java.util.List.of(frame)), null, null, null, null);
        String digest = "b".repeat(64);
        var pending = new CrashState(app, "fallback", "build", metadata.occurredAt(), 0, "pending", "awaiting_processing", "raw_chain",
                mapper.writeValueAsString(CrashTestSupport.crash("java.lang.RuntimeException", "same", 1, "a")),
                "java.lang.RuntimeException", "tmp", "tmp-v1", "temporary", null, null, null, null, null,
                digest, mapper.writeValueAsString(java.util.List.of(thread)));
        states.accept(new CrashFact(metadata, "jvm", true, "{}", digest), pending);
        when(symbols.retrace(eq(mapping), anyList())).thenReturn(new SymbolicationResult(
                "java.lang.RuntimeException\n\tat com.example.Main.run(Main.java:1)", SymbolicationResult.Status.SYMBOLICATED, null));
        when(symbols.retraceAuxiliary(eq(mapping), anyList(), anyLong(), any())).thenReturn(new SymbolicationResult(
                "\tat com.example.Worker.run(Worker.java:8)", SymbolicationResult.Status.SYMBOLICATED, null));
        when(publications.existing(eq(app), anyString())).thenReturn(Optional.empty());
        AtomicLong attempts = new AtomicLong();
        when(publications.register(eq(lease), any())).thenAnswer(call -> {
            if (attempts.incrementAndGet() == 1) throw new CrashResultTooLargeException();
            CrashState fixed = CrashSymbolicationPublications.versioned(call.getArgument(1), 1);
            return Optional.of(new CrashSymbolicationPublications.Publication(app, fixed.eventId(), "build", 1, "digest",
                    new CrashStateJson(mapper).encode(fixed), now.plusSeconds(3600)));
        });
        when(publications.publish(eq(lease), any())).thenAnswer(call -> {
            CrashSymbolicationPublications.Publication fixed = call.getArgument(1);
            states.replay(fixed.payload());
            return true;
        });
        new CrashBackgroundProcessor(jobs, states, publications, symbols, new CrashFingerprintService(),
                new CrashSanitizer(CrashTestSupport.ingestProperties()), mapper, 100).runOnce();
        var result = states.current(app, "fallback").orElseThrow();
        assertEquals(2, attempts.get());
        assertEquals("symbolicated", result.status());
        assertEquals("resolved-v1", result.fingerprintVersion());
        assertTrue(result.stackPayload().contains("com.example.Main.run"));
        var restored = mapper.readValue(result.threadStacksPayload(), com.shanshui.apmserver.crash.api.CrashThreadStack[].class);
        assertEquals("failed", restored[0].symbolicationStatus());
        assertEquals("output_limit", restored[0].symbolicationReason());
        assertEquals("raw_frames", restored[0].stack().format());
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
