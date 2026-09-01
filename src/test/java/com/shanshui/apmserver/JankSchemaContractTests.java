package com.shanshui.apmserver;

import com.shanshui.apmserver.config.IngestProperties;
import com.shanshui.apmserver.domain.EventBatchRequest;
import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.service.CrashEventValidator;
import com.shanshui.apmserver.service.EventSchemaValidator;
import com.shanshui.apmserver.service.EventValidationException;
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
        IngestProperties properties = properties();
        CrashEventValidator validator = new CrashEventValidator(properties, mapper);
        EventBatchRequest batch;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/jank-dataset.json")) {
            batch = mapper.readValue(input, EventBatchRequest.class);
        }
        assertDoesNotThrow(() -> batch.events().stream()
                .filter(event -> !"jank".equals(event.eventType()))
                .forEach(validator::validate));
        batch.events().stream().filter(event -> "jank".equals(event.eventType())).forEach(event -> {
            EventValidationException exception = assertThrows(EventValidationException.class,
                    () -> validator.validate(event));
            assertTrue(exception.getIssues().stream()
                    .anyMatch(issue -> issue.code().equals("JANK_ARTIFACT_REQUIRED")));
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
        IngestProperties properties = properties();
        CrashEventValidator validator = new CrashEventValidator(properties, mapper);
        EventEnvelope event = new EventEnvelope(2, "x", "jank", System.currentTimeMillis(), "s", "d",
                "app", "1", 1, "b", "prod", "c", "1", "m", null, null, null, null,
                null, null, null);
        EventValidationException exception = assertThrows(EventValidationException.class, () -> validator.validate(event));
        assertTrue(exception.getIssues().stream().anyMatch(issue -> issue.code().equals("JANK_ARTIFACT_REQUIRED")));
    }

    private IngestProperties properties() {
        IngestProperties properties = CrashTestSupport.ingestProperties();
        properties.setMaxPastDays(3650);
        properties.setSupportedFpsAlgorithmVersions(java.util.List.of("fps-v1", "fps-v2"));
        properties.setSupportedSuspensionAlgorithmVersions(java.util.List.of("suspension-v1", "suspension-v2"));
        return properties;
    }
}
