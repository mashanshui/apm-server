package com.shanshui.apmserver.bootstrap.internal.observability;

import com.shanshui.apmserver.crash.api.CrashMetrics;
import com.shanshui.apmserver.ingest.api.IngestMetrics;
import com.shanshui.apmserver.jank.api.JankMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MicrometerTelemetryMetrics implements IngestMetrics, CrashMetrics, JankMetrics {

    private final Counter received;
    private final Counter rejected;
    private final Counter duplicate;
    private final Counter retryableFailures;
    private final Counter denominatorInsufficient;
    private final Counter jankReceived;
    private final Counter jankAccepted;
    private final Counter jankRejected;
    private final Counter jankDuplicate;
    private final Counter jankSchemaRejected;
    private final Counter jankAlgorithmRejected;
    private final Counter jankFactDetailInconsistency;
    private final Timer uploadDelay;
    private final Timer visibleDelay;
    private final Timer jankVisibleDelay;
    private final MeterRegistry registry;

    public MicrometerTelemetryMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.received = registry.counter("apm_ingest_events_received_total");
        this.rejected = registry.counter("apm_ingest_events_rejected_total");
        this.duplicate = registry.counter("apm_ingest_events_duplicate_total");
        this.retryableFailures = registry.counter("apm_ingest_retryable_failures_total");
        this.denominatorInsufficient = registry.counter("apm_crash_denominator_insufficient_total");
        this.jankReceived = registry.counter("apm_jank_events_received_total");
        this.jankAccepted = registry.counter("apm_jank_events_accepted_total");
        this.jankRejected = registry.counter("apm_jank_events_rejected_total");
        this.jankDuplicate = registry.counter("apm_jank_events_duplicate_total");
        this.jankSchemaRejected = registry.counter("apm_jank_events_schema_rejected_total");
        this.jankAlgorithmRejected = registry.counter("apm_jank_events_algorithm_rejected_total");
        this.jankFactDetailInconsistency = registry.counter("apm_jank_fact_detail_inconsistency_total");
        this.uploadDelay = registry.timer("apm_ingest_upload_delay");
        this.visibleDelay = registry.timer("apm_crash_visible_delay");
        this.jankVisibleDelay = registry.timer("apm_jank_visible_delay");
    }

    public void received(int count) {
        received.increment(count);
    }

    public void rejected(int count) {
        rejected.increment(count);
    }

    public void duplicate(int count) {
        duplicate.increment(count);
    }

    public void retryableFailure() {
        retryableFailures.increment();
    }

    public void denominatorInsufficient() {
        denominatorInsufficient.increment();
    }

    public void visibleDelay(Duration delay) {
        visibleDelay.record(delay);
    }

    public void uploadDelay(Duration delay) {
        uploadDelay.record(delay);
    }

    public void jankReceived() {
        jankReceived.increment();
    }

    public void jankAccepted(int count) {
        if (count > 0) {
            jankAccepted.increment(count);
        }
    }

    public void jankRejected() {
        jankRejected.increment();
    }

    public void jankDuplicate(int count) {
        if (count > 0) {
            jankDuplicate.increment(count);
        }
    }

    public void jankSchemaVersion(Integer version) {
        registry.counter("apm_jank_schema_version_total", "version",
                version == null ? "unknown" : String.valueOf(version)).increment();
    }

    public void jankAlgorithmVersion(String eventType, String version) {
        registry.counter("apm_jank_algorithm_version_total", "event_type",
                eventType == null ? "unknown" : eventType, "version", safeTag(version)).increment();
    }

    public void jankSchemaRejected() {
        jankSchemaRejected.increment();
    }

    public void jankAlgorithmRejected() {
        jankAlgorithmRejected.increment();
    }

    public void jankVisibleDelay(Duration delay) {
        jankVisibleDelay.record(delay);
    }

    private String safeTag(String value) {
        if (value == null || value.isBlank() || value.length() > 64
                || !value.matches("[A-Za-z0-9._-]+")) {
            return "unknown";
        }
        return value;
    }

    public void jankFactDetailInconsistency() {
        jankFactDetailInconsistency.increment();
    }
}
