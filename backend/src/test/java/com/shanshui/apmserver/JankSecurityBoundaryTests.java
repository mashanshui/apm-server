package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.jank.api.JankSample;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.jank.internal.application.JankSanitizer;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.platform.api.LimitedInputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JankSecurityBoundaryTests {

    @Test
    void validatorRejectsLegacyJsonJankBeforeInspectingEvidence() {
        IngestConfigurationProperties properties = CrashTestSupport.ingestProperties();
        properties.setMaxSceneLength(5);
        properties.setMaxJankStackDepth(1);
        properties.setMaxJankDetailBytes(100);
        EventEnvelope event = new EventEnvelope(1, "limit", "jank", Instant.now().toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, "device",
                "app", "1.0", 1, "build", "prod", "official", "16", "Pixel", "wifi", null, null, null,
                new JankPayload("too-long-scene", "jank-v1", 200_000_000L, 100_000_000L, 100_000_000L,
                        List.of(new JankSample(0L, "stack")),
                        Map.of("stack", List.of(
                                new StackFrame("com.example.App", "run", "a", 1, true),
                                new StackFrame("com.example.App", "next", "a", 2, true))),
                        1, 1, 0), null, null);
        var response = CrashTestSupport.ingestion(new InMemoryJankEventRepository(CrashTestSupport.storageProperties()), properties)
                .ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(event)));
        assertTrue(response.errors().stream().anyMatch(issue -> issue.code().equals("JANK_ARTIFACT_REQUIRED")));
    }

    @Test
    void sanitizerRedactsIllegalDynamicValuesAndHashesDeviceIdentifiers() {
        IngestConfigurationProperties properties = CrashTestSupport.ingestProperties();
        JankSanitizer sanitizer = new JankSanitizer(properties);
        String value = "C:\\Users\\alice\\a.txt https://example.test/x?token=1 alice@example.test "
                + "+86 138 0013 8000 550e8400-e29b-41d4-a716-446655440000\u0000";
        String sanitized = sanitizer.sanitizeText(value, 4096);
        assertTrue(sanitized.contains("[path]"));
        assertTrue(sanitized.contains("[url]"));
        assertTrue(sanitized.contains("[email]"));
        assertTrue(sanitized.contains("[phone]"));
        assertTrue(sanitized.contains("[id]"));
        assertNotEquals("device-raw", sanitizer.hashDeviceId("device-raw"));
    }

    @Test
    void limitedInputStreamProtectsDecompressedBudget() throws Exception {
        LimitedInputStream input = new LimitedInputStream(new ByteArrayInputStream(new byte[9]), 8);
        assertThrows(RuntimeException.class, () -> input.readAllBytes());
    }
}
