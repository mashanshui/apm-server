package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankAggregationRepository;

import com.shanshui.apmserver.bootstrap.internal.config.IngestConfigurationProperties;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;

import com.shanshui.apmserver.ingest.api.EventBatchRequest;
import com.shanshui.apmserver.jank.api.JankOverviewResponse;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.bootstrap.internal.observability.MicrometerTelemetryMetrics;
import com.shanshui.apmserver.jank.internal.application.JankQueryService;
import com.shanshui.apmserver.jank.internal.domain.JankQueryCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JankQueryServiceTests {

    private final ObjectMapper mapper = CrashTestSupport.objectMapper();
    private InMemoryJankEventRepository repository;
    private JankQueryService query;
    private final String from = "2026-08-15T09:59:00Z";
    private final String to = "2026-08-16T00:02:00Z";

    @BeforeEach
    void setUp() throws Exception {
        var properties = CrashTestSupport.ingestProperties();
        properties.setMaxPastDays(3650);
        properties.setSupportedFpsAlgorithmVersions(java.util.List.of("fps-v1", "fps-v2"));
        properties.setSupportedSuspensionAlgorithmVersions(java.util.List.of("suspension-v1", "suspension-v2"));
        repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        JankTestSupport.appendFixture(TestAppIds.id("demo-app"), repository, fixture());
        query = new JankQueryService(new InMemoryJankAggregationRepository(repository), CrashTestSupport.queryProperties());
    }

    @Test
    void overviewAndIssueQueriesPreserveExactAndEstimatedEvidence() {
        JankOverviewResponse overview = query.overview(TestAppIds.id("demo-app"), from, to, JankQueryCommand.empty());
        assertEquals(3, overview.stats().jankEvents());
        assertEquals(3, overview.stats().affectedSessions());
        assertEquals(2, overview.stats().affectedDevices());
        assertEquals(3, overview.stats().groupableEvents());
        assertEquals(500.0, overview.stats().exactMessageDuration().p50Ms());
        assertEquals(700.0, overview.stats().exactMessageDuration().p90Ms());
        assertEquals(700.0, overview.stats().exactMessageDuration().p99Ms());
        assertEquals("ok", overview.stats().status());

        var issues = query.issues(TestAppIds.id("demo-app"), from, to, JankQueryCommand.empty());
        assertEquals(2, issues.issues().size());
        assertEquals(2, issues.issues().get(0).eventCount());
        assertEquals(500.0, issues.issues().get(0).exactMessageDuration().p50Ms());
        assertEquals(300.0, issues.issues().get(0).estimatedStackDuration().p50Ms());
        var issueEvents = query.events(TestAppIds.id("demo-app"), issues.issues().get(0).fingerprint(), from, to,
                JankQueryCommand.empty());
        assertEquals(2, issueEvents.events().size());
        assertEquals("ok", issueEvents.status());
        assertEquals(400.0, query.event(TestAppIds.id("demo-app"), "jank-001").analysis().estimatedDurationNs() / 1_000_000.0);
    }

    @Test
    void filtersPaginationAndNoDataHaveStableStatuses() {
        JankQueryCommand scene = JankQueryCommand.empty();
        scene = scene.withScene("order");
        assertEquals(1, query.overview(TestAppIds.id("demo-app"), from, to, scene).stats().jankEvents());

        JankQueryCommand page = JankQueryCommand.empty();
        page = page.withLimit(1);
        var issues = query.issues(TestAppIds.id("demo-app"), from, to, page);
        assertEquals(1, issues.issues().size());
        String cursor = issues.nextCursor();
        page = page.withCursor(cursor);
        assertEquals(1, query.issues(TestAppIds.id("demo-app"), from, to, page).issues().size());

        JankOverviewResponse empty = query.overview(TestAppIds.id("demo-app"), "2027-01-01T00:00:00Z",
                "2027-01-02T00:00:00Z", JankQueryCommand.empty());
        assertEquals("no_data", empty.stats().status());
        assertEquals(0, empty.stats().jankEvents());
        assertNull(empty.stats().exactMessageDuration().p50Ms());
    }

    private EventBatchRequest fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/jank-dataset.json")) {
            return mapper.readValue(input, EventBatchRequest.class);
        }
    }
}
