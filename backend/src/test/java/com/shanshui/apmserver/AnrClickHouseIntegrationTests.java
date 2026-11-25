package com.shanshui.apmserver;

import com.shanshui.apmserver.anr.api.AnrPayload;
import com.shanshui.apmserver.anr.internal.domain.AnrEvent;
import com.shanshui.apmserver.anr.internal.persistence.ClickHouseAnrRepository;
import com.shanshui.apmserver.anr.internal.application.AnrQueryService;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用隔离的真实 ClickHouse 验证 ANR 写入及确认丢失恢复。 */
@Testcontainers
class AnrClickHouseIntegrationTests {
    /** 测试专用 ClickHouse，不访问已有业务数据。 */
    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>("clickhouse/clickhouse-server:26.7.3.19")
            .withEnv("CLICKHOUSE_USER", "apm_test")
            .withEnv("CLICKHOUSE_PASSWORD", "apm_test_password")
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123);

    /** 并发重复、跨应用和写入响应丢失均固定首次内容。 */
    @Test
    void confirmsWritesAndRecoversAfterLostAcknowledgement() throws Exception {
        var properties = new ClickHouseProperties();
        properties.setUrl("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        properties.setDatabase("default");
        properties.setUsername("apm_test");
        properties.setPassword("apm_test_password");
        var client = new ClickHouseHttpClient(properties);
        client.execute("CREATE DATABASE IF NOT EXISTS apm");
        String ddl;
        try (var stream = getClass().getResourceAsStream("/db/clickhouse/013_anr_events.sql")) {
            ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        client.execute(ddl);
        try (var stream = getClass().getResourceAsStream("/db/clickhouse/014_anr_symbolication_state.sql")) {
            client.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        properties.setDatabase("apm");
        var repository = new ClickHouseAnrRepository(client, new ObjectMapper());
        UUID appId = UUID.randomUUID();
        var initial = event(appId, "shared-id", "first");
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(index -> executor.submit(() -> repository.append(initial))).toList();
            int accepted = 0;
            int duplicate = 0;
            for (var call : calls) {
                var result = call.get();
                accepted += result.accepted();
                duplicate += result.duplicate();
            }
            assertThat(accepted).isEqualTo(1);
            assertThat(duplicate).isEqualTo(15);
        }
        assertThat(repository.append(event(appId, "shared-id", "changed")).duplicate()).isEqualTo(1);
        assertThat(repository.find(appId, "shared-id").orElseThrow().payload().mainThreadStack()).isEqualTo("first");
        UUID otherApp = UUID.randomUUID();
        assertThat(repository.append(event(otherApp, "shared-id", "other")).accepted()).isEqualTo(1);
        assertThat(repository.find(otherApp, "shared-id").orElseThrow().payload().mainThreadStack()).isEqualTo("other");

        // 模拟服务端已写入而 HTTP 应答在数据库与服务之间丢失。
        var loseResponse = new AtomicBoolean(true);
        var faultyClient = new ClickHouseHttpClient(properties) {
            /** 首次写入后丢失响应，其他请求直接转发。 */
            @Override
            public String execute(String query, String body) {
                String result = super.execute(query, body);
                if (query.startsWith("INSERT INTO apm_anr_events") && loseResponse.getAndSet(false)) {
                    throw new EventStoreUnavailableException();
                }
                return result;
            }
        };
        var uncertain = new ClickHouseAnrRepository(faultyClient, new ObjectMapper());
        var pending = event(appId, "lost-response", "retained");
        assertThatThrownBy(() -> uncertain.append(pending)).isInstanceOf(EventStoreUnavailableException.class);
        assertThat(repository.append(pending).duplicate()).isEqualTo(1);
        assertThat(repository.find(appId, "lost-response").orElseThrow().payload().mainThreadStack()).isEqualTo("retained");
        assertThat(client.execute("SELECT count() FROM apm_anr_events FINAL WHERE app_id='" + appId
                + "' AND event_id='lost-response'").trim()).isEqualTo("1");

        // 写入前失败不能产生确认，故障恢复后原事件仍可首次写入。
        var failBeforeWrite = new AtomicBoolean(true);
        var unavailableClient = new ClickHouseHttpClient(properties) {
            /** 首次写入前模拟数据库暂时不可用。 */
            @Override
            public String execute(String query, String body) {
                if (query.startsWith("INSERT INTO apm_anr_events") && failBeforeWrite.getAndSet(false)) {
                    throw new EventStoreUnavailableException();
                }
                return super.execute(query, body);
            }
        };
        var unavailable = new ClickHouseAnrRepository(unavailableClient, new ObjectMapper());
        var retry = event(appId, "failed-before-write", "original");
        assertThatThrownBy(() -> unavailable.append(retry)).isInstanceOf(EventStoreUnavailableException.class);
        assertThat(repository.append(retry).accepted()).isEqualTo(1);
        assertThat(repository.find(appId, "failed-before-write").orElseThrow().payload().mainThreadStack()).isEqualTo("original");

        // 同时间事件和半开时间边界在数据库中完成筛选与分页。
        UUID queryApp = UUID.randomUUID();
        Instant base = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).minusSeconds(10);
        repository.append(eventAt(queryApp, "a", base, "1", "message_timeout", "device-1"));
        repository.append(eventAt(queryApp, "b", base, "1", "message_timeout", "device-1"));
        repository.append(eventAt(queryApp, "c", base.plusMillis(1), "2", "process_error_state", "device-2"));
        repository.append(eventAt(queryApp, "outside", base.plusSeconds(1), "1", "message_timeout", "device-3"));
        repository.append(eventAt(queryApp, "historical", base.minusSeconds(5), "1", "message_timeout", "device-4"));
        // 人为构造跨数据 part 的后到重复，不能让时间筛选先于首次内容去重。
        client.execute("INSERT INTO apm_anr_events SELECT app_id,event_id,event_time + INTERVAL 5 SECOND,received_time,"
                + "first_seen_version - 1,app_version,version_code,device_model,source,anonymous_device_id,metadata_json,payload_json "
                + "FROM apm_anr_events FINAL WHERE app_id='" + queryApp + "' AND event_id='historical'");
        var query = new AnrQueryService(repository, new QueryProperties(), new ObjectMapper(),
                new com.shanshui.apmserver.anr.internal.persistence.ClickHouseAnrSymbolicationStore(client, new ObjectMapper(), new QueryProperties()));
        String from = base.toString();
        String to = base.plusSeconds(1).toString();
        var overview = query.overview(queryApp, from, to, null, null);
        assertThat(overview.eventCount()).isEqualTo(3);
        assertThat(overview.deviceCount()).isEqualTo(2);
        assertThat(query.overview(queryApp, from, to, "1", "message_timeout").eventCount()).isEqualTo(2);
        var page = query.events(queryApp, from, to, "1", "message_timeout", 1, null, null);
        assertThat(page.events()).extracting(item -> item.eventId()).containsExactly("b");
        assertThat(query.events(queryApp, null, null, "1", "message_timeout", 1, page.nextCursor(), null).events())
                .extracting(item -> item.eventId()).containsExactly("a");
        assertThatThrownBy(() -> query.events(queryApp, from, to, "2", "message_timeout", 1, page.nextCursor(), null))
                .isInstanceOf(QueryValidationException.class);
        assertThatThrownBy(() -> query.events(UUID.randomUUID(), from, to, "1", "message_timeout", 1, page.nextCursor(), null))
                .isInstanceOf(QueryValidationException.class);
        assertThat(query.event(queryApp, "a").anr().mainThreadStack()).isEqualTo("stack-a");
    }

    /** 生成保留期内的合成事件。 */
    private AnrEvent event(UUID appId, String id, String stack) {
        Instant now = Instant.now();
        var metadata = new EventMetadata(appId, "com.example.app", id, "anr", now, now, 2,
                "session", "11111111-1111-4111-8111-111111111111", "device", "1", 1,
                "build", "test", "test", "35", "synthetic", null, Map.of(), Map.of());
        return new AnrEvent(metadata, new AnrPayload("message_timeout", stack, false, null, null,
                true, null, null, null, null));
    }

    /** 生成可控制发生时间、筛选和设备的合成事件。 */
    private AnrEvent eventAt(UUID appId, String id, Instant occurredAt, String version, String source, String device) {
        var metadata = new EventMetadata(appId, "com.example.app", id, "anr", occurredAt, Instant.now(), 2,
                "session", "11111111-1111-4111-8111-111111111111", device, version, 1,
                "build", "test", "test", "35", "synthetic", null, Map.of(), Map.of());
        return new AnrEvent(metadata, new AnrPayload(source, "stack-" + id, false, null, null,
                true, null, null, null, null));
    }
}
