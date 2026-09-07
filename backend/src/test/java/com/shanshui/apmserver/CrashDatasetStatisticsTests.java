package com.shanshui.apmserver;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;

import com.shanshui.apmserver.crash.api.CrashOverviewResponse;
import com.shanshui.apmserver.ingest.api.EventBatchRequest;
import com.shanshui.apmserver.ingest.api.EventEnvelope;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
import com.shanshui.apmserver.ingest.internal.application.BatchIngestionService;
import com.shanshui.apmserver.crash.internal.application.CrashQueryService;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CrashDatasetStatisticsTests {

    private final ObjectMapper objectMapper = CrashTestSupport.objectMapper();
    private InMemoryCrashRepository repository;
    private BatchIngestionService ingestion;
    private CrashQueryService query;
    private String from;
    private String to;

    @BeforeEach
    void setUp() throws Exception {
        var ingestProperties = CrashTestSupport.ingestProperties();
        repository = new InMemoryCrashRepository(new com.shanshui.apmserver.platform.api.StorageProperties());
        ingestion = CrashTestSupport.ingestion(repository, ingestProperties);
        query = new CrashQueryService(repository, CrashTestSupport.queryProperties(),
                CrashTestSupport.storageProperties(), new MicrometerTelemetryMetrics(new SimpleMeterRegistry()));
        from = "2026-08-15T09:59:00Z";
        to = "2026-08-15T11:00:00Z";
    }

    @Test
    void fixedDatasetProducesExpectedOverallVersionAndIssueStatistics() throws Exception {
        EventBatchRequest batch = fixture();
        var response = ingestion.ingest(TestAppIds.id("demo-app"), batch);

        assertEquals(12, response.accepted());
        assertEquals(0, response.rejected());
        assertEquals(1, response.duplicate());

        CrashOverviewResponse overall = query.overview(TestAppIds.id("demo-app"), from, to, CrashQueryCommand.empty());
        assertEquals(8, overall.stats().startedSessions());
        assertEquals(4, overall.stats().crashEvents());
        assertEquals(4, overall.stats().crashedSessions());
        assertEquals(4, overall.stats().affectedDevices());
        assertEquals(500.0, overall.stats().crashRatePer1000Sessions());
        assertEquals(0.5, overall.stats().crashFreeSessionRate());
        var trend = query.trend(TestAppIds.id("demo-app"), from, to, "hour", CrashQueryCommand.empty());
        assertEquals(1, trend.points().size());
        assertEquals(8, trend.points().get(0).stats().startedSessions());
        assertEquals(12, repository.findAll(TestAppIds.id("demo-app")).size());

        CrashQueryCommand version = CrashQueryCommand.empty();
        version = version.withAppVersion("3.2.0");
        assertEquals(400.0, query.overview(TestAppIds.id("demo-app"), from, to, version)
                .stats().crashRatePer1000Sessions());

        CrashQueryCommand versionTarget = CrashQueryCommand.empty();
        versionTarget = versionTarget.withAppVersion("3.3.0");
        assertEquals(3, query.overview(TestAppIds.id("demo-app"), from, to, versionTarget).stats().startedSessions());
        assertEquals(666.6666666666666,
                query.overview(TestAppIds.id("demo-app"), from, to, versionTarget).stats().crashRatePer1000Sessions());

        CrashQueryCommand dimensions = CrashQueryCommand.empty();
        dimensions = dimensions.withAppVersion("3.2.0");
        dimensions = dimensions.withChannel("official");
        dimensions = dimensions.withEnvironment("production");
        dimensions = dimensions.withOsVersion("16");
        dimensions = dimensions.withDeviceModel("Pixel-8");
        assertEquals(1, query.overview(TestAppIds.id("demo-app"), from, to, dimensions).stats().crashEvents());

        var issues = query.issues(TestAppIds.id("demo-app"), from, to, CrashQueryCommand.empty());
        assertEquals(2, issues.issues().size());
        assertEquals(3, issues.issues().get(0).eventCount());
        assertEquals(1, issues.issues().get(1).eventCount());
        CrashQueryCommand fingerprintFilter = CrashQueryCommand.empty();
        fingerprintFilter = fingerprintFilter.withFingerprint(issues.issues().get(0).fingerprint());
        assertEquals(8, query.overview(TestAppIds.id("demo-app"), from, to, fingerprintFilter)
                .stats().startedSessions());
        assertEquals(3, query.overview(TestAppIds.id("demo-app"), from, to, fingerprintFilter)
                .stats().crashEvents());
        var issueEvents = query.events(TestAppIds.id("demo-app"), issues.issues().get(0).fingerprint(), from, to,
                CrashQueryCommand.empty());
        assertEquals(3, issueEvents.events().size());
        assertNotNull(query.event(TestAppIds.id("demo-app"), "crash-202").rawCrash());
    }

    @Test
    void noStartDenominatorReturnsNullRates() {
        EventEnvelope crash = CrashTestSupport.event("only-crash", "crash", "session-only", "device-only",
                "3.2.0", CrashTestSupport.nowMillis(),
                CrashTestSupport.crash("java.lang.IllegalStateException", "boom", 1, "A"));
        ingestion.ingest(TestAppIds.id("demo-app"), CrashTestSupport.batch(java.util.List.of(crash)));

        var stats = query.overview(TestAppIds.id("demo-app"), Instant.now().minusSeconds(60).toString(),
                Instant.now().plusSeconds(60).toString(), CrashQueryCommand.empty()).stats();
        assertEquals("denominator_insufficient", stats.status());
        assertNull(stats.crashRatePer1000Sessions());
        assertNull(stats.crashFreeSessionRate());
    }

    private EventBatchRequest fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/crash-dataset.json")) {
            return objectMapper.readValue(input, EventBatchRequest.class);
        }
    }
}
