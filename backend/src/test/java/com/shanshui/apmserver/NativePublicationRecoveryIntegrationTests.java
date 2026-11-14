package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.api.NativeCrash;
import com.shanshui.apmserver.crash.api.NativeStack;
import com.shanshui.apmserver.crash.internal.domain.CrashFact;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashStateStore;
import com.shanshui.apmserver.crash.internal.persistence.CrashStateJson;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationJobs;
import com.shanshui.apmserver.crash.internal.persistence.CrashSymbolicationPublications;
import com.shanshui.apmserver.crash.internal.port.CrashStatePort;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 隔离的真实双库验证 Native 发布丢失确认后的恢复。 */
@Testcontainers(disabledWithoutDocker = true)
class NativePublicationRecoveryIntegrationTests {
    /** 管理库仅供当前测试使用。 */
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 遥测库仅供当前测试使用。 */
    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "test").withEnv("CLICKHOUSE_PASSWORD", "test-only")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);

    /** 真实双库执行 Native 版本工作，原始采集身份与成功来源均保留。 */
    @Test
    void processesNativeWorkThroughActualDatabases() throws Exception {
        String schema = "round_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        var pg = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(pg);
        var manager = new DataSourceTransactionManager(pg);
        var properties = new ClickHouseProperties();
        properties.setUrl("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        properties.setDatabase("default"); properties.setUsername("test"); properties.setPassword("test-only");
        var client = new ClickHouseHttpClient(properties);
        for (String name : List.of("008_background_crash_symbolication.sql", "010_crash_diagnostics.sql", "011_native_crash_state.sql", "012_native_symbolication_state.sql")) {
            String ddl = new String(getClass().getResourceAsStream("/db/clickhouse/" + name).readAllBytes(), StandardCharsets.UTF_8);
            for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        }
        properties.setDatabase("apm");
        var mapper = new ObjectMapper();
        var store = new ClickHouseCrashStateStore(client, mapper, new QueryProperties());
        var jobs = new CrashSymbolicationJobs(jdbc, manager);
        var publications = new CrashSymbolicationPublications(jdbc, jobs, store, new CrashStateJson(mapper), manager, 100, 16777216);
        var symbols = mock(com.shanshui.apmserver.symbol.api.SymbolRegistry.class);
        var lease = mock(com.shanshui.apmserver.symbol.api.SymbolFileLease.class);
        UUID user = UUID.randomUUID(), app = UUID.randomUUID(), source = UUID.randomUUID();
        jdbc.update("INSERT INTO apm_user VALUES (?,'round@example.invalid','轮次测试','unused','ACTIVE',clock_timestamp(),clock_timestamp())", user);
        jdbc.update("INSERT INTO apm_app VALUES (?,'com.example.round','轮次测试',NULL,?,clock_timestamp(),clock_timestamp())", app, user);
        when(lease.symbolId()).thenReturn(source);
        when(lease.revision()).thenReturn(1);
        when(lease.sha256()).thenReturn("b".repeat(64));
        when(symbols.acquireNative(app, "1", "arm64-v8a", "abcd")).thenReturn(java.util.Optional.of(lease));
        when(symbols.symbolizeNative(eq(lease), eq(List.of("0x1")), any())).thenReturn(new com.shanshui.apmserver.symbol.api.NativeSymbolicationResult(
                Map.of("0x1", new com.shanshui.apmserver.symbol.api.NativeSymbolicationResult.Frame(
                        List.of(new com.shanshui.apmserver.symbol.api.NativeSymbolicationResult.Inline("function", "source.cc", 1, null)), null)), null));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var module = new NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd");
        var raw = new NativeCrash("arm64-v8a", 1, null, true, null, null, List.of(module),
                new NativeCrash.Backtrace("ok", null, false, 1L, List.of(new NativeCrash.Frame(0, "m", "0x1", null, null))), null);
        var base = new CrashState(app, "event", "build", now, 0, "pending", "native_symbolication_deferred", "raw_native", mapper.writeValueAsString(raw),
                "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "");
        var metadata = new EventMetadata(app, "com.example.round", "event", "crash", now, now, 2, "session", CrashTestSupport.PROCESS_ID,
                "device", "1", 1, "build", "test", "test", "35", "test", null, Map.of(), Map.of());
        assertTrue(store.accept(new CrashFact(metadata, "native", true, "", base.captureSha256()), base));
        var frameBatch = new com.shanshui.apmserver.crash.internal.application.NativeFrameBatch(symbols);
        var javaBatch = new com.shanshui.apmserver.crash.internal.application.NativeJavaStackBatch(symbols, mapper,
                new com.shanshui.apmserver.crash.internal.application.CrashSanitizer(CrashTestSupport.ingestProperties()));
        var round = new com.shanshui.apmserver.crash.internal.application.NativeCrashRound(
                new com.shanshui.apmserver.crash.internal.application.NativeSnapshotPreparation(mapper),
                new com.shanshui.apmserver.crash.internal.application.NativeBatchStages(frameBatch, javaBatch),
                new com.shanshui.apmserver.crash.internal.application.NativeSnapshotSummary(mapper), publications);
        var worker = new com.shanshui.apmserver.crash.internal.application.NativeBackgroundProcessor(jobs, store, publications, round, 100);
        jobs.wakeNative(app, "1");
        worker.runOnce();
        var current = store.current(app, "event").orElseThrow();
        assertEquals("native_stack", current.stackFormat());
        assertEquals("symbolicated", current.status());
        assertEquals(base.captureSha256(), current.captureSha256());
        assertEquals(base.fingerprint(), current.fingerprint());
        assertEquals(source, mapper.readValue(current.stackPayload(), NativeStack.class).backtrace().frames().getFirst().resolution().source().symbolFileId());
        assertEquals(1, jobs.nativeProgress(app, "1").publishedCount());
        assertFalse(current.nativeWorkRemaining());
    }

    /** 已落库但确认丢失时重启执行器，只能使用登记字节恢复并计数一次。 */
    @Test
    void replaysExactSnapshotAfterLostAcknowledgement() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        var pg = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(pg);
        var manager = new DataSourceTransactionManager(pg);
        var properties = new ClickHouseProperties();
        properties.setUrl("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        properties.setDatabase("default"); properties.setUsername("test"); properties.setPassword("test-only");
        var client = new ClickHouseHttpClient(properties);
        for (String name : List.of("008_background_crash_symbolication.sql", "010_crash_diagnostics.sql",
                "011_native_crash_state.sql", "012_native_symbolication_state.sql")) {
            String ddl = new String(getClass().getResourceAsStream("/db/clickhouse/" + name).readAllBytes(), StandardCharsets.UTF_8);
            for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        }
        properties.setDatabase("apm");
        var mapper = new ObjectMapper();
        var codec = new CrashStateJson(mapper);
        var store = new ClickHouseCrashStateStore(client, mapper, new QueryProperties());
        var fault = mock(CrashStatePort.class, delegatesTo(store));
        var jobs = new CrashSymbolicationJobs(jdbc, manager);
        var publications = new CrashSymbolicationPublications(jdbc, jobs, fault, codec, manager, 100, 16777216);
        UUID user = UUID.randomUUID(), app = UUID.randomUUID();
        jdbc.update("INSERT INTO apm_user VALUES (?,'native-recovery@example.invalid','恢复测试','unused','ACTIVE',clock_timestamp(),clock_timestamp())", user);
        jdbc.update("INSERT INTO apm_app VALUES (?,'com.example.nativerecovery','恢复测试',NULL,?,clock_timestamp(),clock_timestamp())", app, user);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var module = new NativeCrash.Module("m", "lib.so", null, "arm64-v8a", "abcd");
        var rawFrame = new NativeCrash.Frame(0, "m", "0x1", null, null);
        var raw = new NativeCrash("arm64-v8a", 1, null, true, null, null, List.of(module),
                new NativeCrash.Backtrace("ok", null, false, 1L, List.of(rawFrame)), null);
        var base = new CrashState(app, "event", "build", now, 0, "pending", "native_symbolication_deferred", "raw_native",
                mapper.writeValueAsString(raw), "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "");
        var metadata = new EventMetadata(app, "com.example.nativerecovery", "event", "crash", now, now, 2, "session",
                CrashTestSupport.PROCESS_ID, "device", "1", 1, "build", "test", "test", "35", "test", null, Map.of(), Map.of());
        assertTrue(store.accept(new CrashFact(metadata, "native", true, "", base.captureSha256()), base));
        var source = new NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 1, "b".repeat(64));
        var success = new NativeStack.Resolution("symbolicated", null, List.of(new NativeStack.Inline("f", "a.cc", 1, null)), source, source, now, null);
        var stack = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(module),
                new NativeStack.Backtrace("ok", null, false, 1L, List.of(new NativeStack.Frame(0, "m", "0x1", null, null, success))), null);
        var candidate = new CrashState(app, "event", "build", now, 0, "symbolicated", null, "native_stack", mapper.writeValueAsString(stack),
                "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, now, null, base.captureSha256(), "", false);
        jobs.wakeNative(app, "1");
        var lease = jobs.claimNative(UUID.randomUUID(), Duration.ofMinutes(1)).orElseThrow();
        var registered = publications.registerNative(lease, base, candidate, 1).orElseThrow();
        // 模拟数据库已写入，但执行器在收到确认前中断。
        doAnswer(call -> { store.replay(call.getArgument(0)); throw new EventStoreUnavailableException("模拟确认丢失"); })
                .when(fault).replay(anyString());
        assertThrows(EventStoreUnavailableException.class, () -> publications.publish(lease, registered));
        assertEquals(registered.version(), store.current(app, "event").orElseThrow().stateVersion());
        assertEquals(0, jobs.nativeProgress(app, "1").publishedCount());
        jdbc.update("UPDATE crash_symbolication_job SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE job_id=?", lease.jobId());
        var resumedJobs = new CrashSymbolicationJobs(jdbc, manager);
        var resumed = new CrashSymbolicationPublications(jdbc, resumedJobs, store, codec, manager, 100, 16777216);
        var next = resumedJobs.claimNative(UUID.randomUUID(), Duration.ofMinutes(1)).orElseThrow();
        // 模拟不同来源的候选，验证未确认登记仍先于新计算；此处未实际替换符号文件。
        var replacement = new NativeStack.Source("NATIVE_SO", UUID.randomUUID(), 2, "c".repeat(64));
        var newer = new NativeStack.Resolution("symbolicated", null, List.of(new NativeStack.Inline("new_f", "b.cc", 2, null)), replacement, replacement, now, null);
        var replacementStack = new NativeStack("arm64-v8a", 1, null, true, null, null, List.of(module),
                new NativeStack.Backtrace("ok", null, false, 1L, List.of(new NativeStack.Frame(0, "m", "0x1", null, null, newer))), null);
        var recomputed = new CrashState(app, "event", "build", now, 0, "symbolicated", null, "native_stack", mapper.writeValueAsString(replacementStack),
                "SIGSEGV", "fp", "native-tmp-v1", "temporary", null, null, null, now, null, base.captureSha256(), "", false);
        assertEquals(registered.payload(), resumed.registerNative(next, base, recomputed, 2).orElseThrow().payload());
        var replay = resumed.pendingNative(next, 10).getFirst();
        assertEquals(registered.payload(), replay.payload());
        assertTrue(resumed.publish(next, replay));
        assertFalse(resumed.publish(next, replay));
        assertEquals(1, resumedJobs.nativeProgress(app, "1").publishedCount());
        assertEquals(1, resumedJobs.nativeProgress(app, "1").failedUnitCount());
        assertEquals(source, mapper.readValue(store.current(app, "event").orElseThrow().stackPayload(), NativeStack.class)
                .backtrace().frames().getFirst().resolution().source());
        // 过期账本不再进入恢复队列，并在超过宽限期后有限清理。
        jdbc.update("UPDATE crash_symbolication_publication SET event_time=clock_timestamp()-interval '31 days', expires_at=clock_timestamp()-interval '6 minutes', published_at=NULL, pending_payload=? WHERE app_id=?", registered.payload(), app);
        var expired = resumed.existing(app, "event").orElseThrow();
        assertTrue(resumed.pendingNative(next, 10).isEmpty());
        assertFalse(resumed.publish(next, expired));
        assertEquals(1, resumed.cleanupExpired());
    }
}
