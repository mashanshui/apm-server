package com.shanshui.apmserver;

import com.shanshui.apmserver.config.ClickHouseProperties;
import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.config.QueryProperties;
import com.shanshui.apmserver.config.StorageProperties;
import com.shanshui.apmserver.domain.CrashPayload;
import com.shanshui.apmserver.domain.EventBatchRequest;
import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.domain.StackFrame;
import com.shanshui.apmserver.domain.ThrowableNode;
import com.shanshui.apmserver.repository.InMemoryEventRepository;
import com.shanshui.apmserver.service.CrashEventProcessor;
import com.shanshui.apmserver.service.CrashEventValidator;
import com.shanshui.apmserver.service.CrashFingerprintService;
import com.shanshui.apmserver.service.CrashIngestionService;
import com.shanshui.apmserver.service.CrashQualityMetrics;
import com.shanshui.apmserver.service.CrashSanitizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

final class CrashTestSupport {

    private CrashTestSupport() {
    }

    static IngestProperties ingestProperties() {
        IngestProperties properties = new IngestProperties();
        properties.setMaxPastDays(365);
        properties.setMaxFutureSkewMinutes(365 * 24 * 60);
        properties.setDeviceHashSalt("test-salt");
        return properties;
    }

    static ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    static CrashIngestionService ingestion(InMemoryEventRepository repository, IngestProperties properties) {
        CrashSanitizer sanitizer = new CrashSanitizer(properties);
        CrashEventValidator validator = new CrashEventValidator(properties, objectMapper());
        CrashEventProcessor processor = new CrashEventProcessor(validator, sanitizer,
                new CrashFingerprintService(), properties);
        return new CrashIngestionService(processor, repository,
                new CrashQualityMetrics(new SimpleMeterRegistry()));
    }

    static CrashPayload crash(String exceptionType, String message, int line, String className) {
        return new CrashPayload("jvm", true, List.of(new ThrowableNode(exceptionType, message,
                List.of(new StackFrame(className, "submit", "Checkout.kt", line, true),
                        new StackFrame("android.app.Activity", "performCreate", "Activity.java", 0, false)))));
    }

    static EventEnvelope event(String eventId, String eventType, String sessionId,
                               String deviceId, String version, long occurredAt, CrashPayload crash) {
        return new EventEnvelope(1, eventId, eventType, occurredAt, sessionId, deviceId,
                "demo-app", version, version.startsWith("3.2") ? 320 : 330,
                version.startsWith("3.2") ? "build-320" : "build-330", "production", "official",
                "16", "Pixel-8", "wifi", null, null, crash);
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
