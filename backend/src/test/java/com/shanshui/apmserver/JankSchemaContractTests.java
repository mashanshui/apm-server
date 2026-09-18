package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.ingest.api.EventBatchRequest;
import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.ingest.internal.protocol.EventSchemaValidator;
import com.shanshui.apmserver.jank.internal.application.JankMetricEventValidator;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JankSchemaContractTests {

    private final ObjectMapper mapper = CrashTestSupport.objectMapper();

    @Test
    void fixedDatasetMetricsMatchBatchContractAndJsonJankIsRejected() throws Exception {
        IngestConfigurationProperties properties = properties();
        JankMetricEventValidator validator = new JankMetricEventValidator(properties, mapper);
        EventBatchRequest batch;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/jank-dataset.json")) {
            batch = mapper.readValue(input, EventBatchRequest.class);
        }
        assertDoesNotThrow(() -> batch.events().stream()
                .filter(event -> !"jank".equals(event.eventType()))
                .forEach(event -> validator.validate(CrashTestSupport.metricCommand(event))));
        batch.events().stream().filter(event -> "jank".equals(event.eventType())).forEach(event -> {
            var response = CrashTestSupport.ingestion(new InMemoryJankEventRepository(CrashTestSupport.storageProperties()), properties)
                    .ingest(TestAppIds.id("app-a"), new EventBatchRequest("jank-contract", java.util.List.of(event)));
            assertTrue(response.errors().stream().anyMatch(error -> error.code().equals("JANK_ARTIFACT_REQUIRED")));
        });
    }

    @Test
    void unknownFieldsAreRejectedByTheJsonContract() {
        String json = "{\"schemaVersion\":1,\"eventId\":\"x\",\"eventType\":\"jank\","
                + "\"occurredAt\":1,\"sessionId\":\"s\",\"anonymousDeviceId\":\"d\","
                + "\"appVersion\":\"1\",\"versionCode\":1,\"buildId\":\"b\","
                + "\"environment\":\"prod\",\"channel\":\"c\",\"osVersion\":\"1\","
                + "\"deviceModel\":\"m\",\"unexpected\":true}";
        EventSchemaValidator schemaValidator = new EventSchemaValidator();
        assertThrows(RuntimeException.class, () -> schemaValidator.validateBatch(mapper.readTree(
                "{\"requestId\":\"r\",\"events\":[" + json + "]}")));
    }

    @Test
    void jsonJankUsesStableTransportErrorBeforeLegacyPayloadValidation() {
        IngestConfigurationProperties properties = properties();
        EventEnvelope event = new EventEnvelope(2, "x", "jank", System.currentTimeMillis(), "s", CrashTestSupport.PROCESS_ID, "d",
                "app", "1", 1, "b", "prod", "c", "1", "m", null, null, null, null,
                null, null, null);
        var response = CrashTestSupport.ingestion(new InMemoryJankEventRepository(CrashTestSupport.storageProperties()), properties)
                .ingest(TestAppIds.id("app-a"), new EventBatchRequest("jank-contract", java.util.List.of(event)));
        assertTrue(response.errors().stream().anyMatch(issue -> issue.code().equals("JANK_ARTIFACT_REQUIRED")));
    }

    private IngestConfigurationProperties properties() {
        IngestConfigurationProperties properties = CrashTestSupport.ingestProperties();
        properties.setMaxPastDays(3650);
        properties.setSupportedFpsAlgorithmVersions(java.util.List.of("fps-v1", "fps-v2"));
        properties.setSupportedSuspensionAlgorithmVersions(java.util.List.of("suspension-v1", "suspension-v2"));
        return properties;
    }
}
