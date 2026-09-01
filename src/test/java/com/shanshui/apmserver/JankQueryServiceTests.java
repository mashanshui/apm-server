package com.shanshui.apmserver;

import com.shanshui.apmserver.domain.EventBatchRequest;
import com.shanshui.apmserver.domain.JankOverviewResponse;
import com.shanshui.apmserver.repository.InMemoryEventRepository;
import com.shanshui.apmserver.service.CrashQualityMetrics;
import com.shanshui.apmserver.service.JankQueryService;
import com.shanshui.apmserver.web.QueryParams;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JankQueryServiceTests {

    private final ObjectMapper mapper = CrashTestSupport.objectMapper();
    private InMemoryEventRepository repository;
    private JankQueryService query;
    private final String from = "2026-08-15T09:59:00Z";
    private final String to = "2026-08-16T00:02:00Z";

    @BeforeEach
    void setUp() throws Exception {
        var properties = CrashTestSupport.ingestProperties();
        properties.setMaxPastDays(3650);
        properties.setSupportedFpsAlgorithmVersions(java.util.List.of("fps-v1", "fps-v2"));
        properties.setSupportedSuspensionAlgorithmVersions(java.util.List.of("suspension-v1", "suspension-v2"));
        repository = new InMemoryEventRepository(CrashTestSupport.storageProperties());
        JankTestSupport.appendFixture(TestAppIds.id("demo-app"), repository, fixture());
        query = new JankQueryService(repository, CrashTestSupport.queryProperties(),
                CrashTestSupport.storageProperties(), new CrashQualityMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void overviewAndIssueQueriesPreserveExactAndEstimatedEvidence() {
        JankOverviewResponse overview = query.overview(TestAppIds.id("demo-app"), from, to, new QueryParams());
        assertEquals(3, overview.stats().jankEvents());
        assertEquals(3, overview.stats().affectedSessions());
        assertEquals(2, overview.stats().affectedDevices());
        assertEquals(3, overview.stats().groupableEvents());
        assertEquals(500.0, overview.stats().exactMessageDuration().p50Ms());
        assertEquals(700.0, overview.stats().exactMessageDuration().p90Ms());
        assertEquals(700.0, overview.stats().exactMessageDuration().p99Ms());
        assertEquals("ok", overview.stats().status());

        var issues = query.issues(TestAppIds.id("demo-app"), from, to, new QueryParams());
        assertEquals(2, issues.issues().size());
        assertEquals(2, issues.issues().get(0).eventCount());
        assertEquals(500.0, issues.issues().get(0).exactMessageDuration().p50Ms());
        assertEquals(300.0, issues.issues().get(0).estimatedStackDuration().p50Ms());
        var issueEvents = query.events(TestAppIds.id("demo-app"), issues.issues().get(0).fingerprint(), from, to,
                new QueryParams());
        assertEquals(2, issueEvents.events().size());
        assertEquals("ok", issueEvents.status());
        assertEquals(400.0, query.event(TestAppIds.id("demo-app"), "jank-001").analysis().estimatedDurationNs() / 1_000_000.0);
    }

    @Test
    void filtersPaginationAndNoDataHaveStableStatuses() {
        QueryParams scene = new QueryParams();
        scene.setScene("order");
        assertEquals(1, query.overview(TestAppIds.id("demo-app"), from, to, scene).stats().jankEvents());

        QueryParams page = new QueryParams();
        page.setLimit(1);
        var issues = query.issues(TestAppIds.id("demo-app"), from, to, page);
        assertEquals(1, issues.issues().size());
        String cursor = issues.nextCursor();
        page.setCursor(cursor);
        assertEquals(1, query.issues(TestAppIds.id("demo-app"), from, to, page).issues().size());

        JankOverviewResponse empty = query.overview(TestAppIds.id("demo-app"), "2027-01-01T00:00:00Z",
                "2027-01-02T00:00:00Z", new QueryParams());
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
