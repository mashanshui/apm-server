package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.symbol.api.NativeSymbolicationResult;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证跨线程帧可按精确模块合并，物理帧与成功来源保持不变。 */
class NativeFrameBatchTests {
    /** 两个模块及重复地址按每个物理帧累计字节，超限后退避并在下一轮补齐。 */
    @Test
    void limitsNewNativeOutputAcrossModulesAndResumesWithoutChangingSuccess() {
        var registry = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(UUID.randomUUID());
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireNative(eq(app), eq("1"), eq("arm64-v8a"), anyString())).thenReturn(Optional.of(lease));
        /** 相同地址在主辅线程重复出现，工具只解析一次但每个物理帧都有独立输出。 */
        String function = "函".repeat(5_000);
        var item = new NativeSymbolicationResult.Frame(List.of(new NativeSymbolicationResult.Inline(function, "a.cc", 1, null)), null);
        when(registry.symbolizeNative(eq(lease), anyList(), any())).thenReturn(new NativeSymbolicationResult(Map.of("0x1", item), null));
        var pending = new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var mainFrames = java.util.stream.IntStream.range(0, 20).mapToObj(i -> new NativeStack.Frame(i, "m1", "0x1", null, null, pending)).toList();
        var otherFrames = java.util.stream.IntStream.range(0, 20).mapToObj(i -> new NativeStack.Frame(i, "m2", "0x1", null, null, pending)).toList();
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null,
                List.of(new NativeCrash.Module("m1", "one.so", null, "arm64-v8a", "abcd"),
                        new NativeCrash.Module("m2", "two.so", null, "arm64-v8a", "dcba")),
                new NativeStack.Backtrace("ok", null, false, 1L, mainFrames), null);
        var auxiliary = new com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks(List.of(), List.of(
                new com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks.NativeThread(2,
                        new NativeStack.Backtrace("ok", null, false, 1L, otherFrames))));
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var first = new NativeFrameBatch(registry, () -> 0L, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC))
                .resolveStacks(app, "1", main, auxiliary, Duration.ofSeconds(2));
        var actual = new java.util.ArrayList<>(first.main().backtrace().frames());
        actual.addAll(first.auxiliary().nativeThreads().getFirst().backtrace().frames());
        long succeeded = actual.stream().filter(frame -> "symbolicated".equals(frame.resolution().status())).count();
        long deferred = actual.stream().filter(frame -> "native_output_limit".equals(frame.resolution().reason())
                && now.plusSeconds(30).equals(frame.resolution().retryAt())).count();
        assertTrue(succeeded > 20 && deferred > 0);
        assertEquals(40, succeeded + deferred);
        int bytes = actual.stream().filter(frame -> "symbolicated".equals(frame.resolution().status()))
                .mapToInt(frame -> new tools.jackson.databind.ObjectMapper().writeValueAsBytes(frame.resolution()).length).sum();
        assertTrue(bytes <= 512 * 1024);
        var second = new NativeFrameBatch(registry, () -> 0L, java.time.Clock.fixed(now.plusSeconds(31), java.time.ZoneOffset.UTC))
                .resolveStacks(app, "1", first.main(), first.auxiliary(), Duration.ofSeconds(2));
        assertEquals(first.main().backtrace(), second.main().backtrace());
        var completed = second.auxiliary().nativeThreads().getFirst().backtrace().frames();
        assertTrue(completed.stream().allMatch(frame -> "symbolicated".equals(frame.resolution().status())));
        for (int i = 0; i < otherFrames.size(); i++) {
            var previous = first.auxiliary().nativeThreads().getFirst().backtrace().frames().get(i);
            if ("symbolicated".equals(previous.resolution().status())) assertEquals(previous, completed.get(i));
        }
    }
    /** 到期单元按上次尝试时间轮转，不固定从前部模块开始。 */
    @Test
    void rotatesDueModulesByOldestAttempt() {
        var registry = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        var ticker = new java.util.concurrent.atomic.AtomicLong();
        var clock = java.time.Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        UUID app = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(UUID.randomUUID());
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireNative(eq(app), eq("1"), eq("arm64-v8a"), anyString())).thenReturn(Optional.of(lease));
        when(registry.symbolizeNative(eq(lease), anyList(), any())).thenAnswer(call -> {
            ticker.addAndGet(Duration.ofSeconds(2).toNanos());
            return new NativeSymbolicationResult(Map.of(), "native_timeout");
        });
        var newer = new NativeStack.Resolution("pending", "native_timeout", null, null, null,
                Instant.parse("2025-12-31T23:59:20Z"), Instant.parse("2025-12-31T23:59:50Z"));
        var older = new NativeStack.Resolution("pending", "native_timeout", null, null, null,
                Instant.parse("2025-12-31T23:59:00Z"), Instant.parse("2025-12-31T23:59:30Z"));
        var frames = List.of(new NativeStack.Frame(0, "newer", "0x1", null, null, newer),
                new NativeStack.Frame(1, "older", "0x2", null, null, older));
        var modules = List.of(new NativeCrash.Module("newer", "one.so", null, "arm64-v8a", "abcd"),
                new NativeCrash.Module("older", "two.so", null, "arm64-v8a", "dcba"));
        var result = new NativeFrameBatch(registry, ticker::get, clock).resolve(app, "1", modules, frames, Duration.ofSeconds(2));
        assertEquals(frames.getFirst(), result.getFirst());
        assertEquals(clock.instant(), result.get(1).resolution().processedAt());
        verify(registry).symbolizeNative(eq(lease), eq(List.of("0x2")), any());
    }

    /** 前部慢模块已尝试时，后部未尝试模块优先使用有限预算。 */
    @Test
    void prioritizesUntriedModuleWhenPreviousModuleWasSlow() {
        var registry = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        var ticker = new java.util.concurrent.atomic.AtomicLong();
        var clock = java.time.Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        UUID app = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(UUID.randomUUID());
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireNative(eq(app), eq("1"), eq("arm64-v8a"), anyString())).thenReturn(Optional.of(lease));
        when(registry.symbolizeNative(eq(lease), anyList(), any())).thenAnswer(call -> {
            ticker.addAndGet(Duration.ofSeconds(2).toNanos());
            return new NativeSymbolicationResult(Map.of(), "native_timeout");
        });
        var old = new NativeStack.Resolution("pending", "native_timeout", null, null, null,
                Instant.parse("2025-12-31T23:59:00Z"), Instant.parse("2025-12-31T23:59:30Z"));
        var untouched = new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var frames = List.of(new NativeStack.Frame(0, "first", "0x1", null, null, old),
                new NativeStack.Frame(1, "later", "0x2", null, null, untouched));
        var modules = List.of(new NativeCrash.Module("first", "one.so", null, "arm64-v8a", "abcd"),
                new NativeCrash.Module("later", "two.so", null, "arm64-v8a", "dcba"));
        var result = new NativeFrameBatch(registry, ticker::get, clock).resolve(app, "1", modules, frames, Duration.ofSeconds(2));
        assertEquals(frames.getFirst(), result.getFirst());
        assertEquals("native_timeout", result.get(1).resolution().reason());
        verify(registry).symbolizeNative(eq(lease), eq(List.of("0x2")), any());
        verify(registry, never()).acquireNative(app, "1", "arm64-v8a", "abcd");
    }

    /** 首次仅函数名成功后，即使来源删除并重传更丰富文件也不重算。 */
    @Test
    void neverUpgradesSuccessfulFramesAfterSourceReplacement() {
        var registry = mock(SymbolRegistry.class);
        var source = new NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 1, "a".repeat(64));
        var resolution = new NativeStack.Resolution("symbolicated", null,
                List.of(new NativeStack.Inline("function", null, null, null)), source, source, Instant.now(), null);
        var frame = new NativeStack.Frame(0, "m", "0x1", null, null, resolution);
        var module = new NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd");
        var batch = new NativeFrameBatch(registry);
        assertEquals(List.of(frame), batch.resolve(UUID.randomUUID(), "1", List.of(module), List.of(frame), Duration.ofSeconds(2)));
        verifyNoInteractions(registry);
    }

    /** 工具或存储临时故障进入退避，退避期不重新启动工具。 */
    @Test
    void backsOffTransientFailures() {
        var registry = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(UUID.randomUUID());
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireNative(app, "1", "arm64-v8a", "abcd")).thenReturn(Optional.of(lease));
        when(registry.symbolizeNative(eq(lease), anyList(), any())).thenReturn(new NativeSymbolicationResult(Map.of(), "native_timeout"));
        var module = new NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd");
        var pending = new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var frame = new NativeStack.Frame(0, "m", "0x1", null, null, pending);
        var batch = new NativeFrameBatch(registry);
        var delayed = batch.resolve(app, "1", List.of(module), List.of(frame), Duration.ofSeconds(2));
        assertEquals("native_timeout", delayed.getFirst().resolution().reason());
        assertTrue(delayed.getFirst().resolution().retryAt().isAfter(Instant.now().plusSeconds(25)));
        assertEquals(delayed, batch.resolve(app, "1", List.of(module), delayed, Duration.ofSeconds(2)));
        verify(registry, times(1)).symbolizeNative(eq(lease), anyList(), any());
        when(registry.acquireNative(app, "1", "arm64-v8a", "abcd"))
                .thenThrow(new com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException("unavailable"));
        var unavailable = batch.resolve(app, "1", List.of(module), List.of(frame), Duration.ofSeconds(2));
        assertEquals("native_store_unavailable", unavailable.getFirst().resolution().reason());
        assertTrue(unavailable.getFirst().resolution().retryAt().isAfter(Instant.now().plusSeconds(25)));
    }

    /** 不同完整 Build ID 分别批处理，空输入不启动工具。 */
    @Test
    void splitsDistinctModuleIdentitiesAndSkipsEmptyInput() {
        var registry = mock(SymbolRegistry.class);
        var one = mock(SymbolFileLease.class);
        var two = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        for (var lease : List.of(one, two)) {
            when(lease.symbolId()).thenReturn(UUID.randomUUID());
            when(lease.revision()).thenReturn(1);
            when(lease.sha256()).thenReturn("a".repeat(64));
        }
        when(registry.acquireNative(app, "1", "arm64-v8a", "abcd")).thenReturn(Optional.of(one));
        when(registry.acquireNative(app, "1", "arm64-v8a", "dcba")).thenReturn(Optional.of(two));
        var resolved = new NativeSymbolicationResult.Frame(List.of(new NativeSymbolicationResult.Inline("f", null, null, null)), null);
        when(registry.symbolizeNative(eq(one), eq(List.of("0x1")), any())).thenReturn(new NativeSymbolicationResult(Map.of("0x1", resolved), null));
        when(registry.symbolizeNative(eq(two), eq(List.of("0x2")), any())).thenReturn(new NativeSymbolicationResult(Map.of("0x2", resolved), null));
        var modules = List.of(new NativeCrash.Module("one", "lib.so", null, "arm64-v8a", "abcd"),
                new NativeCrash.Module("two", "lib.so", null, "arm64-v8a", "dcba"));
        var pending = new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var frames = List.of(new NativeStack.Frame(0, "one", "0x1", null, null, pending),
                new NativeStack.Frame(1, "two", "0x2", null, null, pending));
        var batch = new NativeFrameBatch(registry);
        assertTrue(batch.resolve(app, "1", modules, List.of(), Duration.ofSeconds(2)).isEmpty());
        assertEquals(List.of("symbolicated", "symbolicated"), batch.resolve(app, "1", modules, frames, Duration.ofSeconds(2))
                .stream().map(frame -> frame.resolution().status()).toList());
        verify(registry).symbolizeNative(eq(one), eq(List.of("0x1")), any());
        verify(registry).symbolizeNative(eq(two), eq(List.of("0x2")), any());
    }

    /** 确定性失败只在 SO 修订变化后重试，其他模块不得回退到不匹配文件。 */
    @Test
    void retriesOnlyAfterExactSourceChanges() {
        var registry = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        var app = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(id);
        when(lease.revision()).thenReturn(1, 2);
        when(lease.sha256()).thenReturn("a".repeat(64), "b".repeat(64));
        when(registry.acquireNative(app, "1", "arm64-v8a", "abcd")).thenReturn(Optional.of(lease));
        when(registry.acquireNative(app, "1", "arm64-v8a", "dcba")).thenReturn(Optional.empty());
        when(registry.symbolizeNative(eq(lease), eq(List.of("0x1")), any())).thenReturn(new NativeSymbolicationResult(
                Map.of("0x1", new NativeSymbolicationResult.Frame(List.of(new NativeSymbolicationResult.Inline("f", null, null, null)), null)), null));
        var source = new NativeStack.Source("NATIVE_SO", id, 1, "a".repeat(64));
        var failed = new NativeStack.Resolution("failed", "native_no_function", null, null, source, Instant.now(), null);
        var pending = new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var frames = List.of(new NativeStack.Frame(0, "m1", "0x1", null, null, failed),
                new NativeStack.Frame(1, "m2", "0x2", null, null, pending));
        var modules = List.of(new NativeCrash.Module("m1", "lib.so", null, "arm64-v8a", "abcd"),
                new NativeCrash.Module("m2", "lib.so", null, "arm64-v8a", "dcba"));
        var batch = new NativeFrameBatch(registry);
        assertEquals(frames, batch.resolve(app, "1", modules, frames, Duration.ofSeconds(2)));
        verify(registry, never()).symbolizeNative(any(), anyList(), any());
        var result = batch.resolve(app, "1", modules, frames, Duration.ofSeconds(2));
        assertEquals("symbolicated", result.getFirst().resolution().status());
        assertEquals(2, result.getFirst().resolution().source().revision());
        assertEquals(frames.get(1), result.get(1));
    }

    /** 重复地址只处理一次，已成功帧及未知身份不参与调用。 */
    @Test
    void batchesMatchingModulesAndKeepsPhysicalFrames() throws Exception {
        var registry = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        var app = UUID.randomUUID();
        var id = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(id);
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireNative(app, "1", "arm64-v8a", "abcd")).thenReturn(Optional.of(lease));
        when(registry.symbolizeNative(eq(lease), eq(List.of("0x1")), any())).thenReturn(new NativeSymbolicationResult(
                Map.of("0x1", new NativeSymbolicationResult.Frame(List.of(
                        new NativeSymbolicationResult.Inline("inner", "a.cc", 1, null),
                        new NativeSymbolicationResult.Inline("outer", "a.cc", 2, null)), null)), null));
        var pending = new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var source = new NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 3, "b".repeat(64));
        var done = new NativeStack.Resolution("symbolicated", null, List.of(new NativeStack.Inline("old", null, null, null)),
                source, source, Instant.parse("2026-01-01T00:00:00Z"), null);
        var unknown = new NativeStack.Resolution("not_applicable", "missing_module", null, null, null, null, null);
        var frames = List.of(new NativeStack.Frame(0, "m1", "0x1", null, null, pending),
                new NativeStack.Frame(0, "m2", "0x1", null, null, pending),
                new NativeStack.Frame(1, "m1", "0x2", null, null, done),
                new NativeStack.Frame(2, null, null, null, null, unknown));
        var modules = List.of(new NativeCrash.Module("m1", "one.so", null, "arm64-v8a", "abcd"),
                new NativeCrash.Module("m2", "two.so", null, "arm64-v8a", "abcd"));
        var result = new NativeFrameBatch(registry).resolve(app, "1", modules, frames, Duration.ofSeconds(2));
        assertEquals(4, result.size());
        assertEquals("0x1", result.getFirst().relativePc());
        assertEquals(2, result.getFirst().resolution().inline().size());
        assertEquals(id, result.get(1).resolution().source().symbolFileId());
        assertEquals(frames.get(2), result.get(2));
        assertEquals(frames.get(3), result.get(3));
        verify(registry, times(1)).symbolizeNative(eq(lease), eq(List.of("0x1")), any());
        verify(lease).close();
        assertEquals(frames, new NativeFrameBatch(registry).resolve(app, "1", modules, frames, Duration.ZERO));
    }

    /** 主栈和其他线程共用地址时仅执行一次解析，线程边界和采集状态保持。 */
    @Test
    void batchesMainAndAuxiliaryThreadsTogether() {
        var registry = mock(SymbolRegistry.class);
        var lease = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(UUID.randomUUID());
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireNative(app, "1", "arm64-v8a", "abcd")).thenReturn(Optional.of(lease));
        when(registry.symbolizeNative(eq(lease), eq(List.of("0x1")), any())).thenReturn(new NativeSymbolicationResult(
                Map.of("0x1", new NativeSymbolicationResult.Frame(List.of(new NativeSymbolicationResult.Inline("f", "a.cc", 1, null)), null)), null));
        var pending = new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var frame = new NativeStack.Frame(0, "m", "0x1", null, null, pending);
        var trace = new NativeStack.Backtrace("ok", null, true, 1L, List.of(frame));
        var module = new NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd");
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(module), trace, null);
        var aux = new com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks(List.of(),
                List.of(new com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks.NativeThread(2, trace)));
        var result = new NativeFrameBatch(registry).resolveStacks(app, "1", main, aux, Duration.ofSeconds(2));
        assertEquals("symbolicated", result.main().backtrace().frames().getFirst().resolution().status());
        assertEquals("symbolicated", result.auxiliary().nativeThreads().getFirst().backtrace().frames().getFirst().resolution().status());
        assertTrue(result.main().backtrace().truncated());
        assertEquals(2, result.auxiliary().nativeThreads().getFirst().systemTid());
        verify(registry, times(1)).symbolizeNative(eq(lease), eq(List.of("0x1")), any());
    }
}
