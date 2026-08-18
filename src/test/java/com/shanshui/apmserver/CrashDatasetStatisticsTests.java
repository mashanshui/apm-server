package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.CrashOverviewResponse;
import com.shanshui.apmserver.domain.EventBatchRequest;
import com.shanshui.apmserver.domain.EventEnvelope;
import com.shanshui.apmserver.repository.InMemoryEventRepository;
import com.shanshui.apmserver.service.CrashIngestionService;
import com.shanshui.apmserver.service.CrashQueryService;
import com.shanshui.apmserver.service.CrashQualityMetrics;
import com.shanshui.apmserver.web.QueryParams;
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
    private InMemoryEventRepository repository;
    private CrashIngestionService ingestion;
    private CrashQueryService query;
    private String from;
    private String to;

    @BeforeEach
    void setUp() throws Exception {
        var ingestProperties = CrashTestSupport.ingestProperties();
        repository = new InMemoryEventRepository(new com.shanshui.apmserver.config.StorageProperties());
        ingestion = CrashTestSupport.ingestion(repository, ingestProperties);
        query = new CrashQueryService(repository, CrashTestSupport.queryProperties(),
                CrashTestSupport.storageProperties(), new CrashQualityMetrics(new SimpleMeterRegistry()));
        from = "2026-08-15T09:59:00Z";
        to = "2026-08-15T11:00:00Z";
    }

    @Test
    void fixedDatasetProducesExpectedOverallVersionAndIssueStatistics() throws Exception {
        EventBatchRequest batch = fixture();
        var response = ingestion.ingest("demo-project", batch);

        assertEquals(12, response.accepted());
        assertEquals(0, response.rejected());
        assertEquals(1, response.duplicate());

        CrashOverviewResponse overall = query.overview("demo-project", from, to, new QueryParams());
        assertEquals(8, overall.stats().startedSessions());
        assertEquals(4, overall.stats().crashEvents());
        assertEquals(4, overall.stats().crashedSessions());
        assertEquals(4, overall.stats().affectedDevices());
        assertEquals(500.0, overall.stats().crashRatePer1000Sessions());
        assertEquals(0.5, overall.stats().crashFreeSessionRate());
        var trend = query.trend("demo-project", from, to, "hour", new QueryParams());
        assertEquals(1, trend.points().size());
        assertEquals(8, trend.points().get(0).stats().startedSessions());
        assertEquals(12, repository.findAll("demo-project").size());

        QueryParams version = new QueryParams();
        version.setAppVersion("3.2.0");
        assertEquals(400.0, query.overview("demo-project", from, to, version)
                .stats().crashRatePer1000Sessions());

        QueryParams versionTarget = new QueryParams();
        versionTarget.setAppVersion("3.3.0");
        assertEquals(3, query.overview("demo-project", from, to, versionTarget).stats().startedSessions());
        assertEquals(666.6666666666666,
                query.overview("demo-project", from, to, versionTarget).stats().crashRatePer1000Sessions());

        QueryParams dimensions = new QueryParams();
        dimensions.setAppVersion("3.2.0");
        dimensions.setChannel("official");
        dimensions.setEnvironment("production");
        dimensions.setOsVersion("16");
        dimensions.setDeviceModel("Pixel-8");
        assertEquals(1, query.overview("demo-project", from, to, dimensions).stats().crashEvents());

        var issues = query.issues("demo-project", from, to, new QueryParams());
        assertEquals(2, issues.issues().size());
        assertEquals(3, issues.issues().get(0).eventCount());
        assertEquals(1, issues.issues().get(1).eventCount());
        QueryParams fingerprintFilter = new QueryParams();
        fingerprintFilter.setFingerprint(issues.issues().get(0).fingerprint());
        assertEquals(8, query.overview("demo-project", from, to, fingerprintFilter)
                .stats().startedSessions());
        assertEquals(3, query.overview("demo-project", from, to, fingerprintFilter)
                .stats().crashEvents());
        var issueEvents = query.events("demo-project", issues.issues().get(0).fingerprint(), from, to,
                new QueryParams());
        assertEquals(3, issueEvents.events().size());
        assertNotNull(query.event("demo-project", "crash-202").rawCrash());
    }

    @Test
    void noStartDenominatorReturnsNullRates() {
        EventEnvelope crash = CrashTestSupport.event("only-crash", "crash", "session-only", "device-only",
                "3.2.0", CrashTestSupport.nowMillis(),
                CrashTestSupport.crash("java.lang.IllegalStateException", "boom", 1, "A"));
        ingestion.ingest("demo-project", CrashTestSupport.batch(java.util.List.of(crash)));

        var stats = query.overview("demo-project", Instant.now().minusSeconds(60).toString(),
                Instant.now().plusSeconds(60).toString(), new QueryParams()).stats();
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
