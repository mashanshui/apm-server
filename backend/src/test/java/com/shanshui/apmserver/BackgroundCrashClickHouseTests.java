package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.domain.CrashFact;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashStateStore;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 真实隔离 ClickHouse 核验最新状态、两侧持久化与旧数据集隔离。 */
@Testcontainers(disabledWithoutDocker = true)
class BackgroundCrashClickHouseTests {
    /** 临时服务只使用安全合成账号。 */
    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "test").withEnv("CLICKHOUSE_PASSWORD", "test-only")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);

    /** 重试不能回退状态；迟到失败低版本不能覆盖成功。 */
    @Test
    void keepsOneLogicalResultAndSelectsLatestBeforeFiltering() throws Exception {
        // 专用容器数据库，不执行旧初始化脚本，不连接开发库。
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        properties.setDatabase("default");
        properties.setUsername("test");
        properties.setPassword("test-only");
        ClickHouseHttpClient client = new ClickHouseHttpClient(properties);
        // 直接执行实际版本化 DDL，确认迁移可重复且不依赖旧结构。
        String ddl = new String(getClass().getResourceAsStream("/db/clickhouse/008_background_crash_symbolication.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        String factTtl = ttl(client, "apm_crash_fact");
        String stateTtl = ttl(client, "apm_crash_state");
        // 先保存无诊断的旧事实，再执行新迁移两次，验证默认空值和幂等。
        client.execute("INSERT INTO apm.apm_crash_fact(app_id,event_id,event_type,package_name,build_id,event_time,received_time,schema_version,crash_kind,crash_fatal) VALUES ('11111111-1111-4111-8111-111111111111','migration-old','app_start','com.example','build',now64(3),now64(3),2,'',0)");
        client.execute("INSERT INTO apm.apm_crash_state(app_id,event_id,build_id,event_time,state_version,symbolication_status,symbolication_reason,stack_format,stack_payload,exception_type,fingerprint,fingerprint_version,grouping_kind) VALUES ('11111111-1111-4111-8111-111111111111','migration-old','build',now64(3),0,'pending','awaiting_processing','raw_chain','{}','X','fp','tmp-v1','temporary')");
        String diagnosticsDdl = new String(getClass().getResourceAsStream("/db/clickhouse/010_crash_diagnostics.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : diagnosticsDdl.split(";")) if (!statement.isBlank()) client.execute(statement);
        for (String statement : diagnosticsDdl.split(";")) if (!statement.isBlank()) client.execute(statement);
        // 在已有事实和状态上增量扩展约束，重复执行不能更改数据或 TTL。
        String nativeDdl = new String(getClass().getResourceAsStream("/db/clickhouse/011_native_crash_state.sql")
                .readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : nativeDdl.split(";")) if (!statement.isBlank()) client.execute(statement);
        for (String statement : nativeDdl.split(";")) if (!statement.isBlank()) client.execute(statement);
        var historical = new ObjectMapper().readTree(client.execute("SELECT diagnostics_payload,capture_sha256 FROM apm.apm_crash_fact WHERE event_id='migration-old' FORMAT JSONEachRow"));
        assertEquals("", historical.path("diagnostics_payload").asText());
        assertEquals("", historical.path("capture_sha256").asText());
        var historicalState = new ObjectMapper().readTree(client.execute("SELECT thread_stacks_payload,capture_sha256 FROM apm.apm_crash_state WHERE event_id='migration-old' FORMAT JSONEachRow"));
        assertEquals("", historicalState.path("thread_stacks_payload").asText());
        assertEquals("", historicalState.path("capture_sha256").asText());
        assertEquals(factTtl, ttl(client, "apm_crash_fact"));
        assertEquals(stateTtl, ttl(client, "apm_crash_state"));
        client.execute("ALTER TABLE apm.apm_crash_fact DELETE WHERE event_id='migration-old' SETTINGS mutations_sync=2");
        client.execute("ALTER TABLE apm.apm_crash_state DELETE WHERE event_id='migration-old' SETTINGS mutations_sync=2");
        properties.setDatabase("apm");
        ClickHouseCrashStateStore store = new ClickHouseCrashStateStore(client, new ObjectMapper(), new QueryProperties());
        store.requireCurrentSchema();
        UUID app = UUID.randomUUID();
        UUID symbol = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant occurred = now.minusSeconds(3600);
        EventMetadata metadata = new EventMetadata(app, "com.example.test", "event", "crash", occurred,
                now, 2, "session", "11111111-1111-4111-8111-111111111111", "device", "1", 1,
                "build", "production", "official", "16", "test", "wifi", Map.of(), Map.of());
        CrashState pending = state(app, "event", occurred, 0, "pending", null, null, now);
        // 单侧已写只有孤立状态，不得被详情或后台读取。
        store.publish(pending);
        assertTrue(store.current(app, "event").isEmpty());
        assertTrue(store.candidates(app, "build", symbol, 1, now.minusSeconds(86400), now, 100).isEmpty());
        assertTrue(store.accept(new CrashFact(metadata, "jvm", true), pending));
        assertFalse(store.accept(new CrashFact(metadata, "jvm", true), pending));
        assertEquals(pending, store.current(app, "event").orElseThrow());
        assertEquals(1, store.candidates(app, "build", symbol, 1, now.minusSeconds(86400), now, 100).size());
        CrashState success = state(app, "event", occurred, 5, "symbolicated", symbol, null, now);
        store.publish(success);
        store.publish(success);
        store.publish(state(app, "event", occurred, 4, "failed", symbol, now.plusSeconds(3600), now));
        assertFalse(store.accept(new CrashFact(metadata, "jvm", true), pending));
        assertEquals(success, store.current(app, "event").orElseThrow());
        assertTrue(store.candidates(app, "build", symbol, 1, now.minusSeconds(86400), now, 100).isEmpty());
        assertTrue(store.current(UUID.randomUUID(), "event").isEmpty());
        assertEquals(1, new ObjectMapper().readTree(client.execute("SELECT count() AS n FROM apm_crash_fact FINAL FORMAT JSONEachRow")).path("n").asInt());
        // Native 共享构建时按原始状态保存，不进入 JVM mapping 候选。
        EventMetadata nativeMetadata = new EventMetadata(app, metadata.packageName(), "native-event", "crash", occurred,
                now, 2, "session", metadata.processId(), "device", "1", 1, "build", "production", "official",
                "16", "test", "wifi", Map.of(), Map.of());
        CrashState nativeState = new CrashState(app, "native-event", "build", occurred, 0, "pending",
                "native_symbolication_deferred", "raw_native", "{\"abi\":\"arm64-v8a\"}", "SIGSEGV", "native-fp",
                "native-tmp-v1", "temporary", null, null, null, null, null, "a".repeat(64), "{}");
        CrashFact nativeFact = new CrashFact(nativeMetadata, "native", true, "{\"memory\":{}}", "a".repeat(64));
        assertTrue(store.accept(nativeFact, nativeState));
        assertFalse(store.accept(nativeFact, nativeState));
        assertEquals(nativeState, store.current(app, "native-event").orElseThrow());
        // Native 状态先落盘而事实缺失时，不同摘要不得与原状态拼接。
        EventMetadata orphanNativeMetadata = new EventMetadata(app, metadata.packageName(), "native-orphan", "crash", occurred,
                now, 2, "session", metadata.processId(), "device", "1", 1, "build", "production", "official",
                "16", "test", "wifi", Map.of(), Map.of());
        CrashState orphanNative = new CrashState(app, "native-orphan", "build", occurred, 0, "pending",
                "native_symbolication_deferred", "raw_native", "{}", "SIGSEGV", "native-fp", "native-tmp-v1",
                "temporary", null, null, null, null, null, "c".repeat(64), "{}");
        store.publish(orphanNative);
        CrashState otherNative = new CrashState(app, "native-orphan", "build", occurred, 0, "pending",
                "native_symbolication_deferred", "raw_native", "{}", "SIGSEGV", "native-fp", "native-tmp-v1",
                "temporary", null, null, null, null, null, "d".repeat(64), "{}");
        assertThrows(com.shanshui.apmserver.telemetry.api.EventValidationException.class, () -> store.accept(
                new CrashFact(orphanNativeMetadata, "native", true, "{}", "d".repeat(64)), otherNative));
        assertTrue(store.current(app, "native-orphan").isEmpty());
        assertTrue(store.accept(new CrashFact(orphanNativeMetadata, "native", true, "{}", "c".repeat(64)), orphanNative));
        assertEquals(orphanNative, store.current(app, "native-orphan").orElseThrow());
        assertTrue(store.candidates(app, "build", symbol, 1, now.minusSeconds(86400), now, 100).isEmpty());
        assertEquals(1, new ObjectMapper().readTree(client.execute("SELECT count() AS n FROM apm_crash_fact FINAL WHERE event_id='native-event' FORMAT JSONEachRow")).path("n").asInt());
        assertThrows(RuntimeException.class, () -> client.execute("INSERT INTO apm_crash_state(app_id,event_id,build_id,event_time,state_version,symbolication_status,symbolication_reason,stack_format,stack_payload,fingerprint,fingerprint_version,grouping_kind) VALUES ('" + app + "','invalid-native','build',now64(3),0,'failed','native_symbolication_deferred','raw_native','{}','fp','native-tmp-v1','temporary')"));
        CrashState changedNative = new CrashState(app, "native-event", "build", occurred, 0, "pending",
                "native_symbolication_deferred", "raw_native", "{\"abi\":\"x86_64\"}", "SIGSEGV", "native-fp",
                "native-tmp-v1", "temporary", null, null, null, null, null, "b".repeat(64), "{}");
        var nativeConflict = assertThrows(com.shanshui.apmserver.telemetry.api.EventValidationException.class,
                () -> store.accept(new CrashFact(nativeMetadata, "native", true, "{}", "b".repeat(64)), changedNative));
        assertEquals("CRASH_CONTENT_CONFLICT", nativeConflict.getIssues().getFirst().code());
        client.execute("ALTER TABLE apm_crash_state DELETE WHERE event_id='native-event' SETTINGS mutations_sync=2");
        assertTrue(store.current(app, "native-event").isEmpty());
        assertFalse(store.accept(nativeFact, nativeState));
        assertEquals(nativeState, store.current(app, "native-event").orElseThrow());
        // 模拟事实存在而状态丢失；原 eventId 重试必须补齐另一侧后才确认重复。
        client.execute("ALTER TABLE apm_crash_state DELETE WHERE event_id='event' SETTINGS mutations_sync=2");
        assertTrue(store.current(app, "event").isEmpty());
        assertFalse(store.accept(new CrashFact(metadata, "jvm", true), pending));
        assertEquals(pending, store.current(app, "event").orElseThrow());
        store.publish(success);
        // 确定性失败在同一 mapping 下不空转，新身份可以恢复处理。
        CrashState failed = state(app, "failed-event", occurred, 6, "failed", symbol, null, now);
        EventMetadata failedMetadata = new EventMetadata(app, metadata.packageName(), "failed-event", "crash", occurred,
                now, 2, "session", metadata.processId(), "device", "1", 1,
                "build", "production", "official", "16", "test", "wifi", Map.of(), Map.of());
        store.accept(new CrashFact(failedMetadata, "jvm", true), state(app, "failed-event", occurred, 0, "pending", null, null, now));
        store.publish(failed);
        assertTrue(store.candidates(app, "build", symbol, 1, now.minusSeconds(86400), now, 100).isEmpty());
        assertEquals(1, store.candidates(app, "build", UUID.randomUUID(), 1, now.minusSeconds(86400), now, 100).size());
        // 仅轻量列投影的查询计划不能读取堆栈列。
        String plan = client.execute("EXPLAIN PLAN actions=1 SELECT event_id,fingerprint,grouping_kind FROM apm_crash_state FINAL WHERE app_id='" + app + "'");
        assertFalse(plan.contains("stack_payload"), plan);
        // 状态先写且事实未写时，不同诊断摘要不能与原状态拼接。
        EventMetadata partialMetadata = new EventMetadata(app, metadata.packageName(), "partial-diagnostics", "crash", occurred,
                now, 2, "session", metadata.processId(), "device", "1", 1, "digest-build", "production", "official",
                "16", "test", "wifi", Map.of(), Map.of());
        CrashState original = diagnosticState(partialMetadata, "a".repeat(64));
        store.publish(original);
        var conflict = assertThrows(com.shanshui.apmserver.telemetry.api.EventValidationException.class, () -> store.accept(
                new CrashFact(partialMetadata, "jvm", true, "{\"memory\":{}}", "b".repeat(64)),
                diagnosticState(partialMetadata, "b".repeat(64))));
        assertEquals("CRASH_CONTENT_CONFLICT", conflict.getIssues().getFirst().code());
        assertTrue(store.current(app, "partial-diagnostics").isEmpty());
        CrashFact originalFact = new CrashFact(partialMetadata, "jvm", true, "{\"memory\":{}}", "a".repeat(64));
        assertTrue(store.accept(originalFact, original));
        assertFalse(store.accept(originalFact, original));
        assertEquals(original, store.current(app, "partial-diagnostics").orElseThrow());
        // 原始状态仍是新三态；无法解析的状态在构造入口明确失败。
        assertThrows(IllegalArgumentException.class, () -> state(app, "bad", occurred, 1, "raw_only", null, null, now));
        // 缺少新结构必须明确失败，即便有旧表也不回退。
        client.execute("CREATE TABLE apm_crash_detail (event_id String) ENGINE=Memory");
        client.execute("INSERT INTO apm_crash_detail VALUES ('legacy')");
        assertTrue(store.current(app, "legacy").isEmpty());
        client.execute("DROP TABLE apm_crash_state");
        assertThrows(RuntimeException.class, () -> store.current(app, "event"));
        assertEquals(1, new ObjectMapper().readTree(client.execute("SELECT count() AS n FROM apm_crash_detail FORMAT JSONEachRow")).path("n").asInt());
    }

    /** 从实际建表语句提取 TTL，验证加列不会改变保留期。 */
    private String ttl(ClickHouseHttpClient client, String table) {
        String row = client.execute("SELECT create_table_query FROM system.tables WHERE database='apm' AND name='" + table + "' FORMAT JSONEachRow");
        String ddl = new ObjectMapper().readTree(row).path("create_table_query").asText();
        int start = ddl.lastIndexOf(" TTL ");
        int end = ddl.indexOf(" SETTINGS ", start);
        if (start < 0) throw new IllegalStateException("建表语句缺少 TTL");
        return ddl.substring(start, end < 0 ? ddl.length() : end);
    }

    /** 构造携带摘要和辅助线程材料的初始状态。 */
    private CrashState diagnosticState(EventMetadata metadata, String digest) {
        return new CrashState(metadata.appId(), metadata.eventId(), metadata.buildId(), metadata.occurredAt(), 0,
                "pending", "awaiting_processing", "raw_chain", "{}", "E", "fp", "tmp-v1", "temporary",
                null, null, null, null, null, digest, "[]");
    }

    /** 合成单份原始或还原状态，来源身份固定。 */
    private CrashState state(UUID app, String event, Instant time, long version, String status,
                              UUID symbol, Instant retry, Instant now) {
        // 成功只有还原文本，其他状态只有原始异常链。
        boolean success = "symbolicated".equals(status);
        return new CrashState(app, event, "build", time, version, status, success ? null : "waiting_mapping",
                success ? "retrace_text" : "raw_chain", success ? "java.lang.RuntimeException\n\tat com.example.Main.call(Main.java:1)" : "{\"kind\":\"jvm\",\"fatal\":true,\"throwableChain\":[]}",
                "java.lang.RuntimeException", success ? "resolved-fingerprint" : "temporary-fingerprint",
                success ? "resolved-v1" : "tmp-v1", success ? "resolved" : "temporary", symbol,
                symbol == null ? null : 1, symbol == null ? null : "a".repeat(64), symbol == null ? null : now, retry);
    }
}
