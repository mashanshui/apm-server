package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.crash.api.CrashDiagnostics;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.crash.api.CrashMetrics;
import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.api.CrashSymbolicationWork;
import com.shanshui.apmserver.crash.api.CrashThreadStack;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashStateStore;
import com.shanshui.apmserver.crash.internal.domain.CrashFact;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.symbol.api.SymbolRegistry;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 验证诊断拆分、稳定摘要与授权查询使用的单事件组合。 */
class CrashDiagnosticsStorageTests {
    /** 当前 JSON 编码器。 */ private final ObjectMapper mapper = new ObjectMapper();
    /** 每个测试实例固定发生时间，避免跨越自然日导致摘要变化。 */
    private final long occurredAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS).toEpochMilli();

    /** 原始线程帧不混入不变上下文；摘要不受 Map 插入顺序影响。 */
    @Test
    void separatesFramesAndUsesStableIdentityDigest() {
        var diagnostics = diagnostics("first");
        Map<String, Object> left = new LinkedHashMap<>();
        left.put("a", "one"); left.put("b", "two");
        Map<String, Object> right = new LinkedHashMap<>();
        right.put("b", "two"); right.put("a", "one");
        var storage = new CrashDiagnosticsStorage(mapper);
        var first = storage.encode(event("session", diagnostics, left));
        var retry = storage.encode(event("session", diagnostics, right));
        assertEquals(first.captureSha256(), retry.captureSha256());
        assertNotEquals(first.captureSha256(), storage.encode(event("other-session", diagnostics, left)).captureSha256());
        assertNotEquals(first.captureSha256(), storage.encode(event("session", diagnostics("second"), left)).captureSha256());
        assertFalse(first.diagnosticsPayload().contains("\"frames\""));
        assertTrue(first.threadStacksPayload().contains("\"raw_frames\""));
        var threadStacks = mapper.readValue(first.threadStacksPayload(), CrashThreadStack[].class);
        assertEquals("pending", threadStacks[0].symbolicationStatus());
        assertEquals("not_applicable", threadStacks[1].symbolicationStatus());
    }

    /** 合法数据可查询；重复相同内容保持幂等，变更日志返回永久内容冲突。 */
    @Test
    void preservesDetailAndRejectsDifferentCaptureOnRetry() {
        var properties = new IngestConfigurationProperties();
        properties.setDeviceHashSalt("test-salt");
        var storage = new StorageProperties();
        var repository = new InMemoryCrashRepository(storage);
        var processor = new CrashEventProcessor(new CrashEventValidator(properties, mapper), new CrashSanitizer(properties),
                new CrashFingerprintService(), repository, mapper, mock(CrashSymbolicationWork.class), mock(SymbolRegistry.class));
        UUID app = UUID.randomUUID();
        var first = event("session", diagnostics("first"), Map.of());
        assertEquals(1, processor.ingest(app, first, Instant.now()).appendResult().accepted());
        assertEquals(1, processor.ingest(app, first, Instant.now()).appendResult().duplicate());
        var conflict = assertThrows(EventValidationException.class,
                () -> processor.ingest(app, event("session", diagnostics("second"), Map.of()), Instant.now()));
        assertTrue(conflict.getIssues().stream().anyMatch(issue -> issue.code().equals("CRASH_CONTENT_CONFLICT")));
        var queries = new CrashQueryService(repository, new QueryProperties(), storage, mock(CrashMetrics.class));
        var detail = queries.event(app, "event");
        assertNull(((CrashPayload) detail.stack().content()).diagnostics());
        assertEquals(0L, detail.diagnostics().memory().javaHeapUsedBytes());
        assertEquals(2, detail.diagnostics().otherThreads().threads().size());
        assertEquals("pending", detail.diagnostics().otherThreads().threads().getFirst().symbolicationStatus());
        assertEquals("not_applicable", detail.diagnostics().otherThreads().threads().getLast().symbolicationStatus());
        assertEquals("first", detail.diagnostics().logcat().main().lines().getFirst());
    }

    /** 状态先写而事实失败后，只允许原内容补齐，历史事件不能追加新现场。 */
    @Test
    void repairsOrphanOnlyForTheSameCapture() {
        UUID app = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        EventMetadata metadata = new EventMetadata(app, "com.example.app", "partial", "crash", now, now,
                2, "session", "11111111-1111-4111-8111-111111111111", "device", "1", 1,
                "build", "test", "test", "35", "test", null, Map.of(), Map.of());
        var store = new InMemoryCrashStateStore(new StorageProperties(), mapper);
        String originalDigest = "a".repeat(64);
        String differentDigest = "b".repeat(64);
        CrashState original = pending(metadata, originalDigest);
        store.publish(original);
        var conflict = assertThrows(EventValidationException.class, () -> store.accept(
                new CrashFact(metadata, "jvm", true, "{\"memory\":{}}", differentDigest), pending(metadata, differentDigest)));
        assertEquals("CRASH_CONTENT_CONFLICT", conflict.getIssues().getFirst().code());
        assertTrue(store.current(app, "partial").isEmpty());
        CrashFact fact = new CrashFact(metadata, "jvm", true, "{\"memory\":{}}", originalDigest);
        assertTrue(store.accept(fact, original));
        assertFalse(store.accept(fact, original));
        assertEquals(original, store.current(app, "partial").orElseThrow());

        EventMetadata historical = new EventMetadata(app, metadata.packageName(), "historical", metadata.eventType(),
                now, now, 2, metadata.sessionId(), metadata.processId(), metadata.anonymousDeviceId(), metadata.appVersion(),
                metadata.versionCode(), metadata.buildId(), metadata.environment(), metadata.channel(), metadata.osVersion(),
                metadata.deviceModel(), metadata.networkType(), Map.of(), Map.of());
        assertTrue(store.accept(new CrashFact(historical, "jvm", true), pending(historical, "")));
        assertThrows(EventValidationException.class, () -> store.accept(
                new CrashFact(historical, "jvm", true, "{\"memory\":{}}", originalDigest), pending(historical, originalDigest)));
        assertEquals("", store.current(app, "historical").orElseThrow().captureSha256());
    }

    /** 创建可参与部分写入测试的初始状态。 */
    private CrashState pending(EventMetadata metadata, String digest) {
        return new CrashState(metadata.appId(), metadata.eventId(), metadata.buildId(), metadata.occurredAt(), 0,
                "pending", "awaiting_processing", "raw_chain", "{}", "E", "fp", "tmp-v1", "temporary",
                null, null, null, null, null, digest, "[]");
    }

    /** 构造含有一个原始帧和一个空帧的合成诊断。 */
    private CrashDiagnostics diagnostics(String line) {
        String json = """
                {"otherThreads":{"status":"ok","truncated":false,"observedCount":2,"omittedCount":0,
                  "threads":[{"javaId":"1","name":"same","state":"WAITING","priority":5,
                    "daemon":false,"mainThread":true,"truncated":false,
                    "frames":[{"className":"a.A","methodName":"run","lineNumber":1}]},
                   {"javaId":"2","name":"same","state":"WAITING","priority":5,
                    "daemon":false,"mainThread":false,"truncated":false,"frames":[]}]},
                 "memory":{"status":"ok","truncated":false,"javaHeapUsedBytes":0},
                 "logcat":{"status":"ok","truncated":false,
                   "main":{"status":"ok","truncated":false,"lines":["%s"]}}}
                """.formatted(line);
        return mapper.readValue(json, CrashDiagnostics.class);
    }

    /** 事件发生时间固定，避免重试因时钟变化而改变身份。 */
    private CrashIngestCommand event(String session, CrashDiagnostics diagnostics, Map<String, Object> attributes) {
        return new CrashIngestCommand(2, "event", "crash", occurredAt,
                session, "11111111-1111-4111-8111-111111111111", "device", "com.example.app", "1", 1,
                "build", "test", "test", "35", "synthetic", null, Map.of(), attributes,
                new CrashPayload("jvm", true, List.of(new ThrowableNode("E", "failure",
                        List.of(new StackFrame("A", "run", "A.kt", 1, true)))), diagnostics));
    }
}
