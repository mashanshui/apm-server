package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;

import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.jank.api.JankSample;
import com.shanshui.apmserver.telemetry.api.StackFrame;
import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.telemetry.api.EventValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JankIngestionTests {

    /** Jank 指标入库时保留与 mapping 注册表精确匹配的 buildId。 */
    @Test
    void preservesBuildIdThatMatchesPhonePattern() {
        /** Jank 内存仓库。 */
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        /** 使用真实事件处理链构造批次接收器。 */
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        /** 构造合法帧指标事件，并替换成包含日期数字的构建标识。 */
        EventEnvelope source = frame("frame-build-id", "device");
        EventEnvelope event = new EventEnvelope(source.schemaVersion(), source.eventId(), source.eventType(),
                source.occurredAt(), source.sessionId(), source.processId(), source.anonymousDeviceId(),
                source.packageName(), source.appVersion(), source.versionCode(), "symbol-validation-2026-09-20",
                source.environment(), source.channel(), source.osVersion(), source.deviceModel(), source.networkType(),
                source.measurements(), source.attributes(), null, null, source.frameSceneSummary(), null, null);
        /** 批次入库响应。 */
        var response = ingestion.ingest(TestAppIds.id("app-build-id"), CrashTestSupport.batch(List.of(event)));

        /** 事件必须被接收，且保存的构建标识不得被电话号码规则改写。 */
        assertEquals(1, response.accepted());
        assertEquals("symbol-validation-2026-09-20", repository.findAll(TestAppIds.id("app-build-id")).get(0).buildId());
    }

    @Test
    void rejectsJsonJankAndPointsClientToArtifactEndpoint() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope event = jank("jank-1", 0L, "device-raw");

        var response = ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(event)));
        assertEquals(0, response.accepted());
        assertEquals(1, response.rejected());
        assertTrue(response.errors().stream().anyMatch(error -> error.code().equals("JANK_ARTIFACT_REQUIRED")));
        assertTrue(repository.findJankEvents(TestAppIds.id("app-a")).isEmpty());
    }

    @Test
    void repeatedJsonJankNeverCreatesAnEvent() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope event = jank("same-id", 0L, "device");
        assertEquals(1, ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(event))).rejected());
        var retry = ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(event)));
        assertEquals(0, retry.accepted());
        assertEquals(1, retry.rejected());
        assertEquals(0, repository.findJankEvents(TestAppIds.id("app-a")).size());
    }

    @Test
    void rejectsJsonJankBeforeInspectingLegacyEvidenceFields() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope invalid = jank("invalid", 300_000_000L, "device");
        var response = ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(invalid)));
        assertEquals(0, response.accepted());
        assertTrue(response.errors().stream().anyMatch(error -> error.code().equals("JANK_ARTIFACT_REQUIRED")));
    }

    @Test
    void mixedBatchAcceptsFrameAndRejectsJsonJank() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope validFrame = frame("frame-valid", "device");
        var response = ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(
                jank("legacy", 0L, "device"), validFrame)));
        assertEquals(1, response.accepted());
        assertEquals(1, response.rejected());
        assertTrue(response.errors().stream().anyMatch(error -> error.code().equals("JANK_ARTIFACT_REQUIRED")));
    }

    @Test
    void unavailableMemoryStoreRemainsRetryableForMetricEvents() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        repository.setAvailable(false);
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        assertThrows(EventStoreUnavailableException.class,
                () -> ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(frame("down", "device")))));
    }

    @Test
    void acceptsFrameAndSuspensionMetricsAndSanitizesDeviceId() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        var properties = CrashTestSupport.ingestProperties();
        properties.setSupportedFpsAlgorithmVersions(List.of("fps-v1"));
        properties.setSupportedSuspensionAlgorithmVersions(List.of("suspension-v1"));
        var ingestion = CrashTestSupport.ingestion(repository, properties);
        var response = ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(
                frame("frame-1", "device-raw"), suspension("suspension-1", "device-raw"))));
        assertEquals(2, response.accepted());
        assertTrue(repository.findAll(TestAppIds.id("app-a")).stream().anyMatch(event -> event.isFrameSceneSummary()
                && !"device-raw".equals(event.anonymousDeviceId())));
        assertTrue(repository.findAll(TestAppIds.id("app-a")).stream().anyMatch(event -> event.isForegroundSuspensionSummary()));
    }

    @Test
    void rejectsUnsupportedFrameAndSuspensionAlgorithmsAndInvalidDenominators() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope invalidFrame = frame("frame-invalid-algorithm", "device");
        invalidFrame = new EventEnvelope(invalidFrame.schemaVersion(), invalidFrame.eventId(), invalidFrame.eventType(),
                invalidFrame.occurredAt(), invalidFrame.sessionId(), invalidFrame.processId(), invalidFrame.anonymousDeviceId(), invalidFrame.packageName(),
                invalidFrame.appVersion(), invalidFrame.versionCode(), invalidFrame.buildId(), invalidFrame.environment(),
                invalidFrame.channel(), invalidFrame.osVersion(), invalidFrame.deviceModel(), invalidFrame.networkType(),
                null, null, null, null,
                new FrameSceneSummaryPayload("scene", "fps-unknown", 1000L, 30, 60.0, 30.0, null), null);
        EventEnvelope invalidSuspension = new EventEnvelope(2, "suspension-invalid", "foreground_suspension_summary",
                Instant.now().toEpochMilli(), "s", CrashTestSupport.PROCESS_ID, "d", "app", "1.0", 1, "build", "prod", "official", "16", "Pixel", "wifi",
                null, null, null, null, null,
                new ForegroundSuspensionSummaryPayload("suspension-v1", 0L, 1L, 1, 200L));
        var response = ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(invalidFrame, invalidSuspension)));
        assertEquals(0, response.accepted());
        assertTrue(response.errors().stream().anyMatch(error -> error.code().equals("UNSUPPORTED_FPS_ALGORITHM_VERSION")));
        assertTrue(response.errors().stream().anyMatch(error -> error.code().equals("INVALID_FOREGROUND_DURATION")));
    }

    @Test
    void rejectsNegativeFrameHistogramBucketCounts() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        var ingestion = CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties());
        EventEnvelope invalid = new EventEnvelope(2, "frame-invalid-bucket", "frame_scene_summary",
                Instant.now().toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, "device", "app", "1.0", 1, "build", "prod",
                "official", "16", "Pixel", "wifi", null, null, null, null,
                new FrameSceneSummaryPayload("checkout", "fps-v1", 1000L, 50, 60.0, 50.0,
                        java.util.Map.of("0-16", -1)), null);
        var response = ingestion.ingest(TestAppIds.id("app-a"), CrashTestSupport.batch(List.of(invalid)));
        assertEquals(0, response.accepted());
        assertTrue(response.errors().stream().anyMatch(error -> error.code().equals("INVALID_FRAME_BUCKET_COUNT")));
    }

    private EventEnvelope frame(String eventId, String deviceId) {
        return new EventEnvelope(2, eventId, "frame_scene_summary", Instant.now().toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, deviceId,
                "app", "1.0", 1, "build", "prod", "official", "16", "Pixel", "wifi", null, null, null, null,
                new FrameSceneSummaryPayload("checkout", "fps-v1", 1000L, 50, 60.0, 50.0,
                        java.util.Map.of("0-16", 40)), null);
    }

    private EventEnvelope suspension(String eventId, String deviceId) {
        return new EventEnvelope(2, eventId, "foreground_suspension_summary", Instant.now().toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, deviceId,
                "app", "1.0", 1, "build", "prod", "official", "16", "Pixel", "wifi", null, null, null, null, null,
                new ForegroundSuspensionSummaryPayload("suspension-v1", 3_600_000L, 2_000L, 2, 200L));
    }

    private EventEnvelope jank(String eventId, long offset, String deviceId) {
        JankPayload payload = new JankPayload("checkout", "jank-v1", 200_000_000L, 100_000_000L,
                100_000_000L,
                List.of(new JankSample(0L, "stack"), new JankSample(offset == 0L ? 100_000_000L : offset,
                        offset == 0L ? "stack" : "missing")),
                java.util.Map.of("stack", List.of(new StackFrame("com.example.Payment", "submit", "Checkout.kt", 10, true))),
                2, 2, 0);
        return new EventEnvelope(2, eventId, "jank", Instant.now().toEpochMilli(), "session", CrashTestSupport.PROCESS_ID, deviceId,
                "app", "1.0", 1, "build", "prod", "official", "16", "Pixel", "wifi", null, null,
                null, payload, null, null);
    }
}
