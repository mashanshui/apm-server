package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankMetricsRepository;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;

import com.shanshui.apmserver.ingest.api.EventBatchRequest;
import com.shanshui.apmserver.jank.api.FpsMetricsResponse;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.api.MetricTrendResponse;
import com.shanshui.apmserver.jank.api.MetricDimensionsResponse;
import com.shanshui.apmserver.jank.api.SuspensionRateResponse;
import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.jank.internal.application.JankMetricsQueryService;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import com.shanshui.apmserver.ingest.internal.application.BatchIngestionService;
import com.shanshui.apmserver.jank.internal.domain.JankQueryCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JankMetricsQueryServiceTests {

    private final ObjectMapper mapper = CrashTestSupport.objectMapper();
    private InMemoryJankEventRepository repository;
    private JankMetricsQueryService query;
    private final String from = "2026-08-15T09:59:00Z";
    private final String to = "2026-08-16T00:02:00Z";

    @BeforeEach
    void setUp() throws Exception {
        var properties = CrashTestSupport.ingestProperties();
        properties.setMaxPastDays(3650);
        properties.setSupportedFpsAlgorithmVersions(java.util.List.of("fps-v1", "fps-v2"));
        properties.setSupportedSuspensionAlgorithmVersions(java.util.List.of("suspension-v1", "suspension-v2"));
        repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        BatchIngestionService ingestion = CrashTestSupport.ingestion(repository, properties);
        ingestion.ingest(TestAppIds.id("demo-app"), fixture());
        query = new JankMetricsQueryService(new InMemoryJankMetricsRepository(repository), CrashTestSupport.queryProperties());
    }

    @Test
    void fpsIsolatedByAlgorithmAndSupportsSceneAndVersionFilters() {
        FpsMetricsResponse response = query.fps(TestAppIds.id("demo-app"), from, to, JankQueryCommand.empty());
        assertEquals("ok", response.status());
        assertEquals(2, response.metrics().size());
        assertEquals("fps-v1", response.metrics().get(0).algorithmVersion());
        assertEquals(2, response.metrics().get(0).totalRecords());
        assertEquals(2, response.metrics().get(0).validRecords());
        assertEquals(40.0, response.metrics().get(0).averageFps());
        assertEquals(50.0, response.metrics().get(0).p50Fps());
        assertEquals(30.0, response.metrics().get(0).p90Fps());
        assertEquals(30.0, response.metrics().get(0).p99Fps());
        assertEquals(45.0, response.metrics().get(1).p50Fps());

        JankQueryCommand filter = JankQueryCommand.empty();
        filter = filter.withScene("checkout");
        filter = filter.withAlgorithmVersion("fps-v1");
        assertEquals(1, query.fps(TestAppIds.id("demo-app"), from, to, filter).metrics().size());
        assertEquals(30.0, query.fps(TestAppIds.id("demo-app"), from, to, filter).metrics().get(0).p99Fps());
    }

    @Test
    void suspensionMergesDeviceDayBeforeCalculatingRateAndSupportsDimensions() {
        SuspensionRateResponse response = query.suspensionRate(TestAppIds.id("demo-app"), from, to, JankQueryCommand.empty());
        assertEquals("ok", response.status());
        assertEquals(2, response.metrics().size());
        assertEquals(3.0, response.metrics().get(0).averageSecondsPerHour());
        assertEquals(2, response.metrics().get(0).totalRecords());
        assertEquals(1, response.metrics().get(0).validDeviceDayRecords());

        MetricDimensionsResponse dimensions = query.dimensions(TestAppIds.id("demo-app"), "suspension_rate", "deviceModel",
                from, to, JankQueryCommand.empty());
        assertEquals("ok", dimensions.status());
        assertEquals(2, dimensions.points().size());
        assertEquals(3.0, dimensions.points().get(0).p50SecondsPerHour());
    }

    @Test
    void emptyRangeAndUnsupportedDimensionHaveStableStatuses() {
        assertEquals("no_data", query.fps(TestAppIds.id("demo-app"), "2027-01-01T00:00:00Z",
                "2027-01-02T00:00:00Z", JankQueryCommand.empty()).status());
        assertEquals("no_data", query.suspensionRate(TestAppIds.id("demo-app"), "2027-01-01T00:00:00Z",
                "2027-01-02T00:00:00Z", JankQueryCommand.empty()).status());
        assertThrows(QueryValidationException.class, () -> query.dimensions(TestAppIds.id("demo-app"), "fps", "notAllowed",
                from, to, JankQueryCommand.empty()));
        JankQueryCommand scene = JankQueryCommand.empty().withScene("checkout");
        QueryValidationException exception = assertThrows(QueryValidationException.class,
                () -> query.suspensionRate(TestAppIds.id("demo-app"), from, to, scene));
        assertEquals("INVALID_FILTER", exception.getCode());
    }

    @Test
    void fpsAndSuspensionDimensionQueriesExposeMetricSpecificFields() {
        MetricDimensionsResponse fps = query.dimensions(TestAppIds.id("demo-app"), "fps", "scene", from, to, JankQueryCommand.empty());
        assertTrue(fps.points().stream().anyMatch(point -> "checkout".equals(point.dimensionValue())));
        assertEquals(40.0, fps.points().stream().filter(point -> "checkout".equals(point.dimensionValue()))
                .findFirst().orElseThrow().averageFps());
        MetricDimensionsResponse suspension = query.dimensions(TestAppIds.id("demo-app"), "suspension_rate", "appVersion",
                from, to, JankQueryCommand.empty());
        assertTrue(suspension.points().stream().allMatch(point -> point.averageFps() == null));
    }

    @Test
    void appliesLimitToMetricGroups() {
        JankQueryCommand params = JankQueryCommand.empty();
        params = params.withLimit(1);
        assertEquals(1, query.fps(TestAppIds.id("demo-app"), from, to, params).metrics().size());
        assertEquals(1, query.dimensions(TestAppIds.id("demo-app"), "fps", "scene", from, to, params).points().size());
    }

    @Test
    void suspensionWithoutValidForegroundDenominatorReturnsExplicitStatus() {
        InMemoryJankEventRepository empty = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        InstantHolder time = new InstantHolder();
        empty.append(TestAppIds.id("denominator-app"), java.util.List.of(TestJankSignals.storedEvent(
                TestAppIds.id("denominator-app"), "app", "invalid-suspension", "foreground_suspension_summary",
                time.value, time.value, 1, "session", "device", "1.0", 1, "build", "production", "official",
                "16", "Pixel-8", "wifi", java.util.Map.of(), java.util.Map.of(), null, null, null, null, null, null,
                null, null, null, null, new ForegroundSuspensionSummaryPayload("suspension-v1", 0L, 0L, 0, 200L))));
        JankMetricsQueryService noDenominator = new JankMetricsQueryService(new InMemoryJankMetricsRepository(empty), CrashTestSupport.queryProperties());
        assertEquals("denominator_insufficient", noDenominator.suspensionRate(TestAppIds.id("denominator-app"),
                "2026-08-15T00:00:00Z", "2026-08-16T00:00:00Z", JankQueryCommand.empty()).status());
    }

    @Test
    void fpsTrendUsesUtcBucketsDescendingPercentilesAndHalfOpenRange() {
        InMemoryJankEventRepository events = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        Instant fromInstant = Instant.parse("2026-08-15T10:00:00Z");
        List<JankStoredSignal> values = new ArrayList<>();
        double[] fps = {60.0, 50.0, 40.0, 30.0};
        for (int index = 0; index < fps.length; index++) {
            values.add(frameEvent("frame-trend-" + index, fromInstant.plusSeconds(index * 60L), "fps-v1", fps[index]));
        }
        values.add(frameEvent("frame-next-hour", fromInstant.plusSeconds(3_600), "fps-v2", 45.0));
        events.append(TestAppIds.id("trend-app"), values);
        JankMetricsQueryService service = new JankMetricsQueryService(new InMemoryJankMetricsRepository(events), CrashTestSupport.queryProperties());

        MetricTrendResponse response = service.trend(TestAppIds.id("trend-app"), "fps", "hour",
                fromInstant.toString(), fromInstant.plusSeconds(3_600).toString(), JankQueryCommand.empty());

        assertEquals("ok", response.status());
        assertEquals(1, response.points().size());
        assertEquals(fromInstant, response.points().get(0).bucketStart());
        assertEquals(fromInstant.plusSeconds(3_600), response.points().get(0).bucketEnd());
        assertEquals(4, response.points().get(0).totalRecords());
        assertEquals(4, response.points().get(0).validRecords());
        assertEquals(45.0, response.points().get(0).averageFps());
        assertEquals(50.0, response.points().get(0).p50Fps());
        assertEquals(30.0, response.points().get(0).p90Fps());
        assertEquals(30.0, response.points().get(0).p99Fps());
        assertTrue(response.points().get(0).p50Fps() >= response.points().get(0).p90Fps());
        assertTrue(response.points().get(0).p90Fps() >= response.points().get(0).p99Fps());
        assertEquals(null, response.points().get(0).averageSecondsPerHour());
    }

    @Test
    void suspensionTrendMergesSegmentsByDeviceAndUtcDay() {
        InMemoryJankEventRepository events = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        Instant firstDay = Instant.parse("2026-08-15T23:55:00Z");
        events.append(TestAppIds.id("trend-app"), List.of(
                suspensionEvent("susp-a-1", firstDay, "device-a", 1_800_000L, 1_000L),
                suspensionEvent("susp-a-2", firstDay.plusSeconds(120), "device-a", 1_800_000L, 3_000L),
                suspensionEvent("susp-b-1", firstDay.plusSeconds(180), "device-b", 3_600_000L, 1_000L),
                suspensionEvent("susp-a-next-day", Instant.parse("2026-08-16T00:01:00Z"),
                        "device-a", 3_600_000L, 3_000L)));
        JankMetricsQueryService service = new JankMetricsQueryService(new InMemoryJankMetricsRepository(events), CrashTestSupport.queryProperties());

        MetricTrendResponse response = service.trend(TestAppIds.id("trend-app"), "suspension_rate", "day",
                "2026-08-15T00:00:00Z", "2026-08-17T00:00:00Z", JankQueryCommand.empty());

        assertEquals(2, response.points().size());
        assertEquals(3, response.points().get(0).totalRecords());
        assertEquals(2, response.points().get(0).validRecords());
        assertEquals(2.5, response.points().get(0).averageSecondsPerHour());
        assertEquals(1.0, response.points().get(0).p50SecondsPerHour());
        assertEquals(4.0, response.points().get(0).p90SecondsPerHour());
        assertEquals(1, response.points().get(1).validRecords());
        assertEquals(3.0, response.points().get(1).averageSecondsPerHour());
        assertEquals(null, response.points().get(0).averageFps());
    }

    @Test
    void trendRejectsInvalidCombinationsAndAppliesPointLimit() {
        QueryValidationException interval = assertThrows(QueryValidationException.class,
                () -> query.trend(TestAppIds.id("demo-app"), "suspension_rate", "hour", from, to, JankQueryCommand.empty()));
        assertEquals("INVALID_INTERVAL", interval.getCode());
        assertEquals("INVALID_METRIC", assertThrows(QueryValidationException.class,
                () -> query.trend(TestAppIds.id("demo-app"), "cpu", "day", from, to, JankQueryCommand.empty())).getCode());
        JankQueryCommand fingerprint = JankQueryCommand.empty().withFingerprint("not-supported");
        assertEquals("INVALID_FILTER", assertThrows(QueryValidationException.class,
                () -> query.trend(TestAppIds.id("demo-app"), "fps", "hour", from, to, fingerprint)).getCode());
        JankQueryCommand limit = JankQueryCommand.empty();
        limit = limit.withLimit(1);
        assertEquals(1, query.trend(TestAppIds.id("demo-app"), "fps", "day", from, to, limit).points().size());
    }

    @Test
    void fixedDatasetProducesHourlyDailyAndAlgorithmIsolatedTrendPoints() {
        MetricTrendResponse hourly = query.trend(TestAppIds.id("demo-app"), "fps", "hour", from, to, JankQueryCommand.empty());
        assertEquals(2, hourly.points().size());
        assertEquals("fps-v1", hourly.points().get(0).algorithmVersion());
        assertEquals(Instant.parse("2026-08-15T10:00:00Z"), hourly.points().get(0).bucketStart());
        assertEquals(2, hourly.points().get(0).totalRecords());
        assertEquals(40.0, hourly.points().get(0).averageFps());
        assertEquals(50.0, hourly.points().get(0).p50Fps());
        assertEquals(30.0, hourly.points().get(0).p90Fps());
        assertEquals("fps-v2", hourly.points().get(1).algorithmVersion());
        assertEquals(Instant.parse("2026-08-16T00:00:00Z"), hourly.points().get(1).bucketStart());

        MetricTrendResponse daily = query.trend(TestAppIds.id("demo-app"), "fps", "day", from, to, JankQueryCommand.empty());
        assertEquals(2, daily.points().size());
        assertEquals(Instant.parse("2026-08-15T00:00:00Z"), daily.points().get(0).bucketStart());
        assertEquals(Instant.parse("2026-08-16T00:00:00Z"), daily.points().get(1).bucketStart());

        MetricTrendResponse suspension = query.trend(TestAppIds.id("demo-app"), "suspension_rate", "day",
                from, to, JankQueryCommand.empty());
        assertEquals(2, suspension.points().size());
        assertEquals("suspension-v1", suspension.points().get(0).algorithmVersion());
        assertEquals(2, suspension.points().get(0).totalRecords());
        assertEquals(1, suspension.points().get(0).validRecords());
        assertEquals(3.0, suspension.points().get(0).averageSecondsPerHour());
        assertEquals("suspension-v2", suspension.points().get(1).algorithmVersion());
    }

    @Test
    void trendExposesNoDataAndNoValidDataWithoutFakeZeros() {
        assertEquals("no_data", query.trend(TestAppIds.id("demo-app"), "fps", "hour",
                "2027-01-01T00:00:00Z", "2027-01-02T00:00:00Z", JankQueryCommand.empty()).status());
        InMemoryJankEventRepository invalid = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        invalid.append(TestAppIds.id("invalid-app"), List.of(TestJankSignals.storedEvent(
                TestAppIds.id("invalid-app"), "app", "invalid-fps", "frame_scene_summary",
                Instant.parse("2026-08-15T12:00:00Z"), Instant.parse("2026-08-15T12:00:00Z"),
                1, "session", "device", "1.0", 1, "build", "production", "official",
                "16", "Pixel-8", "wifi", Map.of(), Map.of(), null, null, null, null,
                null, null, null, null, null,
                new FrameSceneSummaryPayload("home", "fps-v1", 0L, 0, 60.0, 0.0, Map.of()), null)));
        MetricTrendResponse response = new JankMetricsQueryService(new InMemoryJankMetricsRepository(invalid), CrashTestSupport.queryProperties())
                .trend(TestAppIds.id("invalid-app"), "fps", "hour", "2026-08-15T00:00:00Z",
                        "2026-08-16T00:00:00Z", JankQueryCommand.empty());
        assertEquals("no_valid_data", response.status());
        assertEquals(null, response.points().get(0).averageFps());
        assertEquals(0, response.points().get(0).validRecords());
    }

    private JankStoredSignal frameEvent(String eventId, Instant occurredAt, String algorithm, double fps) {
        return TestJankSignals.storedEvent(TestAppIds.id("trend-app"), "app", eventId, "frame_scene_summary", occurredAt, occurredAt,
                1, "session", "device", "1.0", 1, "build", "production", "official", "16", "Pixel-8",
                "wifi", Map.of(), Map.of(), null, null, null, null, null, null, null, null, null,
                new FrameSceneSummaryPayload("home", algorithm, 1_000L, 60, 60.0, fps, Map.of()), null);
    }

    private JankStoredSignal suspensionEvent(String eventId, Instant occurredAt, String device,
                                        long foregroundMs, long suspensionMs) {
        return TestJankSignals.storedEvent(TestAppIds.id("trend-app"), "app", eventId, "foreground_suspension_summary",
                occurredAt, occurredAt, 1, "session", device, "1.0", 1, "build", "production", "official",
                "16", "Pixel-8", "wifi", Map.of(), Map.of(), null, null, null, null, null, null, null,
                null, null, null,
                new ForegroundSuspensionSummaryPayload("suspension-v1", foregroundMs, suspensionMs, 1, 200L));
    }

    private static final class InstantHolder {
        private final java.time.Instant value = java.time.Instant.parse("2026-08-15T12:00:00Z");
    }

    private EventBatchRequest fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/jank-dataset.json")) {
            return mapper.readValue(input, EventBatchRequest.class);
        }
    }
}
