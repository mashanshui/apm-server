package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.application.CrashQueryService;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.persistence.*;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 详情只读已发布状态，固定来源不再依赖当前符号文件。 */
class CrashPersistentSymbolicationTests {
    /** pending/failed 可读，成功仅保留完整还原输出，重复详情不重新还原。 */
    @Test
    void readsOneStoredStackAndItsActualSource() {
        // 后台和前台共用同一当前数据集。
        ObjectMapper mapper = new ObjectMapper();
        var properties = CrashTestSupport.storageProperties();
        InMemoryCrashStateStore states = new InMemoryCrashStateStore(properties, mapper);
        InMemoryCrashRepository repository = new InMemoryCrashRepository(properties, states);
        UUID app = TestAppIds.id("persistent-symbol-app");
        CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties()).ingest(app, CrashTestSupport.batch(List.of(
                CrashTestSupport.event("event", "crash", "session", "device", "1", CrashTestSupport.nowMillis(),
                        CrashTestSupport.crash("a.b.Exception", "test", 12, "a.b.Main")))));
        CrashQueryService queries = new CrashQueryService(repository, CrashTestSupport.queryProperties(), properties,
                new MicrometerTelemetryMetrics(new SimpleMeterRegistry()));
        var pending = queries.event(app, "event");
        assertEquals("pending", pending.symbolicationStatus());
        assertEquals("raw_chain", pending.stack().format());
        assertTrue(pending.fatal());
        assertEquals("jvm", pending.crashKind());
        CrashState initial = states.current(app, "event").orElseThrow();
        UUID oldSymbol = UUID.randomUUID();
        Instant processed = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CrashState failed = new CrashState(app, "event", initial.buildId(), initial.eventTime(), 1, "failed", "output_limit",
                "raw_chain", initial.stackPayload(), initial.exceptionType(), initial.fingerprint(), "tmp-v1", "temporary",
                oldSymbol, 7, "7".repeat(64), processed, null);
        states.publish(failed);
        assertEquals("failed", queries.event(app, "event").symbolicationStatus());
        assertEquals("output_limit", queries.event(app, "event").symbolicationReason());
        CrashState success = new CrashState(app, "event", initial.buildId(), initial.eventTime(), 2, "symbolicated", null,
                "retrace_text", "java.lang.RuntimeException\n\tat com.example.Main.run(Main.java:1)\n<OR> at com.example.Other.run(Other.java:2)",
                "java.lang.RuntimeException", "resolved-fingerprint", "resolved-v1", "resolved", oldSymbol, 7, "7".repeat(64), processed, null);
        states.publish(success);
        var first = queries.event(app, "event");
        var second = queries.event(app, "event");
        assertEquals(first, second);
        assertEquals(first, queries.analysisSnapshot(app, "event").detail());
        assertEquals(success.stackPayload(), first.stack().content());
        assertEquals("retrace_text", first.stack().format());
        assertEquals(oldSymbol, first.symbolFileId());
        assertEquals(7, first.symbolFileRevision());
        assertEquals("7".repeat(64), first.symbolFileSha256());
        assertFalse(mapper.valueToTree(first).has("rawCrash"));
        assertFalse(mapper.valueToTree(first).has("symbolicatedStackText"));
        assertThrows(com.shanshui.apmserver.platform.api.QueryValidationException.class, () -> queries.event(UUID.randomUUID(), "event"));
        assertEquals(1, repository.findAll(app).size());
    }
}
