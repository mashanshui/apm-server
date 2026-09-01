package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.EventBatchRequest;
import com.shanshui.apmserver.domain.JankAnalysis;
import com.shanshui.apmserver.domain.StoredEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JankDatasetStatisticsTests {

    @Test
    void independentCalculationMatchesDocumentedJankEvidence() throws Exception {
        ObjectMapper mapper = CrashTestSupport.objectMapper();
        EventBatchRequest batch;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/jank-dataset.json")) {
            batch = mapper.readValue(input, EventBatchRequest.class);
        }
        var properties = CrashTestSupport.ingestProperties();
        properties.setMaxPastDays(3650);
        properties.setSupportedFpsAlgorithmVersions(List.of("fps-v1", "fps-v2"));
        properties.setSupportedSuspensionAlgorithmVersions(List.of("suspension-v1", "suspension-v2"));
        Map<String, StoredEvent> uniqueJankEvents = new LinkedHashMap<>();
        batch.events().stream().filter(event -> "jank".equals(event.eventType())).forEach(event -> {
            StoredEvent stored = JankTestSupport.storedEvent(TestAppIds.id("demo-app"), event);
            uniqueJankEvents.putIfAbsent(stored.eventId(), stored);
        });
        List<StoredEvent> janks = List.copyOf(uniqueJankEvents.values());

        assertEquals(11, batch.events().size());
        assertEquals(9, batch.events().stream().map(event -> event.eventId()).distinct().count());
        assertEquals(3, janks.size());
        assertEquals(400_000_000L, janks.get(0).jankAnalysis().estimatedDurationNs());
        assertEquals(100_000_000L, janks.get(0).jankAnalysis().uncoveredDurationNs());
        assertEquals(300_000_000L, janks.get(1).jankAnalysis().estimatedDurationNs());
        assertEquals(400_000_000L, janks.get(1).jankAnalysis().uncoveredDurationNs());
        assertEquals(200_000_000L, janks.get(2).jankAnalysis().estimatedDurationNs());
        assertEquals(100_000_000L, janks.get(2).jankAnalysis().uncoveredDurationNs());
        assertEquals(janks.get(0).crashFingerprint(), janks.get(1).crashFingerprint());
        assertNotEquals(janks.get(0).crashFingerprint(), janks.get(2).crashFingerprint());

        Map<String, com.shanshui.apmserver.domain.FrameSceneSummaryPayload> uniqueFrames = new LinkedHashMap<>();
        batch.events().stream().filter(event -> "frame_scene_summary".equals(event.eventType()))
                .forEach(event -> uniqueFrames.putIfAbsent(event.eventId(), event.frameSceneSummary()));
        assertEquals(3, uniqueFrames.size());
        assertEquals(50.0, uniqueFrames.get("frame-001").normalizedFps60());
        assertEquals(45.0, uniqueFrames.get("frame-002").normalizedFps60());
        assertEquals(30.0, uniqueFrames.get("frame-003").normalizedFps60());
        assertTrue(uniqueFrames.get("frame-001").normalizedFps60()
                >= uniqueFrames.get("frame-003").normalizedFps60());
        assertEquals(Instant.parse("2026-08-15T10:00:00Z"),
                Instant.ofEpochMilli(batch.events().get(4).occurredAt()).truncatedTo(ChronoUnit.HOURS));
        assertEquals(Instant.parse("2026-08-16T00:00:00Z"),
                Instant.ofEpochMilli(batch.events().get(5).occurredAt()).truncatedTo(ChronoUnit.HOURS));

        var suspensions = batch.events().stream()
                .filter(event -> "foreground_suspension_summary".equals(event.eventType()))
                .map(event -> event.foregroundSuspensionSummary()).toList();
        assertEquals(3, suspensions.size());
        long v1ForegroundMs = suspensions.stream().filter(value -> "suspension-v1".equals(value.algorithmVersion()))
                .mapToLong(value -> value.foregroundDurationMs()).sum();
        long v1SuspensionMs = suspensions.stream().filter(value -> "suspension-v1".equals(value.algorithmVersion()))
                .mapToLong(value -> value.suspensionDurationMs()).sum();
        assertEquals(3.0, v1SuspensionMs / 1000.0 / (v1ForegroundMs / 3_600_000.0), 0.0001);
        assertEquals(java.time.LocalDate.of(2026, 8, 15),
                Instant.ofEpochMilli(batch.events().get(6).occurredAt()).atZone(ZoneOffset.UTC).toLocalDate());
        assertEquals(java.time.LocalDate.of(2026, 8, 16),
                Instant.ofEpochMilli(batch.events().get(7).occurredAt()).atZone(ZoneOffset.UTC).toLocalDate());
    }
}
