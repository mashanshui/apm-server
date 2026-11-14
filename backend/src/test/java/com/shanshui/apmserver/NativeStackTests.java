package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeStack;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 Native 原物理帧、逐帧状态和 Java 栈来源。 */
class NativeStackTests {
    /** 合法结果可往返 JSON，长地址和内联链不丢失。 */
    @Test
    void keepsOriginalFramesAndCompleteSources() {
        var source = new NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 1, "a".repeat(64));
        var resolution = new NativeStack.Resolution("symbolicated", null,
                List.of(new NativeStack.Inline("inner", "a.cc", 1, null), new NativeStack.Inline("outer", "a.cc", 2, 3)),
                source, source, Instant.parse("2026-01-01T00:00:00Z"), null);
        var frame = new NativeStack.Frame(0, "m1", "0xffffffffffffffff", "captured", "0x0", resolution);
        var backtrace = new NativeStack.Backtrace("ok", null, false, 1L, List.of(frame));
        var stack = new NativeStack("arm64-v8a", 12, "main", true, null, null,
                List.of(new NativeCrash.Module("m1", "lib.so", null, "arm64-v8a", "abcd")), backtrace, null);
        var mapper = new ObjectMapper();
        assertEquals(stack, mapper.readValue(mapper.writeValueAsString(stack), NativeStack.class));
        assertEquals("0xffffffffffffffff", stack.backtrace().frames().getFirst().relativePc());
        assertEquals(2, stack.backtrace().frames().getFirst().resolution().inline().size());
    }

    /** 缺身份、类型错配、空结果或未知状态不能被视为成功。 */
    @Test
    void rejectsInvalidFrameAndSource() {
        var source = new NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 1, "a".repeat(64));
        var javaSource = new NativeStack.Source("MAPPING", UUID.randomUUID(), 1, "b".repeat(64));
        var inline = List.of(new NativeStack.Inline("function", "a.cc", null, null));
        var at = Instant.parse("2026-01-01T00:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new NativeStack.Resolution("symbolicated", null, inline, null, null, at, null));
        assertThrows(IllegalArgumentException.class, () -> new NativeStack.Resolution("symbolicated", null, inline, javaSource, null, at, null));
        assertThrows(IllegalArgumentException.class, () -> new NativeStack.Resolution("unknown", null, null, null, null, null, null));
        var success = new NativeStack.Resolution("symbolicated", null, inline, source, null, at, null);
        assertThrows(IllegalArgumentException.class, () -> new NativeStack.Frame(0, null, "0x1", null, null, success));
        var frame = new NativeStack.Frame(0, "missing", "0x1", null, null, success);
        assertThrows(IllegalArgumentException.class, () -> new NativeStack("arm64-v8a", 1, null, false,
                null, null, List.of(), new NativeStack.Backtrace("ok", null, false, 1L, List.of(frame)), null));
        var unknown = new NativeStack.Resolution("not_applicable", "missing_module", null, null, null, null, null);
        var unknownFrame = new NativeStack.Frame(0, null, null, null, null, unknown);
        assertEquals(unknownFrame, new NativeStack("arm64-v8a", 1, null, false, null, null, List.of(),
                new NativeStack.Backtrace("ok", null, false, 1L, List.of(unknownFrame)), null).backtrace().frames().getFirst());
        assertThrows(IllegalArgumentException.class, () -> new NativeStack.Frame(0, null, null, null, null,
                new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> new NativeStack.Inline("f", "/private/a.cc", null, null));
    }

    /** 空栈和同名线程的独立身份不因处理而改变。 */
    @Test
    void keepsEmptyJavaStackAndDistinctThreadIds() {
        var empty = new NativeStack.JavaStack("ok", null, false, 1L, "not_applicable", "empty",
                new CrashThreadStack.Stack("raw_frames", List.of()), null, null, null, null);
        var mapping = new NativeStack.Source("MAPPING", UUID.randomUUID(), 2, "a".repeat(64));
        var completed = new NativeStack.JavaStack("ok", null, false, 1L, "symbolicated", null,
                new CrashThreadStack.Stack("retrace_text", "at A.run(A.java:1)"), mapping, mapping, Instant.now(), null);
        assertEquals(mapping, completed.source());
        assertEquals(mapping, completed.attemptedSource());
        var threads = new NativeCurrentAuxiliaryStacks(List.of(new NativeCurrentAuxiliaryStacks.JavaThread("1", empty),
                new NativeCurrentAuxiliaryStacks.JavaThread("2", empty)), List.of());
        assertEquals(2, threads.javaThreads().size());
        assertThrows(IllegalArgumentException.class, () -> new NativeCurrentAuxiliaryStacks(
                List.of(new NativeCurrentAuxiliaryStacks.JavaThread("1", empty), new NativeCurrentAuxiliaryStacks.JavaThread("1", empty)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new NativeStack.JavaStack("ok", null, false, 1L, "symbolicated", null,
                new CrashThreadStack.Stack("retrace_text", "at A.run(A.java:1)"), null, null, Instant.now(), null));
    }
}
