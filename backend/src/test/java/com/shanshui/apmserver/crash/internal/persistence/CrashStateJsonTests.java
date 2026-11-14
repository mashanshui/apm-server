package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.internal.domain.CrashState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证发布载荷迁移时仍能对账原有在途版本。 */
class CrashStateJsonTests {
    /** 首次转换和后续快照必须保留采集现场与已成功单元。 */
    @Test
    void rejectsNativeIdentityChangesAndSuccessfulFrameRegression() {
        var mapper = new ObjectMapper();
        var guard = new NativeStateTransition(mapper);
        var now = Instant.parse("2026-01-01T00:00:01Z");
        var module = new com.shanshui.apmserver.crash.api.NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd");
        var rawFrame = new com.shanshui.apmserver.crash.api.NativeCrash.Frame(0, "m", "0x1", null, null);
        var rawJava = new com.shanshui.apmserver.crash.api.NativeCrash.JavaStack("ok", null, false, 1L,
                java.util.List.of(new com.shanshui.apmserver.telemetry.api.StackFrame("a.A", "a", null, null, null)));
        var raw = new com.shanshui.apmserver.crash.api.NativeCrash("arm64-v8a", 1, null, false, null, null,
                java.util.List.of(module), new com.shanshui.apmserver.crash.api.NativeCrash.Backtrace("ok", null, false, 1L, java.util.List.of(rawFrame)), rawJava);
        var source = new com.shanshui.apmserver.crash.api.NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 1, "a".repeat(64));
        var success = new com.shanshui.apmserver.crash.api.NativeStack.Resolution("symbolicated", null,
                java.util.List.of(new com.shanshui.apmserver.crash.api.NativeStack.Inline("f", "a.cc", 1, null)), source, source, now, null);
        var frame = new com.shanshui.apmserver.crash.api.NativeStack.Frame(0, "m", "0x1", null, null, success);
        var javaStack = new com.shanshui.apmserver.crash.api.NativeStack.JavaStack("ok", null, false, 1L, "pending", "missing_mapping",
                new com.shanshui.apmserver.crash.api.CrashThreadStack.Stack("raw_frames", java.util.List.of(java.util.Map.of("className", "a.A", "methodName", "a"))), null, null, null, null);
        var stack = new com.shanshui.apmserver.crash.api.NativeStack("arm64-v8a", 1, null, false, null, null,
                java.util.List.of(module), new com.shanshui.apmserver.crash.api.NativeStack.Backtrace("ok", null, false, 1L, java.util.List.of(frame)), javaStack);
        var base = new CrashState(UUID.randomUUID(), "event", "build", now, 0, "pending", "native_symbolication_deferred", "raw_native",
                mapper.writeValueAsString(raw), "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "c".repeat(64), "");
        var candidate = new CrashState(base.appId(), base.eventId(), base.buildId(), now, 0, "symbolicated", null, "native_stack",
                mapper.writeValueAsString(stack), base.exceptionType(), base.fingerprint(), base.fingerprintVersion(), "temporary",
                null, null, null, now, null, base.captureSha256(), "", false);
        guard.validate(base, candidate);
        var changedFrame = new com.shanshui.apmserver.crash.api.NativeStack.Frame(0, "m", "0x2", null, null, success);
        var changed = new com.shanshui.apmserver.crash.api.NativeStack("arm64-v8a", 1, null, false, null, null, java.util.List.of(module),
                new com.shanshui.apmserver.crash.api.NativeStack.Backtrace("ok", null, false, 1L, java.util.List.of(changedFrame)), javaStack);
        var invalid = new CrashState(base.appId(), base.eventId(), base.buildId(), now, 0, "symbolicated", null, "native_stack",
                mapper.writeValueAsString(changed), base.exceptionType(), base.fingerprint(), base.fingerprintVersion(), "temporary",
                null, null, null, now, null, base.captureSha256(), "", false);
        assertThrows(IllegalArgumentException.class, () -> guard.validate(base, invalid));
        assertThrows(IllegalArgumentException.class, () -> guard.validate(candidate, invalid));
    }
    /** 结构化 Native 的逐帧来源、捕获摘要和剩余工作标记完整往返。 */
    @Test
    void roundTripsStructuredNativeAndRejectsInvalidUnits() {
        var mapper = new ObjectMapper();
        var codec = new CrashStateJson(mapper);
        var now = Instant.parse("2026-01-01T00:00:01Z");
        var source = new com.shanshui.apmserver.crash.api.NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 1, "a".repeat(64));
        var resolution = new com.shanshui.apmserver.crash.api.NativeStack.Resolution("symbolicated", null,
                java.util.List.of(new com.shanshui.apmserver.crash.api.NativeStack.Inline("function", "source.cc", null, null)),
                source, source, now, null);
        var frame = new com.shanshui.apmserver.crash.api.NativeStack.Frame(0, "m", "0xffffffffffffffff", null, null, resolution);
        var stack = new com.shanshui.apmserver.crash.api.NativeStack("arm64-v8a", 1, null, false, null, null,
                java.util.List.of(new com.shanshui.apmserver.crash.api.NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd")),
                new com.shanshui.apmserver.crash.api.NativeStack.Backtrace("ok", null, false, 1L, java.util.List.of(frame)), null);
        var auxiliary = new com.shanshui.apmserver.crash.api.NativeCurrentAuxiliaryStacks(java.util.List.of(), java.util.List.of());
        var state = new CrashState(UUID.randomUUID(), "native", "build", now, 3, "symbolicated", null,
                "native_stack", mapper.writeValueAsString(stack), "SIGSEGV", "fp", "native-tmp-v1", "temporary",
                null, null, null, now, null, "c".repeat(64), mapper.writeValueAsString(auxiliary), true);
        var encoded = (ObjectNode) mapper.readTree(codec.encode(state));
        assertEquals(state, codec.decode(encoded));
        assertTrue(codec.decode(encoded).nativeWorkRemaining());
        assertEquals("c".repeat(64), codec.decode(encoded).captureSha256());
        var invalidFlag = encoded.deepCopy();
        invalidFlag.put("native_work_remaining", 2);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidFlag));
        var invalidSummary = encoded.deepCopy();
        invalidSummary.put("symbolication_status", "pending");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(invalidSummary));
        var invalidPayload = (ObjectNode) mapper.readTree(state.stackPayload());
        ((ObjectNode) invalidPayload.path("backtrace").path("frames").get(0).path("resolution")).remove("source");
        var missingSource = encoded.deepCopy();
        missingSource.put("stack_payload", mapper.writeValueAsString(invalidPayload));
        assertThrows(RuntimeException.class, () -> codec.decode(missingSource));
        /** 内存适配器必须与数据库适配器一样拒绝不完整来源。 */
        var memory = new InMemoryCrashStateStore(new com.shanshui.apmserver.platform.api.StorageProperties(), mapper);
        memory.publish(state);
        memory.replay(codec.encode(state));
        var invalidState = new CrashState(state.appId(), state.eventId(), state.buildId(), state.eventTime(), state.stateVersion() + 1,
                state.status(), state.reason(), state.stackFormat(), mapper.writeValueAsString(invalidPayload), state.exceptionType(),
                state.fingerprint(), state.fingerprintVersion(), state.groupingKind(), null, null, null, now, null,
                state.captureSha256(), state.threadStacksPayload(), true);
        assertThrows(RuntimeException.class, () -> memory.publish(invalidState));
    }

    /** 空 Native 栈必须保留不可采集原因，不得伪造成功。 */
    @Test
    void requiresUnavailableReasonForEmptyNativeStack() {
        var mapper = new ObjectMapper();
        var codec = new CrashStateJson(mapper);
        var stack = new com.shanshui.apmserver.crash.api.NativeStack("arm64-v8a", 1, null, false, null, null,
                java.util.List.of(), new com.shanshui.apmserver.crash.api.NativeStack.Backtrace("unavailable", "missing", false, 1L, java.util.List.of()), null);
        var state = new CrashState(UUID.randomUUID(), "empty", "build", Instant.parse("2026-01-01T00:00:00Z"), 1,
                "pending", "capture_stack_unavailable", "native_stack", mapper.writeValueAsString(stack), "SIGSEGV",
                "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "", false);
        assertEquals(state, codec.decode(mapper.readTree(codec.encode(state))));
        var invalid = new CrashState(state.appId(), state.eventId(), state.buildId(), state.eventTime(), state.stateVersion(),
                "failed", "other", state.stackFormat(), state.stackPayload(), state.exceptionType(), state.fingerprint(),
                state.fingerprintVersion(), state.groupingKind(), null, null, null, null, null, state.captureSha256(), "", false);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(invalid));
    }
    /** Native 空回溯、零地址和 64 位寄存器经过状态编解码仍保留原值。 */
    @Test
    void roundTripsNativeStateWithoutConvertingAddresses() {
        var mapper = new ObjectMapper();
        var codec = new CrashStateJson(mapper);
        String raw = "{\"signal\":{\"faultAddress\":\"0x0\"},\"registers\":{\"pc\":\"0xffffffffffffffff\"},\"frames\":[]}";
        var state = new CrashState(UUID.randomUUID(), "native", "build", Instant.parse("2026-01-01T00:00:00Z"),
                0, "pending", "native_symbolication_deferred", "raw_native", raw, "SIGSEGV", "fp",
                "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "{}");
        assertEquals(state, codec.decode(mapper.readTree(codec.encode(state))));
        assertThrows(IllegalArgumentException.class, () -> new CrashState(state.appId(), state.eventId(), state.buildId(),
                state.eventTime(), 0, "failed", state.reason(), "raw_native", raw, "SIGSEGV", "fp", "native-tmp-v1",
                "temporary", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CrashState(state.appId(), state.eventId(), state.buildId(),
                state.eventTime(), 0, "pending", state.reason(), "raw_native", raw, "SIGSEGV", "fp", "native-tmp-v1",
                "temporary", UUID.randomUUID(), 1, "a".repeat(64), state.eventTime(), null));
    }

    /** 新状态包含诊断列，旧登记缺列时与数据库默认空值等价。 */
    @Test
    void comparesLegacyAndNewPublicationWithoutDroppingCurrentState() {
        /** 构造固定来源的合成成功结果。 */
        var state = new CrashState(UUID.randomUUID(), "event", "build", Instant.parse("2026-01-01T00:00:00Z"),
                5, "symbolicated", null, "retrace_text", "at A.run(A.java:1)", "A", "fingerprint", "v1",
                "resolved", UUID.randomUUID(), 1, "a".repeat(64), Instant.parse("2026-01-01T00:00:01Z"), null);
        /** 模拟旧版本在途载荷，该载荷不能在升级时被重新生成。 */
        var mapper = new ObjectMapper();
        var codec = new CrashStateJson(mapper);
        var legacy = (ObjectNode) mapper.readTree(codec.encode(state));
        legacy.remove("capture_sha256");
        legacy.remove("thread_stacks_payload");
        assertTrue(codec.matches(mapper.writeValueAsString(legacy), state));
        var changed = new CrashState(state.appId(), state.eventId(), state.buildId(), state.eventTime(), state.stateVersion(),
                state.status(), state.reason(), state.stackFormat(), state.stackPayload(), state.exceptionType(), state.fingerprint(),
                state.fingerprintVersion(), state.groupingKind(), state.symbolId(), state.symbolRevision(), state.symbolSha256(),
                state.processedAt(), state.retryAt(), "b".repeat(64), "[]");
        assertFalse(codec.matches(mapper.writeValueAsString(legacy), changed));
    }

    /** 引号、反斜杠与控制字符经过嵌套 JSON 编码后仍按实际 UTF-8 字节计算。 */
    @Test
    void keepsEscapedMainAndAuxiliaryPayloadWithinThePublicationLimit() {
        var mapper = new ObjectMapper();
        var codec = new CrashStateJson(mapper);
        String escaped = "\"\\\n".repeat(140_000);
        String auxiliary = mapper.writeValueAsString(java.util.List.of(
                new com.shanshui.apmserver.crash.api.CrashThreadStack("1", "symbolicated", null,
                        new com.shanshui.apmserver.crash.api.CrashThreadStack.Stack("retrace_text", "\"\\\n".repeat(10_000)),
                        UUID.randomUUID(), 1, "a".repeat(64), Instant.parse("2026-01-01T00:00:01Z"))));
        var state = new CrashState(UUID.randomUUID(), "event", "build", Instant.parse("2026-01-01T00:00:00Z"),
                5, "symbolicated", null, "retrace_text", escaped, "A", "fingerprint", "v1", "resolved",
                UUID.randomUUID(), 1, "a".repeat(64), Instant.parse("2026-01-01T00:00:01Z"), null, "b".repeat(64), auxiliary);
        String encoded = codec.encode(state);
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length < 4 * 1024 * 1024);
        assertTrue(codec.matches(encoded, state));
    }
}
