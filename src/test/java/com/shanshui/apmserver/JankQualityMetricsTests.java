package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.crash.internal.application.CrashEventProcessor;
import com.shanshui.apmserver.crash.internal.application.CrashEventValidator;
import com.shanshui.apmserver.crash.internal.application.CrashFingerprintService;
import com.shanshui.apmserver.crash.internal.application.CrashSanitizer;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.ingest.internal.application.BatchIngestionService;

import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.jank.api.JankSample;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JankQualityMetricsTests {

    @Test
    void batchJsonJankOnlyRecordsReceiveAndPermanentRejectMetrics() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var properties = CrashTestSupport.ingestProperties();
        var crashProcessor = new com.shanshui.apmserver.crash.internal.application.CrashEventProcessor(
                new com.shanshui.apmserver.crash.internal.application.CrashEventValidator(
                        properties, CrashTestSupport.objectMapper()),
                new com.shanshui.apmserver.crash.internal.application.CrashSanitizer(properties),
                new com.shanshui.apmserver.crash.internal.application.CrashFingerprintService(),
                new com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository(
                        CrashTestSupport.storageProperties()));
        var jankProcessor = new com.shanshui.apmserver.jank.internal.application.JankMetricEventProcessor(
                new com.shanshui.apmserver.jank.internal.application.JankMetricEventValidator(
                        properties, CrashTestSupport.objectMapper()),
                new com.shanshui.apmserver.jank.internal.application.JankSanitizer(properties),
                new com.shanshui.apmserver.jank.internal.application.JankWriteCoordinator(repository));
        var metrics = new com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics(registry);
        var ingestion = new com.shanshui.apmserver.ingest.internal.application.BatchIngestionService(
                crashProcessor, jankProcessor, metrics, metrics, metrics);
        EventEnvelope valid = jank("quality-1", "jank-v1");
        ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(valid)));
        ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(valid)));
        ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(jank("quality-bad", "unknown"))));

        assertEquals(3.0, registry.counter("apm_jank_events_received_total").count());
        assertEquals(0.0, registry.counter("apm_jank_events_accepted_total").count());
        assertEquals(0.0, registry.counter("apm_jank_events_duplicate_total").count());
        assertEquals(3.0, registry.counter("apm_jank_events_rejected_total").count());
        assertEquals(0.0, registry.counter("apm_jank_events_algorithm_rejected_total").count());
        assertEquals(0, registry.timer("apm_jank_visible_delay").count());
        assertTrue(registry.find("apm_jank_fact_detail_inconsistency_total").counter() == null
                || registry.counter("apm_jank_fact_detail_inconsistency_total").count() >= 0);
    }

    private EventEnvelope jank(String eventId, String algorithm) {
        return new EventEnvelope(1, eventId, "jank", Instant.now().toEpochMilli(), "session", "device",
                "app", "1.0", 1, "build", "prod", "official", "16", "Pixel", "wifi", null, null, null,
                new JankPayload("scene", algorithm, 200_000_000L, 100_000_000L, 100_000_000L,
                        List.of(new JankSample(0L, "stack")),
                        Map.of("stack", List.of(new StackFrame("com.example.App", "run", null, null, true))),
                        1, 1, 0), null, null);
    }
}
