package com.shanshui.apmserver;

import com.shanshui.apmserver.memory.api.*;
import com.shanshui.apmserver.memory.internal.application.*;
import com.shanshui.apmserver.memory.internal.persistence.ClickHouseMemoryMetricsRepository;
import com.shanshui.apmserver.memory.internal.domain.MemoryQueryFilter;
import com.shanshui.apmserver.platform.api.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** 在独立真实数据库验证运行峰值的接收、存储、恢复与查询。 */
@Testcontainers
class MemoryPeakPersistenceTests {
    /** 仅本测试使用的临时数据库及公开测试凭据。 */
    @Container
    private static final GenericContainer<?> DATABASE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "apm_test").withEnv("CLICKHOUSE_PASSWORD", "apm_test_password")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1").withExposedPorts(8123);

    /** 验证可重复迁移、原身份上下文、重启重复确认及数据库聚合。 */
    @Test
    void persistsOriginalContextAndDeduplicatesAfterRepositoryRestart() throws Exception {
        // 所有表均位于测试容器，不接触本地现有业务表。
        ClickHouseProperties connection = new ClickHouseProperties();
        connection.setUrl("http://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(8123));
        connection.setUsername("apm_test");
        connection.setPassword("apm_test_password");
        connection.setDatabase("default");
        ClickHouseHttpClient client = new ClickHouseHttpClient(connection);
        client.execute("CREATE DATABASE IF NOT EXISTS apm");
        String ddl = new String(getClass().getResourceAsStream("/db/clickhouse/009_memory_runtime_peaks.sql").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        client.execute(ddl);
        client.execute(ddl);
        connection.setDatabase("apm");
        ObjectMapper mapper = new ObjectMapper();
        QueryProperties budget = new QueryProperties();
        ClickHouseMemoryMetricsRepository repository = new ClickHouseMemoryMetricsRepository(client, mapper, budget);
        MemoryEventProcessor processor = processor(repository, mapper);
        UUID appId = UUID.randomUUID();
        long end = Instant.now().minusSeconds(86400).toEpochMilli();
        MemoryPeakSummaryPayload payload = new MemoryPeakSummaryPayload("com.example.app", end - 10000, end,
                new MemoryPeak(200L, end - 9000, true, "A"), new MemoryPeak(500L, end - 8000, false, null),
                new MemoryPeak(80L, end - 7000, true, "B"));
        MemoryIngestCommand command = new MemoryIngestCommand(2, UUID.randomUUID().toString(), "memory_peak_summary", end,
                "old-session", CrashTestSupport.PROCESS_ID, "device", "com.example.app", "old-version", 1, "old-build",
                "test", "test", "13", "test-device", null, null, null, payload);
        assertThat(processor.ingest(appId, command, Instant.now()).appendResult().accepted()).isEqualTo(1);
        // 停止合并后人工写入真实重复行，验证查询不依赖后台合并。
        client.execute("SYSTEM STOP MERGES apm_memory_peak_summary");
        client.execute("INSERT INTO apm_memory_peak_summary SELECT * REPLACE (received_time + INTERVAL 1 SECOND AS received_time) "
                + "FROM apm_memory_peak_summary WHERE app_id='" + appId + "' AND event_id='" + command.eventId() + "'");
        assertThat(client.execute("SELECT count() FROM apm_memory_peak_summary WHERE app_id='" + appId + "' FORMAT TabSeparated").trim()).isEqualTo("2");
        // 模拟服务重启：重新构建适配器，查重只能依赖持久库。
        ClickHouseMemoryMetricsRepository restarted = new ClickHouseMemoryMetricsRepository(client, mapper, budget);
        assertThat(processor(restarted, mapper).ingest(appId, command, Instant.now().plusSeconds(86400))
                .appendResult().duplicate()).isEqualTo(1);
        var stored = restarted.findByEventId(appId, command.eventId()).orElseThrow();
        assertThat(stored.payload()).isEqualTo(payload);
        assertThat(stored.processId()).isEqualTo(CrashTestSupport.PROCESS_ID);
        assertThat(stored.metadata().buildId()).isEqualTo("old-build");
        MemoryQueryFilter filter = new MemoryQueryFilter(appId, Instant.ofEpochMilli(end - 1), Instant.ofEpochMilli(end + 1),
                null, null, null, null, "A", true, 500, 2000);
        assertThat(restarted.queryPss(filter).runCount()).isEqualTo(1);
        assertThat(restarted.queryVss(filter).runCount()).isZero();
        assertThat(restarted.queryJavaHeap(filter).runCount()).isZero();
        assertThat(restarted.queryTrend(filter, "pss", "hour")).hasSize(1);
        // 同一事件并发重试不会覆盖旧版本或增加有效运行数。
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var jobs = workers.invokeAll(List.of(() -> processor.ingest(appId, command, Instant.now()),
                    () -> processor.ingest(appId, command, Instant.now())));
            for (var job : jobs) assertThat(((com.shanshui.apmserver.telemetry.api.SignalIngestResult) job.get())
                    .appendResult().duplicate()).isEqualTo(1);
        }
        assertThat(restarted.queryPss(filter).runCount()).isEqualTo(1);
    }

    /** 首次并发、批内重复与跨应用同ID都保持原始内容和正确数量。 */
    @Test
    void freshConcurrentAndBatchRetriesRemainApplicationScoped() throws Exception {
        // 每项测试通过独立应用隔离；同一容器也不会依赖测试执行顺序。
        var client = databaseClient();
        var repository = new ClickHouseMemoryMetricsRepository(client, new ObjectMapper(), new QueryProperties());
        var processor = processor(repository, new ObjectMapper());
        var app = UUID.randomUUID();
        var command = command(UUID.randomUUID().toString());
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> processor.ingest(app, command, Instant.now()).appendResult());
            var second = workers.submit(() -> processor.ingest(app, command, Instant.now()).appendResult());
            assertThat(first.get().accepted() + second.get().accepted()).isEqualTo(1);
            assertThat(first.get().duplicate() + second.get().duplicate()).isEqualTo(1);
        }
        var stored = repository.findByEventId(app, command.eventId()).orElseThrow();
        assertThat(repository.append(app, List.of(stored, stored)).duplicate()).isEqualTo(2);
        assertThat(processor.ingest(UUID.randomUUID(), command, Instant.now()).appendResult().accepted()).isEqualTo(1);
    }

    /** 写入前失败不会accepted，写入后响应丢失也能用原ID恢复为duplicate。 */
    @Test
    void failedWriteAndLostAcknowledgementNeverInventAcceptance() throws Exception {
        var connection = connection();
        var healthy = databaseClient();
        var mapper = new ObjectMapper();
        var app = UUID.randomUUID();
        var command = command(UUID.randomUUID().toString());
        // 仅故障注入客户端拒绝插入，真实数据库仍用于验证没有写入。
        var failing = new ClickHouseHttpClient(connection) {
            /** 在发送插入前注入数据库错误。 */
            @Override public String execute(String sql, String body) {
                if (sql.startsWith("INSERT")) throw new IllegalStateException("测试写入失败");
                return super.execute(sql, body);
            }
        };
        var unavailable = processor(new ClickHouseMemoryMetricsRepository(failing, mapper, new QueryProperties()), mapper);
        assertThatThrownBy(() -> unavailable.ingest(app, command, Instant.now())).isInstanceOf(EventStoreUnavailableException.class);
        var repository = new ClickHouseMemoryMetricsRepository(healthy, mapper, new QueryProperties());
        assertThat(repository.findByEventId(app, command.eventId())).isEmpty();
        var acknowledgementLost = new ClickHouseHttpClient(connection) {
            /** 插入实际完成后模拟响应无法确认。 */
            @Override public String execute(String sql, String body) {
                String result = super.execute(sql, body);
                if (sql.startsWith("INSERT")) throw new EventStoreUnavailableException();
                return result;
            }
        };
        assertThatThrownBy(() -> processor(new ClickHouseMemoryMetricsRepository(acknowledgementLost, mapper, new QueryProperties()), mapper)
                .ingest(app, command, Instant.now())).isInstanceOf(EventStoreUnavailableException.class);
        assertThat(processor(repository, mapper).ingest(app, command, Instant.now().plusSeconds(86400)).appendResult().duplicate()).isEqualTo(1);
        assertThat(repository.findByEventId(app, command.eventId()).orElseThrow().payload()).isEqualTo(command.memoryPeakSummary());
    }

    /** 返回测试容器连接，只使用公开测试凭据。 */
    private ClickHouseProperties connection() {
        var properties = new ClickHouseProperties();
        properties.setUrl("http://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(8123));
        properties.setUsername("apm_test");
        properties.setPassword("apm_test_password");
        properties.setDatabase("apm");
        return properties;
    }

    /** 幂等准备当前峰值表，保持各测试可单独执行。 */
    private ClickHouseHttpClient databaseClient() throws Exception {
        var properties = connection();
        properties.setDatabase("default");
        var client = new ClickHouseHttpClient(properties);
        client.execute("CREATE DATABASE IF NOT EXISTS apm");
        properties.setDatabase("apm");
        client.execute(new String(getClass().getResourceAsStream("/db/clickhouse/009_memory_runtime_peaks.sql").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        return client;
    }

    /** 创建保留原版本和三项独立上下文的合法命令。 */
    private MemoryIngestCommand command(String id) {
        long end = Instant.now().minusSeconds(86400).toEpochMilli();
        return new MemoryIngestCommand(2, id, "memory_peak_summary", end, "old-session", CrashTestSupport.PROCESS_ID,
                "device", "com.example.app", "old-version", 1, "old-build", "test", "test", "13", "test-device",
                null, null, null, new MemoryPeakSummaryPayload("com.example.app", end - 1000, end,
                new MemoryPeak(200L, end - 900, true, "A"), null, null));
    }

    /** 构造真实写入链路，沿用生产校验和脱敏。 */
    private MemoryEventProcessor processor(ClickHouseMemoryMetricsRepository repository, ObjectMapper mapper) {
        var configuration = CrashTestSupport.ingestProperties();
        return new MemoryEventProcessor(new MemoryEventValidator(configuration, mapper),
                new MemorySanitizer(configuration), new MemoryWriteCoordinator(repository));
    }
}
