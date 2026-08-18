package com.shanshui.apmserver.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CrashQualityMetrics {

    private final Counter received;
    private final Counter rejected;
    private final Counter duplicate;
    private final Counter retryableFailures;
    private final Counter denominatorInsufficient;
    private final Timer uploadDelay;
    private final Timer visibleDelay;

    public CrashQualityMetrics(MeterRegistry registry) {
        this.received = registry.counter("apm_ingest_events_received_total");
        this.rejected = registry.counter("apm_ingest_events_rejected_total");
        this.duplicate = registry.counter("apm_ingest_events_duplicate_total");
        this.retryableFailures = registry.counter("apm_ingest_retryable_failures_total");
        this.denominatorInsufficient = registry.counter("apm_crash_denominator_insufficient_total");
        this.uploadDelay = registry.timer("apm_ingest_upload_delay");
        this.visibleDelay = registry.timer("apm_crash_visible_delay");
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
}
