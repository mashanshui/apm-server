package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicrometerTelemetryMetricsTests {

    @Test
    void preservesAllExistingMeterNamesTagsAndTimers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerTelemetryMetrics metrics = new MicrometerTelemetryMetrics(registry);

        metrics.received(2);
        metrics.rejected(1);
        metrics.duplicate(1);
        metrics.retryableFailure();
        metrics.denominatorInsufficient();
        metrics.uploadDelay(Duration.ofMillis(5));
        metrics.visibleDelay(Duration.ofMillis(6));
        metrics.jankReceived();
        metrics.jankAccepted(1);
        metrics.jankRejected();
        metrics.jankDuplicate(1);
        metrics.jankSchemaVersion(3);
        metrics.jankAlgorithmVersion("jank", "jank-artifact-v2");
        metrics.jankSchemaRejected();
        metrics.jankAlgorithmRejected();
        metrics.jankVisibleDelay(Duration.ofMillis(7));
        metrics.jankFactDetailInconsistency();

        assertEquals(2.0, registry.counter("apm_ingest_events_received_total").count());
        assertEquals(1.0, registry.counter("apm_ingest_events_rejected_total").count());
        assertEquals(1.0, registry.counter("apm_ingest_events_duplicate_total").count());
        assertEquals(1.0, registry.counter("apm_ingest_retryable_failures_total").count());
        assertEquals(1.0, registry.counter("apm_crash_denominator_insufficient_total").count());
        assertEquals(1, registry.timer("apm_ingest_upload_delay").count());
        assertEquals(1, registry.timer("apm_crash_visible_delay").count());
        assertEquals(1.0, registry.counter("apm_jank_events_received_total").count());
        assertEquals(1.0, registry.counter("apm_jank_events_accepted_total").count());
        assertEquals(1.0, registry.counter("apm_jank_events_rejected_total").count());
        assertEquals(1.0, registry.counter("apm_jank_events_duplicate_total").count());
        assertEquals(1.0, registry.counter("apm_jank_schema_version_total", "version", "3").count());
        assertEquals(1.0, registry.counter("apm_jank_algorithm_version_total", "event_type", "jank",
                "version", "jank-artifact-v2").count());
        assertEquals(1.0, registry.counter("apm_jank_events_schema_rejected_total").count());
        assertEquals(1.0, registry.counter("apm_jank_events_algorithm_rejected_total").count());
        assertEquals(1, registry.timer("apm_jank_visible_delay").count());
        assertEquals(1.0, registry.counter("apm_jank_fact_detail_inconsistency_total").count());
    }
}
