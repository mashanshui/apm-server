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

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "apm_test")
            .withEnv("CLICKHOUSE_PASSWORD", "apm_test_password")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123);

    @Test
    void overviewAndTrendMatchReferenceSemantics() {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        properties.setDatabase("default");
        properties.setUsername("apm_test");
        properties.setPassword("apm_test_password");
        ClickHouseHttpClient client = new ClickHouseHttpClient(properties);
        client.execute("CREATE TABLE apm_event_raw (app_id UUID, event_id String, event_type String, "
                + "event_time DateTime64(3, 'UTC'), received_time DateTime64(3, 'UTC'), "
                + "session_id String, anonymous_device_id String, app_version String, channel String, "
                + "environment String, os_version String, device_model String, crash_fingerprint String, "
                + "fingerprint_version String, crash_exception_type String, version_code Int64, build_id String, "
                + "symbolication_status String) "
                + "ENGINE = ReplacingMergeTree(received_time) ORDER BY (app_id, event_type, event_time, event_id)");
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
        String from = "2026-09-28T10:00:00Z";
        String to = "2026-09-28T12:00:00Z";
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
        assertNull(query.overview(appId, "2026-09-28T09:00:00Z", from, CrashQueryCommand.empty())
                .stats().crashRatePer1000Sessions());
        assertEquals("denominator_insufficient", query.overview(appId, "2026-09-28T11:30:00Z", to,
                CrashQueryCommand.empty()).stats().status());

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
                + "AND position(query, 'FROM apm_event_raw AS r FINAL') > 0 "
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

        // 最新物理版本改变维度后，旧维度不能再匹配该逻辑事件。
        client.execute("INSERT INTO apm_event_raw (app_id,event_id,event_type,event_time,received_time,"
                + "session_id,anonymous_device_id,app_version,channel,environment,os_version,device_model,"
                + "crash_fingerprint,fingerprint_version,crash_exception_type,version_code,build_id,"
                + "symbolication_status) VALUES ('" + appId + "','crash-1','crash',"
                + "'2026-09-28 10:15:00.000','2026-09-28 12:01:00.000','s1','d1','2.0',"
                + "'official','production','16','Pixel','fp-a','v1',"
                + "'java.lang.IllegalStateException',2,'build-2','raw_only')");
        assertEquals(3, query.overview(appId, from, to,
                CrashQueryCommand.empty().withAppVersion("1.0")).stats().crashEvents());
        assertEquals(1, query.overview(appId, from, to,
                CrashQueryCommand.empty().withAppVersion("2.0")).stats().crashEvents());
    }

    /** 固定合成事件，仅覆盖查询使用的原始列。 */
    private void insert(ClickHouseHttpClient client, UUID appId, String eventId, String type, String time,
                        String session, String device, String fingerprint) {
        client.execute("INSERT INTO apm_event_raw (app_id,event_id,event_type,event_time,received_time,"
                + "session_id,anonymous_device_id,app_version,channel,environment,os_version,device_model,"
                + "crash_fingerprint,fingerprint_version,crash_exception_type,version_code,build_id,"
                + "symbolication_status) VALUES ('" + appId + "','" + eventId + "','" + type + "','2026-09-28 "
                + time + ".000','2026-09-28 12:00:00.000','" + session + "','" + device
                + "','1.0','official','production','16','Pixel','" + fingerprint
                + "','v1','java.lang.IllegalStateException',1,'build-1','raw_only')");
    }

    /** 与写入的 ClickHouse 合成行一一对应，重复物理版本只保留一个逻辑事件。 */
    private CrashStoredSignal signal(UUID appId, String eventId, String type, String time,
                                     String session, String device, String fingerprint) {
        EventMetadata metadata = new EventMetadata(appId, "com.example.app", eventId, type,
                Instant.parse("2026-09-28T" + time + "Z"), Instant.parse("2026-09-28T12:00:00Z"),
                2, session.isEmpty() ? null : session, null, device.isEmpty() ? null : device,
                "1.0", 1, "build-1", "production", "official", "16", "Pixel", "wifi", Map.of(), Map.of());
        return "crash".equals(type)
                ? new CrashEvent(metadata, "jvm", true, "java.lang.IllegalStateException", fingerprint,
                "v1", "raw_only", null)
                : new AppStartEvent(metadata);
    }
}
