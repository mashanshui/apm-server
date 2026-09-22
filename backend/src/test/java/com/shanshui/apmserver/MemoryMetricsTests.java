package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.crash.internal.application.CrashEventProcessor;
import com.shanshui.apmserver.crash.internal.application.CrashEventValidator;
import com.shanshui.apmserver.crash.internal.application.CrashFingerprintService;
import com.shanshui.apmserver.crash.internal.application.CrashSanitizer;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.ingest.internal.application.BatchIngestionService;
import com.shanshui.apmserver.ingest.internal.protocol.EventSchemaValidator;
import com.shanshui.apmserver.jank.internal.application.JankMetricEventProcessor;
import com.shanshui.apmserver.jank.internal.application.JankMetricEventValidator;
import com.shanshui.apmserver.jank.internal.application.JankSanitizer;
import com.shanshui.apmserver.jank.internal.application.JankWriteCoordinator;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.memory.api.MemoryMetricStats;
import com.shanshui.apmserver.memory.api.MemorySamplePayload;
import com.shanshui.apmserver.memory.internal.application.MemoryEventProcessor;
import com.shanshui.apmserver.memory.internal.application.MemoryEventValidator;
import com.shanshui.apmserver.memory.internal.application.MemoryMetricsQueryService;
import com.shanshui.apmserver.memory.internal.application.MemorySanitizer;
import com.shanshui.apmserver.memory.internal.application.MemoryWriteCoordinator;
import com.shanshui.apmserver.memory.internal.domain.MemoryQueryCommand;
import com.shanshui.apmserver.memory.internal.persistence.InMemoryMemoryMetricsRepository;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryMetricsTests {

    private static final UUID APP_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");

    @Test
    void summaryUsesIndependentSamplesAndLinearInterpolation() {
        IngestConfigurationProperties properties = properties();
        InMemoryMemoryMetricsRepository repository = new InMemoryMemoryMetricsRepository(CrashTestSupport.storageProperties());
        BatchIngestionService ingestion = ingestion(properties, repository);
        ingestion.ingest(APP_ID, CrashTestSupport.batch(List.of(
                memory("m0", 0L, 10L, null), memory("m1", 100L, 20L, 5L),
                memory("m2", 200L, null, 6L), memory("m3", 300L, 40L, 7L),
                memory("missing", null, 50L, 8L))));
        ingestion.ingest(APP_ID, CrashTestSupport.batch(List.of(memory("m1", 100L, 20L, 5L))));

        QueryProperties queryProperties = CrashTestSupport.queryProperties();
        MemoryMetricsQueryService query = new MemoryMetricsQueryService(repository, queryProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));
        var response = query.summary(APP_ID, NOW.minusSeconds(60).toString(), NOW.plusSeconds(1).toString(),
                MemoryQueryCommand.empty());

        assertThat(response.status()).isEqualTo("ok");
        assertStats(response.pss(), 4, 150D, 150D, 270D, 285D, 297D);
        assertThat(response.vss().sampleCount()).isEqualTo(4);
        assertThat(response.javaHeap().sampleCount()).isEqualTo(4);
        assertThat(response.pss().averageBytes()).isEqualTo(150D);
    }

    @Test
    void zeroIsValidAndMissingAllMetricsIsRejected() {
        IngestConfigurationProperties properties = properties();
        var validator = new MemoryEventValidator(properties, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        validator.validate(new com.shanshui.apmserver.memory.api.MemoryIngestCommand(2, "zero", "memory_sample",
                NOW.toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, "device", "com.example.app", "1.0", 1, "build", "prod",
                "official", "16", "Pixel", "wifi", null, null,
                new MemorySamplePayload(0L, null, null, "com.example.app", false, null)));
        assertThatThrownBy(() -> validator.validate(new com.shanshui.apmserver.memory.api.MemoryIngestCommand(2,
                "missing", "memory_sample", NOW.toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, "device", "com.example.app", "1.0",
                1, "build", "prod", "official", "16", "Pixel", "wifi", null, null,
                new MemorySamplePayload(null, null, null, "com.example.app", true, null))))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("至少需要提供一项");
    }

    @Test
    void rejectsNegativeAndUnsafeMemoryValuesAndSchemaFractions() {
        IngestConfigurationProperties properties = properties();
        var validator = new MemoryEventValidator(properties, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> validator.validate(new com.shanshui.apmserver.memory.api.MemoryIngestCommand(2,
                "negative", "memory_sample", NOW.toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, "device", "com.example.app", "1.0",
                1, "build", "prod", "official", "16", "Pixel", "wifi", null, null,
                new MemorySamplePayload(-1L, null, null, "com.example.app", true, null))))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("非负整数");
        assertThatThrownBy(() -> validator.validate(new com.shanshui.apmserver.memory.api.MemoryIngestCommand(2,
                "too-large", "memory_sample", NOW.toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, "device", "com.example.app", "1.0",
                1, "build", "prod", "official", "16", "Pixel", "wifi", null, null,
                new MemorySamplePayload(properties.getMaxMemoryMetricBytes() + 1, null, null,
                        "com.example.app", true, null))))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("安全整数上限");

        EventSchemaValidator schema = new EventSchemaValidator();
        assertThatThrownBy(() -> schema.validateBatch(new ObjectMapper().readTree(
                "{\"requestId\":\"fraction\",\"events\":[{\"schemaVersion\":2,"
                        + "\"eventId\":\"fraction\",\"eventType\":\"memory_sample\","
                        + "\"occurredAt\":1,\"sessionId\":\"s\",\"processId\":\"11111111-1111-4111-8111-111111111111\",\"anonymousDeviceId\":\"d\","
                        + "\"packageName\":\"com.example.app\",\"appVersion\":\"1\",\"versionCode\":1,"
                        + "\"buildId\":\"b\",\"environment\":\"p\",\"channel\":\"c\","
                        + "\"osVersion\":\"16\",\"deviceModel\":\"m\",\"memorySample\":{"
                        + "\"pssBytes\":1.5,\"processName\":\"com.example.app\",\"foreground\":true}}]}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("pssBytes 必须是整数");
    }

    @Test
    void mixedBatchRejectsConflictingMemoryPayloadPerEvent() {
        IngestConfigurationProperties properties = properties();
        InMemoryMemoryMetricsRepository repository = new InMemoryMemoryMetricsRepository(CrashTestSupport.storageProperties());
        BatchIngestionService ingestion = ingestion(properties, repository);
        EventEnvelope conflicting = new EventEnvelope(2, "conflict", "memory_sample", NOW.toEpochMilli(), "session", CrashTestSupport.PROCESS_ID,
                "device-conflict", "com.example.app", "1.0", 1, "build", "prod", "official", "16", "Pixel",
                "wifi", null, null, CrashTestSupport.crash("java.lang.IllegalStateException", "boom", 1, "A"),
                null, null, null, new MemorySamplePayload(10L, null, null, "com.example.app", true, null));
        var response = ingestion.ingest(APP_ID, CrashTestSupport.batch(List.of(memory("valid", 10L, null, null), conflicting)));
        assertThat(response.accepted()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.errors()).extracting(error -> error.code()).contains("INVALID_MEMORY_PAYLOAD");
    }

    @Test
    void hashesAnonymousDeviceIdExactlyOnce() {
        IngestConfigurationProperties properties = properties();
        InMemoryMemoryMetricsRepository repository = new InMemoryMemoryMetricsRepository(CrashTestSupport.storageProperties());
        BatchIngestionService ingestion = ingestion(properties, repository);
        ingestion.ingest(APP_ID, CrashTestSupport.batch(List.of(memory("hash", 1L, null, null))));
        var stored = repository.findByEventId(APP_ID, "hash").orElseThrow();
        assertThat(stored.processId()).isEqualTo(CrashTestSupport.PROCESS_ID);
        assertThat(stored.metadata().anonymousDeviceId())
                .isEqualTo(new MemorySanitizer(properties).hashDeviceId("device-hash"));
    }

    @Test
    void preservesDigitsInProcessAndActivityNamesForExactFilters() {
        IngestConfigurationProperties properties = properties();
        InMemoryMemoryMetricsRepository repository = new InMemoryMemoryMetricsRepository(CrashTestSupport.storageProperties());
        BatchIngestionService ingestion = ingestion(properties, repository);
        EventEnvelope event = new EventEnvelope(2, "named", "memory_sample", NOW.toEpochMilli(), "session", CrashTestSupport.PROCESS_ID,
                "device-named", "com.example.app2", "1.0", 1, "symbol-validation-2026-09-20", "prod", "official", "16", "Pixel",
                "wifi", null, null, null, null, null, null,
                new MemorySamplePayload(42L, null, null, "com.example.app2:worker", true,
                        "com.example.MainActivity2"));

        ingestion.ingest(APP_ID, CrashTestSupport.batch(List.of(event)));

        var stored = repository.findByEventId(APP_ID, "named").orElseThrow();
        assertThat(stored.processName()).isEqualTo("com.example.app2:worker");
        assertThat(stored.scene()).isEqualTo("com.example.MainActivity2");
        assertThat(stored.metadata().buildId()).isEqualTo("symbol-validation-2026-09-20");
    }

    @Test
    void trendFillsEmptyUtcBucketsWithNulls() {
        IngestConfigurationProperties properties = properties();
        InMemoryMemoryMetricsRepository repository = new InMemoryMemoryMetricsRepository(CrashTestSupport.storageProperties());
        BatchIngestionService ingestion = ingestion(properties, repository);
        ingestion.ingest(APP_ID, CrashTestSupport.batch(List.of(
                memoryAt("first", NOW.plus(5, java.time.temporal.ChronoUnit.MINUTES), 100L),
                memoryAt("third", NOW.plus(2, java.time.temporal.ChronoUnit.HOURS)
                        .plus(5, java.time.temporal.ChronoUnit.MINUTES), 300L))));
        MemoryMetricsQueryService query = new MemoryMetricsQueryService(repository, CrashTestSupport.queryProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var trend = query.trend(APP_ID, "pss", "hour", NOW.toString(),
                NOW.plus(3, java.time.temporal.ChronoUnit.HOURS).toString(), MemoryQueryCommand.empty());
        assertThat(trend.points()).hasSize(3);
        assertThat(trend.points().get(1).sampleCount()).isZero();
        assertThat(trend.points().get(1).p50Bytes()).isNull();
        assertThat(trend.points().get(0).p50Bytes()).isEqualTo(100D);
        assertThat(trend.points().get(2).p50Bytes()).isEqualTo(300D);
    }

    private BatchIngestionService ingestion(IngestConfigurationProperties properties,
                                             InMemoryMemoryMetricsRepository memoryRepository) {
        var storage = CrashTestSupport.storageProperties();
        var crashRepository = new InMemoryCrashRepository(storage);
        var crashProcessor = new CrashEventProcessor(new CrashEventValidator(properties, new ObjectMapper()),
                new CrashSanitizer(properties), new CrashFingerprintService(), crashRepository);
        var jankRepository = new InMemoryJankEventRepository(storage);
        var jankProcessor = new JankMetricEventProcessor(new JankMetricEventValidator(properties, new ObjectMapper()),
                new JankSanitizer(properties), new JankWriteCoordinator(jankRepository));
        var memoryProcessor = new MemoryEventProcessor(new MemoryEventValidator(properties, new ObjectMapper()),
                new MemorySanitizer(properties), new MemoryWriteCoordinator(memoryRepository));
        var metrics = new MicrometerTelemetryMetrics(new SimpleMeterRegistry());
        return new BatchIngestionService(crashProcessor, jankProcessor, memoryProcessor, metrics, metrics, metrics);
    }

    private EventEnvelope memory(String id, Long pss, Long vss, Long javaHeap) {
        return memoryAt(id, NOW.minusSeconds(1), pss, vss, javaHeap);
    }

    private EventEnvelope memoryAt(String id, Instant occurredAt, Long pss) {
        return memoryAt(id, occurredAt, pss, null, null);
    }

    private EventEnvelope memoryAt(String id, Instant occurredAt, Long pss, Long vss, Long javaHeap) {
        return new EventEnvelope(2, id, "memory_sample", occurredAt.toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, "device-" + id,
                "com.example.app", "1.0", 1, "build", "prod", "official", "16", "Pixel", "wifi", null, null,
                null, null, null, null, new MemorySamplePayload(pss, vss, javaHeap, "com.example.app", true,
                        "com.example.HomeActivity"));
    }

    private IngestConfigurationProperties properties() {
        return CrashTestSupport.ingestProperties();
    }

    private void assertStats(MemoryMetricStats value, long count, double average, double p50, double p90,
                             double p95, double p99) {
        assertThat(value.sampleCount()).isEqualTo(count);
        assertThat(value.averageBytes()).isEqualTo(average);
        assertThat(value.p50Bytes()).isEqualTo(p50);
        assertThat(value.p90Bytes()).isEqualTo(p90);
        assertThat(value.p95Bytes()).isEqualTo(p95);
        assertThat(value.p99Bytes()).isEqualTo(p99);
        assertThat(value.status()).isEqualTo("ok");
    }
}
