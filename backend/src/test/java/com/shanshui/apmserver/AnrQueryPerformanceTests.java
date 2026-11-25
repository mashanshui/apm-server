package com.shanshui.apmserver;

import com.shanshui.apmserver.anr.internal.application.AnrQueryService;
import com.shanshui.apmserver.anr.internal.persistence.ClickHouseAnrRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 隔离 ClickHouse 上显式运行的 ANR 查询规模检查。 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_ANR_BENCHMARK", matches = "true")
class AnrQueryPerformanceTests {
    /** 测试使用独立数据库容器。 */
    @Container
    private static final GenericContainer<?> DATABASE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "test").withEnv("CLICKHOUSE_PASSWORD", "test-only")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);

    /** 十万条合成事件和稀疏大现场下检查下推、投影和资源预算。 */
    @Test
    void measuresListAndOverviewWithoutReadingDiagnostics() throws Exception {
        var configuration = new ClickHouseProperties();
        configuration.setUrl("http://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(8123));
        configuration.setDatabase("default");
        configuration.setUsername("test");
        configuration.setPassword("test-only");
        var client = new RecordingClient(configuration);
        client.execute("CREATE DATABASE IF NOT EXISTS apm");
        String ddl;
        try (var stream = getClass().getResourceAsStream("/db/clickhouse/013_anr_events.sql")) {
            ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        client.execute(ddl);
        configuration.setDatabase("apm");
        UUID app = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        client.execute("INSERT INTO apm_anr_events "
                + "SELECT if(number%10=0,toUUID('" + other + "'),toUUID('" + app + "')),"
                + "concat('event-',toString(number)),now64(3)-toIntervalSecond(number%100000+1),now64(3),"
                + "toUInt64(9000000000000000000),concat('v-',toString(number%4)),toInt64(1),'synthetic',"
                + "if(intDiv(number,4)%2=0,'message_timeout','process_error_state'),"
                + "concat('device-',toString(number%1000)),'{}',repeat('D',if(number%1000=0,98304,100)) "
                + "FROM numbers(100000)");
        var limits = new QueryProperties();
        var repository = new ClickHouseAnrRepository(client, new ObjectMapper(), limits);
        var query = new AnrQueryService(repository, limits, new ObjectMapper(),
                new com.shanshui.apmserver.anr.internal.persistence.ClickHouseAnrSymbolicationStore(client, new ObjectMapper(), limits));
        Instant now = Instant.now();
        String from = now.minus(Duration.ofDays(1)).toString();
        String to = now.plusSeconds(1).toString();
        var overview = query.overview(app, from, to, "v-1", "message_timeout");
        var page = query.events(app, from, to, "v-1", "message_timeout", 50, null, null);
        assertThat(overview.eventCount()).isPositive();
        assertThat(page.events()).hasSize(50);
        assertThat(client.overview).contains("app_id=", "event_time >=", "app_version=", "source=");
        assertThat(client.list).contains("ORDER BY event_time DESC, event_id DESC LIMIT 51");
        assertThat(client.overview).doesNotContain("metadata_json", "payload_json");
        assertThat(client.list).doesNotContain("metadata_json", "payload_json");
        String overviewPlan = client.execute("EXPLAIN indexes=1 " + client.overview.replace(" FORMAT JSONEachRow", ""));
        String listPlan = client.execute("EXPLAIN indexes=1 " + client.list.replace(" FORMAT JSONEachRow", ""));
        client.execute("SYSTEM FLUSH LOGS");
        String metrics = client.execute("SELECT query_duration_ms,read_rows,read_bytes,memory_usage,result_rows,result_bytes,columns "
                + "FROM system.query_log WHERE type='QueryFinish' AND position(query,'FROM apm_anr_events FINAL')>0 "
                + "AND startsWith(query,'SELECT ') AND position(query,'system.query_log')=0 "
                + "ORDER BY event_time_microseconds DESC LIMIT 2 FORMAT JSONEachRow");
        var rows = client.decodeJsonEachRow(metrics, new ObjectMapper());
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            assertThat(row.path("read_rows").asLong()).isLessThanOrEqualTo(limits.getMaxRowsToRead());
            assertThat(row.path("read_bytes").asLong()).isLessThanOrEqualTo(limits.getMaxBytesToRead());
            assertThat(row.path("memory_usage").asLong()).isLessThanOrEqualTo(limits.getMaxMemoryUsage());
            assertThat(row.path("result_bytes").asLong()).isLessThanOrEqualTo(limits.getMaxResponseBytes());
        }
        assertThat(metrics).doesNotContain("metadata_json", "payload_json");
        Files.createDirectories(Path.of("build/query-evidence"));
        Files.writeString(Path.of("build/query-evidence/anr.txt"),
                "ClickHouse 26.7.3.19，十万条合成事件，双应用、四版本和稀疏 96 KiB 诊断。\n"
                        + "资源结果：\n" + metrics + "\n概览 SQL：\n" + client.overview + "\n概览计划：\n" + overviewPlan
                        + "\n列表 SQL：\n" + client.list + "\n列表计划：\n" + listPlan);
    }

    /** 记录实际经过预算执行的概览与列表 SQL。 */
    private static class RecordingClient extends ClickHouseHttpClient {
        /** 最近的概览 SQL。 */
        private String overview;
        /** 最近的列表 SQL。 */
        private String list;

        /** 继续使用真实 HTTP 客户端。 */
        private RecordingClient(ClickHouseProperties properties) {
            super(properties);
        }

        /** 保存查询文本并执行原有受限查询。 */
        @Override
        public String executeQuery(String sql, QueryBudget budget) {
            if (sql.startsWith("SELECT count() AS event_count")) overview = sql;
            if (sql.startsWith("SELECT event_id, event_time")) list = sql;
            return super.executeQuery(sql, budget);
        }
    }
}
