package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.domain.CrashQueryCommand;
import com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashRepository;
import com.shanshui.apmserver.crash.internal.application.CrashQueryService;
import com.shanshui.apmserver.crash.internal.application.CrashReferenceQueries;
import com.shanshui.apmserver.crash.internal.application.CrashCursor;
import com.shanshui.apmserver.crash.internal.domain.AppStartEvent;
import com.shanshui.apmserver.crash.internal.domain.CrashEvent;
import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.platform.api.QueryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 ClickHouse 验证逻辑去重、跨桶与指纹分母。 */
@Testcontainers
class ClickHouseCrashQueryIntegrationTests {
    /** 保留期内的固定 UTC 测试日，避免真实 TTL 清理历史样本。 */
    private static final String DAY = java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS).toString().substring(0,10);

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "apm_test")
            .withEnv("CLICKHOUSE_PASSWORD", "apm_test_password")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123);

    @Test
    void overviewAndTrendMatchReferenceSemantics() throws Exception {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        properties.setDatabase("default");
        properties.setUsername("apm_test");
        properties.setPassword("apm_test_password");
        ClickHouseHttpClient client = new ClickHouseHttpClient(properties);
        // 使用当前实际 DDL，不建立旧 Crash 数据集。
        String ddl = new String(getClass().getResourceAsStream("/db/clickhouse/008_background_crash_symbolication.sql").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        String diagnosticsDdl = new String(getClass().getResourceAsStream("/db/clickhouse/010_crash_diagnostics.sql").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        for (String statement : diagnosticsDdl.split(";")) if (!statement.isBlank()) client.execute(statement);
        properties.setDatabase("apm");
        UUID appId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID other = UUID.fromString("22222222-2222-4222-8222-222222222222");
        insert(client, appId, "start-1", "app_start", "10:00:00", "s1", "", "");
        insert(client, appId, "start-1", "app_start", "10:00:00", "s1", "", "");
        insert(client, appId, "crash-1", "crash", "10:15:00", "s1", "d1", "fp-a");
        insert(client, appId, "crash-2", "crash", "11:00:00", "s1", "d1", "fp-a");
        insert(client, appId, "start-2", "app_start", "11:00:00", "s2", "", "");
        insert(client, appId, "crash-3", "crash", "11:30:00", "", "d2", "fp-b");
        insert(client, other, "crash-4", "crash", "10:00:00", "s9", "d9", "fp-a");

        StorageProperties storage = CrashTestSupport.storageProperties();
        storage.setMode("clickhouse");
        CrashQueryService query = new CrashQueryService(new ClickHouseCrashRepository(client, new ObjectMapper()),
                CrashTestSupport.queryProperties(), storage,
                new MicrometerTelemetryMetrics(new SimpleMeterRegistry()));
        String from = "" + DAY + "T10:00:00Z";
        String to = "" + DAY + "T12:00:00Z";
        var overview = query.overview(appId, from, to, CrashQueryCommand.empty()).stats();
        assertEquals(2, overview.startedSessions());
        assertEquals(3, overview.crashEvents());
        assertEquals(1, overview.crashedSessions());
        assertEquals(2, overview.affectedDevices());
        assertEquals(500.0, overview.crashRatePer1000Sessions());
        List<CrashStoredSignal> reference = List.of(
                signal(appId, "start-1", "app_start", "10:00:00", "s1", "", ""),
                signal(appId, "crash-1", "crash", "10:15:00", "s1", "d1", "fp-a"),
                signal(appId, "crash-2", "crash", "11:00:00", "s1", "d1", "fp-a"),
                signal(appId, "start-2", "app_start", "11:00:00", "s2", "", ""),
                signal(appId, "crash-3", "crash", "11:30:00", "", "d2", "fp-b"));
        var normalized = query.filter(appId, from, to, CrashQueryCommand.empty());
        assertEquals(CrashReferenceQueries.overview(reference, normalized), overview);
        var points = query.trend(appId, from, to, "hour", CrashQueryCommand.empty()).points();
        assertEquals(2, points.size());
        assertEquals(CrashReferenceQueries.trend(reference, normalized, "hour"), points);
        assertEquals(1, points.get(0).stats().crashEvents());
        assertEquals(2, points.get(1).stats().crashEvents());
        var onlyFp = query.overview(appId, from, to, CrashQueryCommand.empty().withFingerprint("fp-a")).stats();
        assertEquals(2, onlyFp.startedSessions());
        assertEquals(2, onlyFp.crashEvents());
        var noStarts = query.overview(appId, from, to, CrashQueryCommand.empty().withFingerprint("fp-b"))
                .stats();
        assertEquals(2, noStarts.startedSessions());
        assertEquals(1, noStarts.crashEvents());
        assertNull(query.overview(appId, "" + DAY + "T09:00:00Z", from, CrashQueryCommand.empty())
                .stats().crashRatePer1000Sessions());
        assertEquals("denominator_insufficient", query.overview(appId, "" + DAY + "T11:30:00Z", to,
                CrashQueryCommand.empty()).stats().status());

        // 仓库 Grafana 资源同样只读当前数据集；真实执行全部面板 SQL，禁止残留旧表。
        var dashboard = new ObjectMapper().readTree(getClass().getResourceAsStream("/grafana/dashboards/jvm-crash.json"));
        for (var panel : dashboard.path("panels")) {
            for (var target : panel.path("targets")) {
                String sql = target.path("query").asText().replace("$app_id", appId.toString())
                        .replace("$from", Long.toString(Instant.parse(from).getEpochSecond()))
                        .replace("$to", Long.toString(Instant.parse(to).getEpochSecond()));
                for (String dimension : new String[]{"app_version", "channel", "environment", "os_version", "device_model", "fingerprint"}) {
                    sql = sql.replace("$" + dimension, "'All'");
                }
                assertTrue(!sql.contains("apm_event_raw") && !sql.contains("stack_payload"));
                var rows = client.execute(sql + " FORMAT JSONEachRow");
                assertTrue(!rows.isBlank());
                if (panel.path("id").asInt() == 1) assertEquals(2, new ObjectMapper().readTree(rows).path("started_sessions").asInt());
                if (panel.path("id").asInt() == 2) assertEquals(500.0, new ObjectMapper().readTree(rows).path("crash_rate_per_1000_sessions").asDouble());
            }
        }

        CrashQueryCommand firstPage = CrashQueryCommand.empty().withLimit(1);
        var issuesOne = query.issues(appId, from, to, firstPage);
        assertEquals(CrashReferenceQueries.issues(reference, query.filter(appId, from, to, firstPage))
                .items(), issuesOne.issues());
        assertEquals("fp-a", issuesOne.issues().getFirst().fingerprint());
        assertNotNull(issuesOne.nextCursor());
        var issuesTwo = query.issues(appId, from, to, firstPage.withCursor(issuesOne.nextCursor()));
        assertEquals("fp-b", issuesTwo.issues().getFirst().fingerprint());
        assertNull(issuesTwo.nextCursor());
        assertEquals(0, query.issues(appId, from, to, firstPage.withCursor(
                CrashCursor.issue(query.filter(appId, from, to, firstPage),
                        issuesTwo.issues().getFirst()))).issues().size());
        var eventsOne = query.events(appId, "fp-a", from, to, firstPage);
        assertEquals(CrashReferenceQueries.events(reference,
                query.filter(appId, from, to, firstPage.withFingerprint("fp-a")), "fp-a")
                .items(), eventsOne.events());
        assertEquals("crash-2", eventsOne.events().getFirst().eventId());
        var eventsTwo = query.events(appId, "fp-a", from, to, firstPage.withCursor(eventsOne.nextCursor()));
        assertEquals("crash-1", eventsTwo.events().getFirst().eventId());
        assertNull(eventsTwo.nextCursor());
        assertEquals(0, query.events(appId, "fp-a", from, to, firstPage.withCursor(
                CrashCursor.event(query.filter(appId, from, to, firstPage.withFingerprint("fp-a")),
                        eventsTwo.events().getFirst()))).events().size());
        assertEquals("INVALID_CURSOR", assertThrows(com.shanshui.apmserver.platform.api.QueryValidationException.class,
                () -> query.issues(other, from, to, firstPage.withCursor(issuesOne.nextCursor()))).getCode());
        assertEquals("INVALID_CURSOR", assertThrows(com.shanshui.apmserver.platform.api.QueryValidationException.class,
                () -> query.issues(appId, from, to, firstPage.withCursor("fp-a"))).getCode());
        assertEquals("INVALID_CURSOR", assertThrows(com.shanshui.apmserver.platform.api.QueryValidationException.class,
                () -> query.events(appId, "fp-b", from, to, firstPage.withCursor(eventsOne.nextCursor()))).getCode());

        // 同一毫秒的事件依照 eventId 升序续页，不依赖表的物理读取顺序。
        insert(client, appId, "crash-2b", "crash", "11:00:00", "s1", "d1", "fp-a");
        var tiedFirst = query.events(appId, "fp-a", from, to, firstPage);
        var tiedSecond = query.events(appId, "fp-a", from, to,
                firstPage.withCursor(tiedFirst.nextCursor()));
        var tiedThird = query.events(appId, "fp-a", from, to,
                firstPage.withCursor(tiedSecond.nextCursor()));
        assertEquals("crash-2", tiedFirst.events().getFirst().eventId());
        assertEquals("crash-2b", tiedSecond.events().getFirst().eventId());
        assertEquals("crash-1", tiedThird.events().getFirst().eventId());
        assertNull(tiedThird.nextCursor());

        // 从 ClickHouse query_log 确认摘要查询不关联堆栈表，也没有传输整批原始行。
        client.execute("SYSTEM FLUSH LOGS");
        String log = client.execute("SELECT count() AS query_count, max(result_rows) AS max_result_rows, "
                + "countIf(position(query, 'apm_crash_detail') > 0) AS detail_joins "
                + "FROM system.query_log WHERE type = 'QueryFinish' "
                + "AND query_kind='Select' AND position(query, 'apm_crash_fact') > 0 "
                + "AND position(query, 'system.query_log') = 0 FORMAT JSONEachRow");
        tools.jackson.databind.JsonNode evidence = new ObjectMapper().readTree(log);
        assertTrue(evidence.path("query_count").asLong() >= 5);
        assertTrue(evidence.path("max_result_rows").asLong() <= 2);
        assertEquals(0, evidence.path("detail_joins").asLong());

        QueryProperties tiny = CrashTestSupport.queryProperties();
        tiny.setMaxRowsToRead(1);
        CrashQueryService limited = new CrashQueryService(new ClickHouseCrashRepository(client,
                new ObjectMapper(), tiny), tiny, storage,
                new MicrometerTelemetryMetrics(new SimpleMeterRegistry()));
        assertEquals("QUERY_RESOURCE_LIMIT", assertThrows(com.shanshui.apmserver.platform.api.QueryValidationException.class,
                () -> limited.overview(appId, from, to, CrashQueryCommand.empty())).getCode());

        // 补还原只改变状态归组，不允许重写事实维度或原日期。
        var original = (CrashEvent) signal(appId, "crash-1", "crash", "10:15:00", "s1", "d1", "fp-a");
        new com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashStateStore(client, new ObjectMapper(), CrashTestSupport.queryProperties()).publish(
                new com.shanshui.apmserver.crash.internal.domain.CrashState(appId, "crash-1", "build-1", original.occurredAt(), 2, "symbolicated", null,
                        "retrace_text", "java.lang.IllegalStateException\n\tat com.example.Main.run(Main.java:1)", "java.lang.IllegalStateException",
                        "fp-resolved", "resolved-v1", "resolved", UUID.randomUUID(), 1, "a".repeat(64), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), null));
        assertEquals(4, query.overview(appId, from, to, CrashQueryCommand.empty().withAppVersion("1.0")).stats().crashEvents());
        assertEquals(0, query.overview(appId, from, to, CrashQueryCommand.empty().withAppVersion("2.0")).stats().crashEvents());
        assertEquals(2, query.overview(appId, from, to, CrashQueryCommand.empty().withFingerprint("fp-a")).stats().crashEvents());
        assertEquals(1, query.overview(appId, from, to, CrashQueryCommand.empty().withFingerprint("fp-resolved")).stats().crashEvents());
    }

    /** 固定合成事件，仅覆盖查询使用的原始列。 */
    private void insert(ClickHouseHttpClient client, UUID appId, String eventId, String type, String time,
                        String session, String device, String fingerprint) {
        // 每次插入都走当前两侧确认语义，同一身份的重放不改变不可变事实。
        CrashStoredSignal event = signal(appId, eventId, type, time, session, device, fingerprint);
        new com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashStateStore(client, new ObjectMapper(), CrashTestSupport.queryProperties())
                .accept(new com.shanshui.apmserver.crash.internal.domain.CrashFact(event.metadata(), event.crashKind(), Boolean.TRUE.equals(event.crashFatal())),
                        event instanceof CrashEvent crash ? crash.state() : null);
    }

    /** 与写入的 ClickHouse 合成行一一对应，重复物理版本只保留一个逻辑事件。 */
    private CrashStoredSignal signal(UUID appId, String eventId, String type, String time,
                                     String session, String device, String fingerprint) {
        EventMetadata metadata = new EventMetadata(appId, "com.example.app", eventId, type,
                Instant.parse("" + DAY + "T" + time + "Z"), Instant.parse("" + DAY + "T12:00:00Z"),
                2, session.isEmpty() ? null : session, null, device.isEmpty() ? null : device,
                "1.0", 1, "build-1", "production", "official", "16", "Pixel", "wifi", Map.of(), Map.of());
        return "crash".equals(type)
                ? new CrashEvent(metadata, "jvm", true, new com.shanshui.apmserver.crash.internal.domain.CrashState(appId, eventId,
                "build-1", metadata.occurredAt(), 0, "pending", "mapping_missing", "raw_chain", "{}", "java.lang.IllegalStateException", fingerprint,
                "tmp-v1", "temporary", null, null, null, null, null))
                : new AppStartEvent(metadata);
    }
}
