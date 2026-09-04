package com.shanshui.apmserver;

import com.sun.net.httpserver.HttpServer;
import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.jank.api.JankSample;
import com.shanshui.apmserver.jank.internal.domain.MetricQueryFilter;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.persistence.ClickHouseJankEventRepository;
import com.shanshui.apmserver.jank.internal.persistence.ClickHouseJankMetricsRepository;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.crash.internal.application.CrashEventProcessor;
import com.shanshui.apmserver.crash.internal.application.CrashEventValidator;
import com.shanshui.apmserver.crash.internal.application.CrashFingerprintService;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.crash.internal.application.CrashSanitizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseJankRepositoryTests {

    @Test
    void writesRawFactAndDetailSeparatelyAndUsesFinalForExistingIds() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            byte[] response = new byte[0];
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ClickHouseProperties properties = new ClickHouseProperties();
            properties.setUrl("http://localhost:" + server.getAddress().getPort());
            ClickHouseJankEventRepository repository = repository(properties, new SimpleMeterRegistry());
            JankEvent event = event();
            assertEquals(1, repository.append(TestAppIds.id("app-a"), List.of(event)).accepted());
            assertEquals(4, requests.size());
            assertTrue(requests.get(0).contains("FROM apm_event_raw FINAL"));
            assertTrue(requests.stream().anyMatch(request -> request.contains("INSERT INTO apm_event_raw")));
            assertTrue(requests.stream().anyMatch(request -> request.contains("INSERT INTO apm_jank_event")));
            assertTrue(requests.stream().anyMatch(request -> request.contains("INSERT INTO apm_jank_detail")));
            String factInsert = requests.stream()
                    .filter(request -> request.contains("INSERT INTO apm_jank_event"))
                    .findFirst().orElseThrow();
            assertTrue(factInsert.contains("\"parsed_sample_count\":1"));
            assertTrue(factInsert.contains("\"missing_sample_count\":0"));
            assertFalse(factInsert.contains("attempted_sample_count"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsClickHouseUnavailableToRetryableStoreError() {
        ClickHouseProperties properties = new ClickHouseProperties();
        properties.setUrl("http://127.0.0.1:1");
        ClickHouseJankEventRepository repository = repository(properties, new SimpleMeterRegistry());
        assertThrows(EventStoreUnavailableException.class,
                () -> repository.append(TestAppIds.id("app-a"), List.of(event())));
    }

    @Test
    void recordsFactDetailInconsistencyWhenDetailWriteFailsAfterFactWrite() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = calls.incrementAndGet();
            int status = call == 4 ? 503 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        try {
            ClickHouseProperties properties = new ClickHouseProperties();
            properties.setUrl("http://localhost:" + server.getAddress().getPort());
            var registry = new SimpleMeterRegistry();
            var metrics = new MicrometerTelemetryMetrics(registry);
            ClickHouseJankEventRepository repository = new ClickHouseJankEventRepository(
                    new ClickHouseHttpClient(properties), new ObjectMapper(), metrics);
            assertThrows(EventStoreUnavailableException.class, () -> repository.append(TestAppIds.id("app-a"), List.of(event())));
            assertEquals(1.0, registry.counter("apm_jank_fact_detail_inconsistency_total").count());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesMissingDetailWhenTheSameEventIsReportedAgain() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int call = calls.incrementAndGet();
            int status = call == 4 ? 503 : 200;
            byte[] response = call == 5 || call == 6
                    ? "{\"event_id\":\"ch-jank\"}\n".getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ClickHouseProperties properties = new ClickHouseProperties();
            properties.setUrl("http://localhost:" + server.getAddress().getPort());
            ClickHouseJankEventRepository repository = repository(properties, new SimpleMeterRegistry());
            JankEvent event = event();
            assertThrows(EventStoreUnavailableException.class, () -> repository.append(TestAppIds.id("app-a"), List.of(event)));
            assertEquals(1, repository.append(TestAppIds.id("app-a"), List.of(event)).duplicate());
            assertEquals(8, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesFpsAndSuspensionTrendRowsWithMetricSpecificStatuses() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String query = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(query);
            String body = query.contains("apm_frame_scene_summary")
                    ? "{\"bucket_start\":\"2026-08-15 10:00:00\",\"bucket_end\":\"2026-08-15 11:00:00\","
                    + "\"algorithm_version\":\"fps-v1\",\"total_records\":2,\"valid_records\":2,"
                    + "\"average_value\":50.0,\"p50_value\":55.0,\"p90_value\":45.0,\"p99_value\":45.0}\n"
                    : "{\"bucket_start\":\"2026-08-15 00:00:00\",\"bucket_end\":\"2026-08-16 00:00:00\","
                    + "\"algorithm_version\":\"suspension-v1\",\"total_records\":2,\"valid_records\":0,"
                    + "\"average_value\":0,\"p50_value\":0,\"p90_value\":0,\"p99_value\":0}\n";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            ClickHouseProperties properties = new ClickHouseProperties();
            properties.setUrl("http://localhost:" + server.getAddress().getPort());
            ClickHouseJankMetricsRepository repository = new ClickHouseJankMetricsRepository(
                    new ClickHouseHttpClient(properties), new ObjectMapper());
            MetricQueryFilter filter = new MetricQueryFilter(TestAppIds.id("app-a"),
                    Instant.parse("2026-08-15T00:00:00Z"), Instant.parse("2026-08-16T00:00:00Z"),
                    null, null, null, null, null, null, null, 50, 2_000);

            var fps = repository.queryFpsTrend(filter, "hour");
            assertEquals(1, fps.size());
            assertEquals(Instant.parse("2026-08-15T10:00:00Z"), fps.get(0).bucketStart());
            assertEquals(55.0, fps.get(0).p50());
            assertEquals("ok", fps.get(0).status());

            var suspension = repository.querySuspensionTrend(filter);
            assertEquals("denominator_insufficient", suspension.get(0).status());
            assertEquals(null, suspension.get(0).average());
            assertTrue(requests.get(0).contains("FINAL"));
            assertTrue(requests.get(1).contains("prefer_column_name_to_alias = 1"));
        } finally {
            server.stop(0);
        }
    }

    private ClickHouseJankEventRepository repository(ClickHouseProperties properties, SimpleMeterRegistry registry) {
        return new ClickHouseJankEventRepository(new ClickHouseHttpClient(properties), new ObjectMapper(),
                new MicrometerTelemetryMetrics(registry));
    }

    private JankEvent event() {
        EventEnvelope envelope = new EventEnvelope(1, "ch-jank", "jank", Instant.now().toEpochMilli(),
                "session", "device", "app", "1.0", 1, "build", "prod", "official", "16", "Pixel", "wifi",
                null, null, null, new JankPayload("scene", "jank-v1", 200_000_000L, 100_000_000L,
                100_000_000L, List.of(new JankSample(0L, "stack")),
                java.util.Map.of("stack", List.of(new StackFrame("com.example.App", "run", null, null, true))),
                1, 1, 0), null, null);
        return JankTestSupport.storedEvent(TestAppIds.id("app-a"), envelope);
    }
}
