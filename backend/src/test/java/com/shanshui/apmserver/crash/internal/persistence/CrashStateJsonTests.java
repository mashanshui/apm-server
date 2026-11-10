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
