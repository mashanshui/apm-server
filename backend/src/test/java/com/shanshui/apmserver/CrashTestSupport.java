package com.shanshui.apmserver;

import com.shanshui.apmserver.platform.api.ClickHouseProperties;
import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.api.CrashIngestCommand;
import com.shanshui.apmserver.ingest.api.EventBatchRequest;
import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.crash.api.ThrowableNode;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
import com.shanshui.apmserver.crash.internal.port.CrashWritePort;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.jank.internal.port.JankEventRepository;
import com.shanshui.apmserver.crash.internal.application.CrashEventProcessor;
import com.shanshui.apmserver.crash.internal.application.CrashEventValidator;
import com.shanshui.apmserver.crash.internal.application.CrashFingerprintService;
import com.shanshui.apmserver.ingest.internal.application.BatchIngestionService;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.crash.internal.application.CrashSanitizer;
import com.shanshui.apmserver.jank.api.JankMetricIngestCommand;
import com.shanshui.apmserver.jank.internal.application.JankMetricEventProcessor;
import com.shanshui.apmserver.jank.internal.application.JankMetricEventValidator;
import com.shanshui.apmserver.jank.internal.application.JankSanitizer;
import com.shanshui.apmserver.jank.internal.application.JankWriteCoordinator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

final class CrashTestSupport {

    private CrashTestSupport() {
    }

    static IngestConfigurationProperties ingestProperties() {
        IngestConfigurationProperties properties = new IngestConfigurationProperties();
        properties.setMaxPastDays(365);
        properties.setMaxFutureSkewMinutes(365 * 24 * 60);
        properties.setDeviceHashSalt("test-salt");
        return properties;
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    static BatchIngestionService ingestion(InMemoryCrashRepository repository, IngestConfigurationProperties properties) {
        return ingestion(repository, new InMemoryJankEventRepository(storageProperties()), properties);
    }

    static BatchIngestionService ingestion(InMemoryJankEventRepository repository, IngestConfigurationProperties properties) {
        return ingestion(new InMemoryCrashRepository(storageProperties()), repository, properties);
    }

    private static BatchIngestionService ingestion(CrashWritePort crashRepository,
                                                     JankEventRepository jankRepository,
                                                     IngestConfigurationProperties properties) {
        CrashSanitizer sanitizer = new CrashSanitizer(properties);
        CrashEventValidator validator = new CrashEventValidator(properties, objectMapper());
        CrashEventProcessor processor = new CrashEventProcessor(validator, sanitizer,
                new CrashFingerprintService(), crashRepository);
        var jankProcessor = new JankMetricEventProcessor(
                new JankMetricEventValidator(properties, objectMapper()), new JankSanitizer(properties),
                new JankWriteCoordinator(jankRepository));
        var metrics = new MicrometerTelemetryMetrics(new SimpleMeterRegistry());
        return new BatchIngestionService(processor, jankProcessor, metrics, metrics, metrics);
    }

    static CrashPayload crash(String exceptionType, String message, int line, String className) {
        return new CrashPayload("jvm", true, List.of(new ThrowableNode(exceptionType, message,
                List.of(new StackFrame(className, "submit", "Checkout.kt", line, true),
                        new StackFrame("android.app.Activity", "performCreate", "Activity.java", 0, false)))));
    }

    static EventEnvelope event(String eventId, String eventType, String sessionId,
                               String deviceId, String version, long occurredAt, CrashPayload crash) {
        return new EventEnvelope(2, eventId, eventType, occurredAt, sessionId, deviceId,
                "com.example.app", version, version.startsWith("3.2") ? 320 : 330,
                version.startsWith("3.2") ? "build-320" : "build-330", "production", "official",
                "16", "Pixel-8", "wifi", null, null, crash);
    }

    static CrashIngestCommand crashCommand(EventEnvelope event) {
        return new CrashIngestCommand(event.schemaVersion(), event.eventId(), event.eventType(), event.occurredAt(),
                event.sessionId(), event.anonymousDeviceId(), event.packageName(), event.appVersion(),
                event.versionCode(), event.buildId(), event.environment(), event.channel(), event.osVersion(),
                event.deviceModel(), event.networkType(), event.measurements(), event.attributes(), event.crash());
    }

    static JankMetricIngestCommand metricCommand(EventEnvelope event) {
        return new JankMetricIngestCommand(event.schemaVersion(), event.eventId(), event.eventType(), event.occurredAt(),
                event.sessionId(), event.anonymousDeviceId(), event.packageName(), event.appVersion(),
                event.versionCode(), event.buildId(), event.environment(), event.channel(), event.osVersion(),
                event.deviceModel(), event.networkType(), event.measurements(), event.attributes(),
                event.frameSceneSummary(), event.foregroundSuspensionSummary());
    }

    static QueryProperties queryProperties() {
        QueryProperties properties = new QueryProperties();
        properties.setMaxRangeDays(365);
        properties.setDefaultLimit(50);
        properties.setMaxLimit(500);
        return properties;
    }

    static StorageProperties storageProperties() {
        StorageProperties properties = new StorageProperties();
        properties.setMode("memory");
        return properties;
    }

    static ClickHouseProperties clickHouseProperties() {
        return new ClickHouseProperties();
    }

    static EventBatchRequest batch(List<EventEnvelope> events) {
        return new EventBatchRequest("test-request", events);
    }

    static long nowMillis() {
        return Instant.now().toEpochMilli();
    }
}
