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
        properties.setDatabase("apm");
        ClickHouseCrashStateStore store = new ClickHouseCrashStateStore(client, new ObjectMapper(), new QueryProperties());
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
