package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 主帧摘要与辅助栈剩余工作保持独立。 */
class NativeSnapshotSummaryTests {
    /** 部分成功与确定性失败共存时按主帧汇总失败并保留重试机会。 */
    @Test
    void summarizesPartialSuccessAndFailure() {
        var mapper = new ObjectMapper();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var base = new CrashState(UUID.randomUUID(), "partial", "build", now, 0, "pending", "native_symbolication_deferred",
                "raw_native", "{}", "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "");
        var source = new NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 1, "b".repeat(64));
        var success = new NativeStack.Resolution("symbolicated", null, List.of(new NativeStack.Inline("f", null, null, null)), source, source, now, null);
        var failure = new NativeStack.Resolution("failed", "native_no_function", null, null, source, now, null);
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null,
                List.of(new NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd")),
                new NativeStack.Backtrace("ok", null, false, 1L, List.of(new NativeStack.Frame(0, "m", "0x1", null, null, success),
                        new NativeStack.Frame(1, "m", "0x2", null, null, failure))), null);
        var result = new NativeSnapshotSummary(mapper).summarize(base, main, new NativeCurrentAuxiliaryStacks(List.of(), List.of()));
        assertEquals("failed", result.state().status());
        assertEquals("native_no_function", result.state().reason());
        assertEquals(1, result.succeeded());
        assertEquals(1, result.failed());
        assertTrue(result.state().nativeWorkRemaining());
    }

    /** 空主栈不伪造成功，非空 Java 栈仍保留处理机会。 */
    @Test
    void keepsJavaWorkWhenMainStackIsUnavailable() {
        var mapper = new ObjectMapper();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var base = new CrashState(UUID.randomUUID(), "empty", "build", now, 0, "pending", "native_symbolication_deferred",
                "raw_native", "{}", "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "");
        var javaStack = new NativeStack.JavaStack("ok", null, false, 1L, "pending", "missing_mapping",
                new com.shanshui.apmserver.crash.api.CrashThreadStack.Stack("raw_frames", List.of(java.util.Map.of("className", "A", "methodName", "a"))), null, null, null, null);
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(),
                new NativeStack.Backtrace("unavailable", "missing", false, 1L, List.of()), javaStack);
        var result = new NativeSnapshotSummary(mapper).summarize(base, main, new NativeCurrentAuxiliaryStacks(List.of(), List.of()));
        assertEquals("pending", result.state().status());
        assertEquals("capture_stack_unavailable", result.state().reason());
        assertTrue(result.state().nativeWorkRemaining());
        assertEquals(0, result.succeeded());
    }

    /** 主栈成功时其他线程仍可继续处理，原指纹和摘要不变。 */
    @Test
    void retainsAuxiliaryWorkAfterMainSuccess() {
        var mapper = new ObjectMapper();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var base = new CrashState(UUID.randomUUID(), "event", "build", now, 0, "pending", "native_symbolication_deferred",
                "raw_native", "{}", "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "");
        var module = new NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd");
        var source = new NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 1, "b".repeat(64));
        var success = new NativeStack.Resolution("symbolicated", null, List.of(new NativeStack.Inline("f", null, null, null)), source, source, now, null);
        var waiting = new NativeStack.Resolution("pending", "missing_so", null, null, null, null, null);
        var mainTrace = new NativeStack.Backtrace("ok", null, false, 1L, List.of(new NativeStack.Frame(0, "m", "0x1", null, null, success)));
        var auxiliaryTrace = new NativeStack.Backtrace("ok", null, false, 1L, List.of(new NativeStack.Frame(0, "m", "0x2", null, null, waiting)));
        var main = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(module), mainTrace, null);
        var auxiliary = new NativeCurrentAuxiliaryStacks(List.of(), List.of(new NativeCurrentAuxiliaryStacks.NativeThread(2, auxiliaryTrace)));
        var summary = new NativeSnapshotSummary(mapper).summarize(base, main, auxiliary);
        assertEquals("symbolicated", summary.state().status());
        assertTrue(summary.state().nativeWorkRemaining());
        assertEquals(1, summary.succeeded());
        assertEquals(base.captureSha256(), summary.state().captureSha256());
        assertEquals(base.fingerprint(), summary.state().fingerprint());
        assertEquals("temporary", summary.state().groupingKind());
    }
}
