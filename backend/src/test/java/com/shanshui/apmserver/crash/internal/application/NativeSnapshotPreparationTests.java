package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.crash.api.NativeAuxiliaryStacks;
import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.persistence.CrashStateJson;
import com.shanshui.apmserver.crash.internal.persistence.NativeStateTransition;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 原始主辅栈转换后仍保持采集身份。 */
class NativeSnapshotPreparationTests {
    /** 可定位帧等待后台检查，未知帧不可处理；Java 线程 ID 与原始帧不变。 */
    @Test
    void convertsRawStacksWithoutChangingCaptureIdentity() {
        var mapper = new ObjectMapper();
        var module = new NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd");
        var frames = List.of(new NativeCrash.Frame(0, "m", "0x1", "captured", null),
                new NativeCrash.Frame(1, null, null, null, null));
        var trace = new NativeCrash.Backtrace("ok", null, true, 1L, frames);
        var javaStack = new NativeCrash.JavaStack("ok", null, false, 1L, List.of(new StackFrame("A", "a", null, null, null)));
        var raw = new NativeCrash("arm64-v8a", 1, "main", true, null, null, List.of(module), trace, javaStack);
        var otherJava = new CrashThreadStack("2", "pending", "native_symbolication_deferred",
                new CrashThreadStack.Stack("raw_frames", List.of(java.util.Map.of("className", "B", "methodName", "b"))), null, null, null, null);
        var auxiliary = new NativeAuxiliaryStacks(List.of(otherJava), List.of(new NativeAuxiliaryStacks.NativeThreadStack(3, trace)));
        var state = new CrashState(UUID.randomUUID(), "event", "build", Instant.parse("2026-01-01T00:00:00Z"), 0,
                "pending", "native_symbolication_deferred", "raw_native", mapper.writeValueAsString(raw), "SIGSEGV", "fp",
                "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), mapper.writeValueAsString(auxiliary));
        var prepared = new NativeSnapshotPreparation(mapper).prepare(state);
        assertEquals("pending", prepared.main().backtrace().frames().getFirst().resolution().status());
        assertEquals("native_symbolication_deferred", prepared.main().backtrace().frames().getFirst().resolution().reason());
        assertEquals("native_symbolication_deferred", prepared.main().javaStack().symbolicationReason());
        assertEquals("native_symbolication_deferred", prepared.auxiliary().javaThreads().getFirst().stack().symbolicationReason());
        assertEquals("not_applicable", prepared.main().backtrace().frames().get(1).resolution().status());
        assertEquals("2", prepared.auxiliary().javaThreads().getFirst().javaId());
        assertEquals(3, prepared.auxiliary().nativeThreads().getFirst().systemTid());
        var result = new NativeSnapshotSummary(mapper).summarize(state, prepared.main(), prepared.auxiliary());
        new NativeStateTransition(mapper).validate(state, result.state());
        assertNotNull(new CrashStateJson(mapper).encode(result.state()));
    }
}
