package com.shanshui.apmserver;

import com.shanshui.apmserver.memory.internal.application.MemoryMetricsQueryService;
import com.shanshui.apmserver.memory.internal.domain.MemoryQueryCommand;
import com.shanshui.apmserver.memory.internal.domain.MemoryQueryFilter;
import com.shanshui.apmserver.memory.internal.persistence.ClickHouseMemoryMetricsQuerySql;
import com.shanshui.apmserver.memory.internal.persistence.ClickHouseMemoryMetricsRepository;
import com.shanshui.apmserver.platform.api.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 十万目标运行加其他应用/历史月份，验证数据库裁剪与资源失败边界。 */
@Testcontainers
class MemoryPeakQueryBudgetTests {
    /** 独立临时数据库，不连接已有服务。 */
    @Container private static final GenericContainer<?> DATABASE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "apm_test").withEnv("CLICKHOUSE_PASSWORD", "apm_test_password")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);

    /** 查询仅返回聚合与有限桶；扫描、内存、响应和耗时超限必须明确失败。 */
    @Test
    void boundsAggregatesAndRejectsExhaustedBudgets() throws Exception {
        var connection = new ClickHouseProperties();
        connection.setUrl("http://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(8123));
        connection.setUsername("apm_test");
        connection.setPassword("apm_test_password");
        connection.setDatabase("default");
        var client = new ClickHouseHttpClient(connection);
        client.execute("CREATE DATABASE apm");
        client.execute(Files.readString(Path.of("src/main/resources/db/clickhouse/009_memory_runtime_peaks.sql")));
        connection.setDatabase("apm");
        /** 十万条目标运行，另加其他应用和更早月份共二十万噪声记录。 */
        UUID app = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String columns = "(app_id,event_id,event_time,received_time,collection_started_at,pss_bytes,pss_occurred_at,pss_foreground)";
        for (int cohort = 0; cohort < 3; cohort++) {
            String id = cohort == 1 ? "22222222-2222-4222-8222-222222222222" : app.toString();
            String time = cohort == 2 ? "now64(3) - INTERVAL 40 DAY" : "now64(3)";
            client.execute("INSERT INTO apm_memory_peak_summary " + columns + " SELECT toUUID('" + id
                    + "'),concat('" + cohort + "-',toString(number))," + time + ",now64(3),toUInt64(toUnixTimestamp64Milli(" + time
                    + ")),number,toUInt64(toUnixTimestamp64Milli(" + time + ")),1 FROM numbers(100000)");
        }
        var budgets = new QueryProperties();
        var repository = new ClickHouseMemoryMetricsRepository(client, new ObjectMapper(), budgets);
        Instant now = Instant.now();
        var filter = new MemoryQueryFilter(app, now.minusSeconds(3600), now.plusSeconds(60), null,null,null,null,null,null,500,2000);
        var summary = repository.queryPss(filter);
        assertThat(summary.runCount()).isEqualTo(100000);
        assertThat(summary.averageBytes()).isEqualTo(49999.5);
        assertThat(summary.p90Bytes()).isCloseTo(89999.1, within(0.000001));
        assertThat(repository.queryVss(filter).runCount()).isZero();
        assertThat(repository.queryTrend(filter, "pss", "hour")).hasSizeLessThanOrEqualTo(2);
        /** 查询计划保留月份/应用裁剪与数据库聚合的证据。 */
        String query = ClickHouseMemoryMetricsQuerySql.selectSummary(filter);
        String plan = client.execute("EXPLAIN indexes=1 " + query.substring(0, query.indexOf(" FORMAT")));
        assertThat(plan).contains("Aggregating", "Partition", "PrimaryKey", "Parts:", "Granules:");
        Files.writeString(Path.of("build/memory-peak-query-plan.txt"), plan);
        client.execute("SYSTEM FLUSH LOGS");
        String scan = client.execute("SELECT read_rows,result_rows,memory_usage,query_duration_ms FROM system.query_log WHERE type='QueryFinish' AND query LIKE 'SELECT countIf%apm_memory_peak_summary%' ORDER BY event_time_microseconds DESC LIMIT 1 FORMAT JSONEachRow");
        assertThat(scan).isNotBlank();
        var measured = new ObjectMapper().readTree(scan.trim());
        assertThat(measured.path("result_rows").asLong()).isEqualTo(1);
        assertThat(measured.path("read_rows").asLong()).isEqualTo(100000L);
        Files.writeString(Path.of("build/memory-peak-query-resources.json"), scan);
        /** 最长非整点窗口补桶仍有固定上限，不能受列表limit截断。 */
        var queryService = new MemoryMetricsQueryService(repository, budgets);
        Instant from = now.truncatedTo(ChronoUnit.HOURS).minus(31, ChronoUnit.DAYS).plusSeconds(1800);
        assertThat(queryService.trend(app, "pss", "hour", from.toString(), from.plus(31,ChronoUnit.DAYS).toString(), MemoryQueryCommand.empty()).points()).hasSize(745);
        try {
            budgets.setMaxRowsToRead(1);
            assertResourceFailure(() -> repository.queryPss(filter));
        } finally { budgets.setMaxRowsToRead(5_000_000); }
        try {
            budgets.setMaxMemoryUsage(1024);
            assertResourceFailure(() -> repository.queryPss(filter));
        } finally { budgets.setMaxMemoryUsage(256L * 1024 * 1024); }
        try {
            budgets.setMaxResponseBytes(1);
            assertResourceFailure(() -> repository.queryPss(filter));
        } finally { budgets.setMaxResponseBytes(8 * 1024 * 1024); }
        /** 亚秒预算不能被 SQL 向上取整为一秒；真实数据库超时映射为408。 */
        var tiny = new MemoryQueryFilter(app, filter.from(),filter.to(),null,null,null,null,null,null,500,1);
        assertThat(ClickHouseMemoryMetricsQuerySql.selectSummary(tiny)).contains("max_execution_time = 0.001");
        assertThatThrownBy(() -> repository.queryPss(tiny)).isInstanceOf(QueryValidationException.class)
                .satisfies(error -> assertThat(((QueryValidationException)error).getCode()).isEqualTo("QUERY_TIMEOUT"));
    }

    /** 预算耗尽不能返回成功空统计。 */
    private void assertResourceFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable query) {
        assertThatThrownBy(query).isInstanceOf(QueryValidationException.class)
                .satisfies(error -> assertThat(((QueryValidationException)error).getCode()).isEqualTo("QUERY_RESOURCE_LIMIT"));
    }
}
