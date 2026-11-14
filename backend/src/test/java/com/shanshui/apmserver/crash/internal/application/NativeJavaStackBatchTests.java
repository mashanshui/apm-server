package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.symbol.api.SymbolFileLease;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.symbol.api.SymbolicationResult;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native 中每条 Java 栈独立固定完整 R8 输出和 mapping 来源。 */
class NativeJavaStackBatchTests {
    /** Java 主辅栈共享 UTF-8 总预算，超限原栈在退避后可使用同一 mapping 完成。 */
    @Test
    void limitsCombinedJavaOutputAndResumesAfterBackoff() {
        var registry = mock(SymbolRegistry.class);
        var sanitizer = mock(CrashSanitizer.class);
        when(sanitizer.sanitizeRetrace(anyString())).thenAnswer(call -> call.getArgument(0));
        var lease = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(UUID.randomUUID());
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireVersioned(app, "1", "build")).thenReturn(Optional.of(lease));
        /** 每条栈低于 32 KiB，但十条文本的 UTF-8 总量超过 256 KiB。 */
        String text = "界".repeat(10_922);
        when(registry.retraceAuxiliary(eq(lease), anyList(), anyLong(), any()))
                .thenReturn(new SymbolicationResult(text, SymbolicationResult.Status.SYMBOLICATED, null));
        var raw = new CrashThreadStack.Stack("raw_frames", List.of(new StackFrame("A", "a", null, null, null)));
        var pending = new NativeStack.JavaStack("ok", null, false, 1L, "pending", "missing_mapping", raw, null, null, null, null);
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(),
                new NativeStack.Backtrace("unavailable", "missing", false, 1L, List.of()), pending);
        var threads = java.util.stream.IntStream.range(0, 9)
                .mapToObj(i -> new NativeCurrentAuxiliaryStacks.JavaThread(Integer.toString(i + 2), pending)).toList();
        var auxiliary = new NativeCurrentAuxiliaryStacks(threads, List.of());
        var now = java.time.Instant.parse("2026-01-01T00:00:00Z");
        var first = new NativeJavaStackBatch(registry, new ObjectMapper(), sanitizer, () -> 0L,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC)).resolve(app, "1", "build", main, auxiliary, Duration.ofSeconds(2));
        assertEquals("symbolicated", first.main().javaStack().symbolicationStatus());
        var firstThreads = first.auxiliary().javaThreads();
        assertEquals(7, firstThreads.stream().filter(t -> "symbolicated".equals(t.stack().symbolicationStatus())).count());
        assertEquals(2, firstThreads.stream().filter(t -> "output_limit".equals(t.stack().symbolicationReason())
                && now.plusSeconds(30).equals(t.stack().retryAt()) && raw.equals(t.stack().stack())).count());
        assertTrue(8 * text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 256 * 1024);
        var second = new NativeJavaStackBatch(registry, new ObjectMapper(), sanitizer, () -> 0L,
                java.time.Clock.fixed(now.plusSeconds(31), java.time.ZoneOffset.UTC))
                .resolve(app, "1", "build", first.main(), first.auxiliary(), Duration.ofSeconds(2));
        assertEquals(first.main().javaStack(), second.main().javaStack());
        assertTrue(second.auxiliary().javaThreads().stream().allMatch(t -> "symbolicated".equals(t.stack().symbolicationStatus())));
        for (int i = 0; i < 7; i++) assertEquals(firstThreads.get(i), second.auxiliary().javaThreads().get(i));
    }
    /** 预算有限时，未尝试的辅助线程先于已尝试的主关联栈。 */
    @Test
    void prioritizesUntriedJavaStackWithinBudget() {
        var registry = mock(SymbolRegistry.class);
        var sanitizer = mock(CrashSanitizer.class);
        when(sanitizer.sanitizeRetrace(anyString())).thenAnswer(call -> call.getArgument(0));
        var lease = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(UUID.randomUUID());
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireVersioned(app, "1", "build")).thenReturn(Optional.of(lease));
        var ticker = new java.util.concurrent.atomic.AtomicLong();
        when(registry.retraceAuxiliary(eq(lease), anyList(), anyLong(), any())).thenAnswer(call -> {
            ticker.addAndGet(Duration.ofSeconds(2).toNanos());
            return new SymbolicationResult("at B.b(B.java:1)", SymbolicationResult.Status.SYMBOLICATED, null);
        });
        var raw = new CrashThreadStack.Stack("raw_frames", List.of(new StackFrame("a.A", "a", null, null, null)));
        var tried = new NativeStack.JavaStack("ok", null, false, 1L, "pending", "timeout", raw, null, null,
                java.time.Instant.parse("2025-12-31T23:59:00Z"), java.time.Instant.parse("2025-12-31T23:59:30Z"));
        var fresh = new NativeStack.JavaStack("ok", null, false, 1L, "pending", "missing_mapping", raw, null, null, null, null);
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(),
                new NativeStack.Backtrace("unavailable", "missing", false, 1L, List.of()), tried);
        var aux = new NativeCurrentAuxiliaryStacks(List.of(new NativeCurrentAuxiliaryStacks.JavaThread("2", fresh)), List.of());
        var clock = java.time.Clock.fixed(java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);
        var batch = new NativeJavaStackBatch(registry, new ObjectMapper(), sanitizer, ticker::get, clock);
        var result = batch.resolve(app, "1", "build", main, aux, Duration.ofSeconds(2));
        assertEquals(tried, result.main().javaStack());
        assertEquals("symbolicated", result.auxiliary().javaThreads().getFirst().stack().symbolicationStatus());
        verify(registry, times(1)).retraceAuxiliary(eq(lease), anyList(), anyLong(), any());
    }

    /** Native 工具延期时，Java 仍使用自己的预算完成。 */
    @Test
    void keepsJavaBudgetWhenNativeToolIsUnavailable() {
        var registry = mock(SymbolRegistry.class);
        var sanitizer = mock(CrashSanitizer.class);
        when(sanitizer.sanitizeRetrace(anyString())).thenAnswer(call -> call.getArgument(0));
        var nativeLease = mock(SymbolFileLease.class);
        var mapping = mock(SymbolFileLease.class);
        for (var lease : List.of(nativeLease, mapping)) {
            when(lease.symbolId()).thenReturn(UUID.randomUUID());
            when(lease.revision()).thenReturn(1);
            when(lease.sha256()).thenReturn("a".repeat(64));
        }
        UUID app = UUID.randomUUID();
        when(registry.acquireNative(app, "1", "arm64-v8a", "abcd")).thenReturn(Optional.of(nativeLease));
        when(registry.symbolizeNative(eq(nativeLease), anyList(), any())).thenReturn(new com.shanshui.apmserver.symbol.api.NativeSymbolicationResult(java.util.Map.of(), "native_tool_unavailable"));
        when(registry.acquireVersioned(app, "1", "build")).thenReturn(Optional.of(mapping));
        when(registry.retraceAuxiliary(eq(mapping), anyList(), anyLong(), any())).thenReturn(new SymbolicationResult("at A.a(A.java:1)", SymbolicationResult.Status.SYMBOLICATED, null));
        var pending = new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var trace = new NativeStack.Backtrace("ok", null, false, 1L, List.of(new NativeStack.Frame(0, "m", "0x1", null, null, pending)));
        var javaStack = new NativeStack.JavaStack("ok", null, false, 1L, "pending", "missing_mapping",
                new CrashThreadStack.Stack("raw_frames", List.of(new StackFrame("a.A", "a", null, null, null))), null, null, null, null);
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null,
                List.of(new com.shanshui.apmserver.crash.api.NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd")), trace, javaStack);
        var stages = new NativeBatchStages(new NativeFrameBatch(registry), new NativeJavaStackBatch(registry, new ObjectMapper(), sanitizer));
        var result = stages.resolve(app, "1", "build", main, new NativeCurrentAuxiliaryStacks(List.of(), List.of()), Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertEquals("native_tool_unavailable", result.main().backtrace().frames().getFirst().resolution().reason());
        assertEquals("symbolicated", result.main().javaStack().symbolicationStatus());
    }

    /** 一条栈失败不影响同一事件中的另一条完整成功。 */
    @Test
    void allowsIndependentSuccessAndTemporaryFailure() {
        var registry = mock(SymbolRegistry.class);
        var sanitizer = mock(CrashSanitizer.class);
        when(sanitizer.sanitizeRetrace(anyString())).thenAnswer(call -> call.getArgument(0));
        var lease = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(UUID.randomUUID());
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireVersioned(app, "1", "build")).thenReturn(Optional.of(lease));
        when(registry.retraceAuxiliary(eq(lease), anyList(), anyLong(), any()))
                .thenReturn(new SymbolicationResult("at A.a(A.java:1)\nat B.b(B.java:2)", SymbolicationResult.Status.SYMBOLICATED, null))
                .thenReturn(SymbolicationResult.failed("timeout"));
        var javaStack = new NativeStack.JavaStack("ok", null, false, 1L, "pending", "missing_mapping",
                new CrashThreadStack.Stack("raw_frames", List.of(new StackFrame("a.A", "a", null, null, null))), null, null, null, null);
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(),
                new NativeStack.Backtrace("unavailable", "missing", false, 1L, List.of()), javaStack);
        var aux = new NativeCurrentAuxiliaryStacks(List.of(new NativeCurrentAuxiliaryStacks.JavaThread("2", javaStack)), List.of());
        var result = new NativeJavaStackBatch(registry, new ObjectMapper(), sanitizer).resolve(app, "1", "build", main, aux, Duration.ofSeconds(2));
        assertEquals("at A.a(A.java:1)\nat B.b(B.java:2)", result.main().javaStack().stack().content());
        assertEquals("timeout", result.auxiliary().javaThreads().getFirst().stack().symbolicationReason());
        assertNotNull(result.auxiliary().javaThreads().getFirst().stack().retryAt());
        assertEquals(javaStack.stack(), result.auxiliary().javaThreads().getFirst().stack().stack());
    }

    /** 专用 mapping 不可读时不自行切换默认来源，保留原栈并退避。 */
    @Test
    void defersWhenSelectedMappingIsUnreadable() {
        var registry = mock(SymbolRegistry.class);
        var sanitizer = mock(CrashSanitizer.class);
        UUID app = UUID.randomUUID();
        when(registry.acquireVersioned(app, "1", "build"))
                .thenThrow(new com.shanshui.apmserver.symbol.api.SymbolStoreUnavailableException("unavailable"));
        var stack = new NativeStack.JavaStack("ok", null, false, 1L, "pending", "missing_mapping",
                new CrashThreadStack.Stack("raw_frames", List.of(new StackFrame("A", "a", null, null, null))), null, null, null, null);
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(),
                new NativeStack.Backtrace("unavailable", "missing", false, 1L, List.of()), stack);
        var aux = new NativeCurrentAuxiliaryStacks(List.of(), List.of());
        var result = new NativeJavaStackBatch(registry, new ObjectMapper(), sanitizer).resolve(app, "1", "build", main, aux, Duration.ofSeconds(2));
        assertEquals("mapping_unavailable", result.main().javaStack().symbolicationReason());
        assertNotNull(result.main().javaStack().retryAt());
        assertEquals(stack.stack(), result.main().javaStack().stack());
        verify(registry, times(1)).acquireVersioned(app, "1", "build");
        verify(registry, never()).acquire(any(), anyString());
    }

    /** 相同线程名不合并，未改变文本也属于 R8 完整成功。 */
    @Test
    void preservesIndependentJavaStacksAndFullOutput() {
        var registry = mock(SymbolRegistry.class);
        var sanitizer = mock(CrashSanitizer.class);
        when(sanitizer.sanitizeRetrace(anyString())).thenAnswer(call -> call.getArgument(0));
        var lease = mock(SymbolFileLease.class);
        UUID app = UUID.randomUUID(), sourceId = UUID.randomUUID();
        when(lease.symbolId()).thenReturn(sourceId);
        when(lease.revision()).thenReturn(2);
        when(lease.sha256()).thenReturn("a".repeat(64));
        when(registry.acquireVersioned(app, "1", "build")).thenReturn(Optional.of(lease));
        when(registry.retraceAuxiliary(eq(lease), anyList(), anyLong(), any())).thenAnswer(call ->
                new SymbolicationResult(String.join("\n", call.<List<String>>getArgument(1)), SymbolicationResult.Status.SYMBOLICATED, null));
        var frame = new StackFrame("a.A", "a", "A.java", 1, true);
        var javaStack = new NativeStack.JavaStack("ok", null, false, 1L, "pending", "missing_mapping",
                new CrashThreadStack.Stack("raw_frames", List.of(frame)), null, null, null, null);
        var trace = new NativeStack.Backtrace("unavailable", "missing", false, 1L, List.of());
        var main = new NativeStack("arm64-v8a", 1, "main", true, null, null, List.of(), trace, javaStack);
        var aux = new NativeCurrentAuxiliaryStacks(List.of(new NativeCurrentAuxiliaryStacks.JavaThread("10", javaStack),
                new NativeCurrentAuxiliaryStacks.JavaThread("11", javaStack)), List.of());
        var batch = new NativeJavaStackBatch(registry, new ObjectMapper(), sanitizer);
        var result = batch.resolve(app, "1", "build", main, aux, Duration.ofSeconds(2));
        assertEquals("symbolicated", result.main().javaStack().symbolicationStatus());
        assertEquals(sourceId, result.main().javaStack().source().symbolFileId());
        assertEquals("\tat a.A.a(A.java:1)", result.main().javaStack().stack().content());
        assertEquals(List.of("10", "11"), result.auxiliary().javaThreads().stream().map(NativeCurrentAuxiliaryStacks.JavaThread::javaId).toList());
        assertTrue(result.auxiliary().javaThreads().stream().allMatch(thread -> "symbolicated".equals(thread.stack().symbolicationStatus())));
        verify(registry, times(3)).retraceAuxiliary(eq(lease), anyList(), anyLong(), any());
        clearInvocations(registry);
        assertEquals(result, batch.resolve(app, "1", "build", result.main(), result.auxiliary(), Duration.ofSeconds(2)));
        verifyNoInteractions(registry);
    }
}
