package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashStateStore;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.platform.api.QueryBudget;
import com.shanshui.apmserver.platform.api.QueryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 显式执行的隔离混合 Crash 候选性能验证，不推导生产容量。 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_NATIVE_BENCHMARK", matches = "true")
class NativeCrashQueryPerformanceTests {
    /** 合成数据仅写入临时数据库。 */
    @Container
    private static final GenericContainer<?> DATABASE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "test").withEnv("CLICKHOUSE_PASSWORD", "test-only")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);

    /** 十万条混合类型、多版本和稀疏大诊断下验证轻量分页及混合查询预算。 */
    @Test
    void boundsNativeCandidatesUnderMixedLoad() throws Exception {
        var properties = new ClickHouseProperties();
        properties.setUrl("http://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(8123));
        properties.setDatabase("default"); properties.setUsername("test"); properties.setPassword("test-only");
        var client = new RecordingClient(properties);
        for (String name : List.of("008_background_crash_symbolication.sql", "010_crash_diagnostics.sql", "011_native_crash_state.sql", "012_native_symbolication_state.sql")) {
            String ddl = new String(getClass().getResourceAsStream("/db/clickhouse/" + name).readAllBytes(), StandardCharsets.UTF_8);
            for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        }
        properties.setDatabase("apm");
        UUID app = UUID.randomUUID();
        client.execute("INSERT INTO apm_crash_fact(app_id,event_id,event_type,package_name,app_version,build_id,event_time,received_time,schema_version,crash_kind,crash_fatal) SELECT '" + app
                + "',concat('event-',toString(number)),'crash','com.example',concat('v-',toString(number%4)),concat('build-',toString(number%10)),now64(3)-toIntervalSecond(number%86400+1),now64(3),2,if(intDiv(number,4)%2=0,'native','jvm'),1 FROM numbers(100000)");
        client.execute("INSERT INTO apm_crash_fact(app_id,event_id,event_type,package_name,app_version,build_id,event_time,received_time,schema_version,crash_kind,crash_fatal,diagnostics_payload) SELECT app_id,event_id,event_type,package_name,app_version,build_id,event_time,received_time,schema_version,crash_kind,crash_fatal,repeat('D',131072) FROM apm_crash_fact FINAL WHERE toUInt64(substring(event_id,7))%10000=0");
        client.execute("INSERT INTO apm_crash_state(app_id,event_id,build_id,event_time,state_version,symbolication_status,symbolication_reason,stack_format,stack_payload,exception_type,fingerprint,fingerprint_version,grouping_kind) SELECT app_id,event_id,build_id,event_time,0,'pending',if(crash_kind='native','native_symbolication_deferred','mapping_missing'),if(crash_kind='native','raw_native','raw_chain'),repeat('x',2048),'Exception','fp',if(crash_kind='native','native-tmp-v1','tmp-v1'),'temporary' FROM apm_crash_fact FINAL");
        var limits = new QueryProperties();
        var store = new ClickHouseCrashStateStore(client, new ObjectMapper(), limits);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var page = store.nativeCandidatePage(app, "v-0", now.minus(Duration.ofDays(30)), now, null, null, 100);
        assertEquals(100, page.size());
        String light = client.lightweight;
        assertNotNull(light);
        assertFalse(light.contains("stack_payload"));
        assertFalse(light.contains("diagnostics_payload"));
        assertTrue(light.contains("native_work_remaining=1"));
        String plan = client.execute("EXPLAIN indexes=1 " + light.replace(" FORMAT JSONEachRow", ""));
        var payload = store.nativePayloads(app, "v-0", page);
        assertEquals(100, payload.size());
        String payloadSql = client.last;
        assertFalse(payloadSql.contains("diagnostics_payload"));
        var repository = new com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashRepository(client, new ObjectMapper(), limits);
        var query = new com.shanshui.apmserver.crash.internal.application.CrashQueryService(repository, limits,
                CrashTestSupport.storageProperties(), new com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var command = com.shanshui.apmserver.crash.internal.domain.CrashQueryCommand.empty();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 3; index++) {
                var foreground = workers.submit(() -> query.overview(app, now.minus(Duration.ofDays(2)).toString(), now.plusSeconds(1).toString(), command));
                var background = workers.submit(() -> store.nativeCandidatePage(app, "v-0", now.minus(Duration.ofDays(30)), now, null, null, 100));
                assertTrue(foreground.get().stats().crashEvents() > 0);
                assertEquals(100, background.get().size());
            }
        }
        client.execute("SYSTEM FLUSH LOGS");
        String measurements = client.execute("SELECT query_duration_ms,read_rows,read_bytes,memory_usage,result_rows,result_bytes,columns FROM system.query_log WHERE type='QueryFinish' AND startsWith(query,'SELECT s.event_id,s.build_id') AND position(query,'native_work_remaining')>0 ORDER BY event_time_microseconds DESC LIMIT 1 FORMAT JSONEachRow");
        var measured = new ObjectMapper().readTree(measurements);
        assertEquals(100, measured.path("result_rows").asInt());
        assertTrue(measured.path("read_rows").asLong() <= limits.getMaxRowsToRead());
        assertTrue(measured.path("read_bytes").asLong() <= limits.getMaxBytesToRead());
        assertTrue(measured.path("memory_usage").asLong() <= limits.getMaxMemoryUsage());
        assertTrue(measured.path("result_bytes").asLong() <= limits.getMaxResponseBytes());
        assertFalse(measurements.contains("stack_payload"));
        assertFalse(measurements.contains("diagnostics_payload"));
        assertFalse(measurements.contains("thread_stacks_payload"));
        limits.setMaxResponseBytes(128);
        assertEquals("QUERY_RESOURCE_LIMIT", assertThrows(com.shanshui.apmserver.platform.api.QueryValidationException.class,
                () -> store.nativeCandidatePage(app, "v-0", now.minus(Duration.ofDays(30)), now, null, null, 100)).getCode());
        Files.createDirectories(Path.of("build/query-evidence"));
        Files.writeString(Path.of("build/query-evidence/native-crash.txt"), "ClickHouse 26.7.3.19\n十万条混合 JVM/Native、四版本、稀疏 128 KiB 诊断；前台与后台并行三轮通过。\n" + measurements + "\n轻量计划：\n" + plan + "\n载荷 SQL：\n" + payloadSql);
    }

    /** 保存有限查询的 SQL，实际调用仍执行资源预算。 */
    private static class RecordingClient extends ClickHouseHttpClient {
        /** 最近的有预算查询。 */ private String last;
        /** Native 轻量身份查询。 */ private String lightweight;
        /** 保持真实 HTTP 访问。 */ private RecordingClient(ClickHouseProperties properties) { super(properties); }
        /** 记录查询计划证据。 */
        @Override public String executeQuery(String sql, QueryBudget budget) {
            last = sql;
            if (sql.startsWith("SELECT s.event_id,s.build_id") && sql.contains("native_work_remaining")) lightweight = sql;
            return super.executeQuery(sql, budget);
        }
    }
}
