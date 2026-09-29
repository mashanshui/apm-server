package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.crash.internal.application.CrashQueryService;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryCommand;
import com.shanshui.apmserver.crash.internal.persistence.ClickHouseCrashRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import com.shanshui.apmserver.platform.api.StorageProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** 显式启动的合成容量基线；常规回归不会加载百万行数据。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_CRASH_BENCHMARK", matches = "true")
class CrashQueryPerformanceBaselineTests {

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "apm_test")
            .withEnv("CLICKHOUSE_PASSWORD", "apm_test_password")
            .withExposedPorts(8123);

    @Test
    void recordsThreeSizesAndTwoConcurrencyLevels() throws Exception {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        properties.setDatabase("default");
        properties.setUsername("apm_test");
        properties.setPassword("apm_test_password");
        ClickHouseHttpClient client = new ClickHouseHttpClient(properties);
        client.execute("CREATE TABLE apm_event_raw (app_id UUID, event_id String, event_type String, "
                + "event_time DateTime64(3, 'UTC'), received_time DateTime64(3, 'UTC'), session_id String, "
                + "anonymous_device_id String, app_version String, channel String, environment String, "
                + "os_version String, device_model String, crash_fingerprint String, fingerprint_version String, "
                + "crash_exception_type String, version_code Int64, build_id String, symbolication_status String) "
                + "ENGINE = ReplacingMergeTree(received_time) ORDER BY (app_id,event_type,event_time,event_id)");
        StorageProperties storage = CrashTestSupport.storageProperties();
        storage.setMode("clickhouse");
        CrashQueryService query = new CrashQueryService(new ClickHouseCrashRepository(client, new ObjectMapper()),
                CrashTestSupport.queryProperties(), storage,
                new MicrometerTelemetryMetrics(new SimpleMeterRegistry()));
        String from = "2026-09-28T10:00:00Z";
        String to = "2026-09-28T11:00:00Z";
        System.out.println("CRASH_BENCH hardware=" + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + " cpu=" + Runtime.getRuntime().availableProcessors()
                + " jvm=" + System.getProperty("java.version") + " clickhouse=26.7.3.19");
        for (int size : new int[]{10_000, 100_000, 1_000_000}) {
            UUID appId = UUID.nameUUIDFromBytes(("crash-benchmark-" + size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            seed(client, appId, size);
            for (int concurrency : new int[]{1, 2}) {
                List<Long> milliseconds = Collections.synchronizedList(new ArrayList<>());
                List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<Callable<Void>> calls = new ArrayList<>();
                    for (int run = 0; run < 5; run++) {
                        calls.add(() -> {
                            long started = System.nanoTime();
                            try {
                                var overview = query.overview(appId, from, to, CrashQueryCommand.empty());
                                if (overview.stats().crashEvents() != size / 10) {
                                    throw new AssertionError("Crash 精确数量与固定集不符");
                                }
                                outcomes.add("ok");
                            } catch (QueryValidationException ex) {
                                outcomes.add(ex.getCode());
                            } finally {
                                milliseconds.add((System.nanoTime() - started) / 1_000_000);
                            }
                            return null;
                        });
                    }
                    for (int start = 0; start < calls.size(); start += concurrency) {
                        executor.invokeAll(calls.subList(start, Math.min(calls.size(), start + concurrency)));
                    }
                }
                milliseconds.sort(Long::compareTo);
                long heapPeak = ManagementFactory.getMemoryPoolMXBeans().stream()
                        .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                        .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
                client.execute("SYSTEM FLUSH LOGS");
                String log = client.execute("SELECT sum(read_rows) AS read_rows, sum(read_bytes) AS read_bytes, "
                        + "sum(result_bytes) AS result_bytes, count() AS logged_queries "
                        + "FROM system.query_log WHERE type != 'QueryStart' "
                        + "AND position(query, 'signal_count') > 0 AND position(query, '" + appId + "') > 0 "
                        + "AND position(query, 'system.query_log') = 0 FORMAT JSONEachRow");
                JsonNode metrics = new ObjectMapper().readTree(log);
                System.out.println("CRASH_BENCH rows=" + size + " concurrency=" + concurrency
                        + " p50_ms=" + milliseconds.get(2) + " p95_ms=" + milliseconds.get(4)
                        + " outcomes=" + outcomes + " read_rows=" + metrics.path("read_rows").asLong()
                        + " read_bytes=" + metrics.path("read_bytes").asLong()
                        + " result_bytes=" + metrics.path("result_bytes").asLong()
                        + " logged_queries=" + metrics.path("logged_queries").asLong()
                        + " jvm_heap_peak_bytes=" + heapPeak);
            }
            var narrow = query.overview(appId, from, "2026-09-28T10:00:01Z", CrashQueryCommand.empty());
            long expectedCrash = (size - 1L) / 3600 + 1;
            if (narrow.stats().crashEvents() != expectedCrash) {
                throw new AssertionError("小时间窗 Crash 数量与合成数据不符");
            }
            client.execute("SYSTEM FLUSH LOGS");
            String narrowLog = client.execute("SELECT read_rows, read_bytes, result_bytes FROM system.query_log "
                    + "WHERE type = 'QueryFinish' AND position(query, 'signal_count') > 0 "
                    + "AND position(query, '" + appId + "') > 0 "
                    + "AND position(query, '10:00:01') > 0 ORDER BY event_time_microseconds DESC "
                    + "LIMIT 1 FORMAT JSONEachRow");
            JsonNode narrowMetrics = new ObjectMapper().readTree(narrowLog);
            System.out.println("CRASH_BENCH narrow_rows=" + size + " crash_events=" + expectedCrash
                    + " read_rows=" + narrowMetrics.path("read_rows").asLong()
                    + " read_bytes=" + narrowMetrics.path("read_bytes").asLong()
                    + " result_bytes=" + narrowMetrics.path("result_bytes").asLong());
        }
    }

    /** 合成 10% Crash、90% app_start；各事件 ID 唯一，固定一小时内的数据。 */
    private void seed(ClickHouseHttpClient client, UUID appId, int size) {
        client.execute("INSERT INTO apm_event_raw SELECT toUUID('" + appId + "'), concat('event-',toString(number)), "
                + "if(number % 10 = 0,'crash','app_start'), "
                + "toDateTime64('2026-09-28 10:00:00',3,'UTC') + (number % 3600), "
                + "toDateTime64('2026-09-28 12:00:00',3,'UTC'), "
                + "concat('session-',toString(intDiv(number,2))), "
                + "concat('device-',toString(number % 10000)), '1.0','official','production','16','Pixel', "
                + "if(number % 10 = 0,concat('fp-',toString(number % 100)),''), "
                + "'v1','java.lang.IllegalStateException',1,'build-1','raw_only' FROM numbers(" + size + ")");
    }
}
