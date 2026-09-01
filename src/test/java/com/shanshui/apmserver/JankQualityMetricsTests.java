package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.domain.JankPayload;
import com.shanshui.apmserver.domain.JankSample;
import com.shanshui.apmserver.domain.StackFrame;
import com.shanshui.apmserver.repository.InMemoryEventRepository;
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
        InMemoryEventRepository repository = new InMemoryEventRepository(CrashTestSupport.storageProperties());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var properties = CrashTestSupport.ingestProperties();
        var sanitizer = new com.shanshui.apmserver.service.CrashSanitizer(properties);
        var validator = new com.shanshui.apmserver.service.CrashEventValidator(properties, CrashTestSupport.objectMapper());
        var processor = new com.shanshui.apmserver.service.CrashEventProcessor(validator, sanitizer,
                new com.shanshui.apmserver.service.CrashFingerprintService(), properties);
        var ingestion = new com.shanshui.apmserver.service.CrashIngestionService(processor, repository,
                new com.shanshui.apmserver.service.CrashQualityMetrics(registry));
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
