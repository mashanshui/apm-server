package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashStateStore;
import com.shanshui.apmserver.platform.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 显式执行的百万当前状态候选基线；不连接开发数据库，不推导生产 SLA。 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_CRASH_BENCHMARK", matches = "true")
class BackgroundCrashQueryPerformanceTests {
    /** 独立固定版本服务。 */
    @Container
    private static final GenericContainer<?> DATABASE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "test").withEnv("CLICKHOUSE_PASSWORD", "test-only")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);

    /** 十构建、百万 pending 和每事件约 2 KiB 完整链验证有限载荷查询。 */
    @Test
    void boundsCandidatePayloadAndRejectsInsufficientBudget() throws Exception {
        // 专用临时服务与数据，不触碰已有数据集。
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl("http://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(8123));
        properties.setDatabase("default");
        properties.setUsername("test");
        properties.setPassword("test-only");
        RecordingClient client = new RecordingClient(properties);
        String ddl = new String(getClass().getResourceAsStream("/db/clickhouse/008_background_crash_symbolication.sql").readAllBytes(), StandardCharsets.UTF_8);
        for (String statement : ddl.split(";")) if (!statement.isBlank()) client.execute(statement);
        properties.setDatabase("apm");
        UUID app = UUID.randomUUID();
        UUID symbol = UUID.randomUUID();
        // 写入发生时间横跨十天，旧构建和高待处理比例都位于当前新数据集。
        client.execute("INSERT INTO apm_crash_fact(app_id,event_id,event_type,package_name,build_id,event_time,received_time,schema_version,crash_kind,crash_fatal) SELECT '" + app
                + "',concat('event-',toString(number)),'crash','com.example',concat('build-',toString(number%10)),now64(3)-toIntervalSecond(number%864000+1),now64(3),2,'jvm',1 FROM numbers(1000000)");
        client.execute("INSERT INTO apm_crash_state(app_id,event_id,build_id,event_time,state_version,symbolication_status,symbolication_reason,stack_format,stack_payload,exception_type,fingerprint,fingerprint_version,grouping_kind) SELECT app_id,event_id,build_id,event_time,0,'pending','mapping_missing','raw_chain',concat('{\"kind\":\"jvm\",\"fatal\":true,\"throwableChain\":[{\"type\":\"java.lang.RuntimeException\",\"message\":\"',repeat('x',2048),'\",\"frames\":[{\"className\":\"a\",\"methodName\":\"b\",\"fileName\":\"SourceFile\",\"lineNumber\":1,\"applicationFrame\":true}]}]}'),'java.lang.RuntimeException','tmp','tmp-v1','temporary' FROM apm_crash_fact");
        QueryProperties limits = new QueryProperties();
        ClickHouseCrashStateStore store = new ClickHouseCrashStateStore(client, new ObjectMapper(), limits);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var candidates = store.candidates(app, "build-0", symbol, 1, now.minus(Duration.ofDays(30)), now, 100);
        assertEquals(100, candidates.size());
        assertTrue(candidates.stream().allMatch(item -> item.packageName().equals("com.example") && item.state().buildId().equals("build-0")));
        String sql = client.lastQuery;
        String plan = client.execute("EXPLAIN indexes=1 " + sql.replace(" FORMAT JSONEachRow", ""));
        client.execute("SYSTEM FLUSH LOGS");
        String measurements = client.execute("SELECT query_duration_ms,read_rows,read_bytes,memory_usage,result_rows,result_bytes FROM system.query_log WHERE type='QueryFinish' AND startsWith(query,'SELECT s.*,f.package_name FROM') ORDER BY event_time_microseconds DESC LIMIT 1 FORMAT JSONEachRow");
        assertFalse(measurements.isBlank());
        // 排除 EXPLAIN 的结果，证明实查只返回百条载荷且没有读取整构建的约 200 MiB 堆栈。
        var actual = new ObjectMapper().readTree(measurements);
        assertEquals(100, actual.path("result_rows").asInt());
        assertTrue(actual.path("read_bytes").asLong() < 64L * 1024 * 1024, measurements);
        assertTrue(actual.path("result_bytes").asLong() > 200L * 1024, measurements);
        // 百万历史行保持不变，前台在最近一天与后台候选并行，二者均沿用默认预算。
        var repository = new com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashRepository(client, new ObjectMapper(), limits);
        var query = new com.shanshui.apmserver.crash.internal.application.CrashQueryService(repository, limits,
                CrashTestSupport.storageProperties(), new com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        String from = now.minus(Duration.ofDays(1)).toString();
        String to = now.plusSeconds(1).toString();
        var command = com.shanshui.apmserver.crash.internal.domain.CrashQueryCommand.empty();
        var overview = query.overview(app, from, to, command);
        assertTrue(overview.stats().crashEvents() > 80000);
        var issues = query.issues(app, from, to, command);
        assertEquals(1, issues.issues().size());
        assertEquals(50, query.events(app, "tmp", from, to, command).events().size());
        assertFalse(query.trend(app, from, to, "hour", command).points().isEmpty());
        String foregroundSql = client.lastQuery;
        assertFalse(foregroundSql.contains("stack_payload"));
        String foregroundPlan = client.execute("EXPLAIN indexes=1 " + foregroundSql.replace(" FORMAT JSONEachRow", ""));
        var mixedOutcomes = new java.util.ArrayList<String>();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 5; round++) {
                var foreground = executor.submit(() -> query.overview(app, from, to, command));
                var background = executor.submit(() -> store.candidates(app, "build-0", symbol, 1, now.minus(Duration.ofDays(30)), now, 100));
                assertEquals(overview.stats(), foreground.get().stats());
                assertEquals(100, background.get().size());
                mixedOutcomes.add("foreground+background=ok");
            }
        }
        client.execute("SYSTEM FLUSH LOGS");
        String mixedMetrics = client.execute("SELECT query_duration_ms,read_rows,read_bytes,memory_usage,result_rows,result_bytes FROM system.query_log WHERE type='QueryFinish' AND startsWith(query,'SELECT ') AND position(query,'apm_crash_')>0 AND position(query,'system.query_log')=0 ORDER BY event_time_microseconds DESC LIMIT 14 FORMAT JSONEachRow");
        for (String line : mixedMetrics.lines().toList()) {
            var measured = new ObjectMapper().readTree(line);
            assertTrue(measured.path("memory_usage").asLong() <= limits.getMaxMemoryUsage(), line);
            assertTrue(measured.path("read_bytes").asLong() <= limits.getMaxBytesToRead(), line);
        }
        // 不能通过提高无限预算来掩盖读取路径；同一查询在不足预算下必须明确失败。
        limits.setMaxRowsToRead(1);
        assertEquals("QUERY_RESOURCE_LIMIT", assertThrows(QueryValidationException.class,
                () -> store.candidates(app, "build-0", symbol, 1, now.minus(Duration.ofDays(30)), now, 100)).getCode());
        Files.createDirectories(Path.of("build/query-evidence"));
        Files.writeString(Path.of("build/query-evidence/background-crash.txt"), "ClickHouse 26.7.3.19\n规模：1000000 事件，10 构建，全部 pending，约 2 KiB/堆栈\n预算：5000000 行、512 MiB 读取、256 MiB 内存、8 MiB 响应、2 秒\n" + measurements + "\n" + plan + "\n" + sql + "\n前台一天窗口和后台候选并行五轮：" + mixedOutcomes + "\n" + mixedMetrics + "\n" + foregroundPlan + "\n" + foregroundSql);
    }

    /** 仅记录合成查询计划入口，不捕获正文或凭据。 */
    private static class RecordingClient extends ClickHouseHttpClient {
        /** 最近一次有预算查询。 */
        private String lastQuery;
        /** 复用真实 HTTP 通道。 */
        private RecordingClient(ClickHouseProperties properties) { super(properties); }
        /** 保存 SQL 后仍使用真实预算与数据库执行。 */
        @Override public String executeQuery(String sql, QueryBudget budget) {
            lastQuery = sql;
            return super.executeQuery(sql, budget);
        }
    }
}
