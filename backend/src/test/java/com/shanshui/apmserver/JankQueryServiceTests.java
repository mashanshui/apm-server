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

    /** 每个筛选维度、绝对时间、应用与列表种类均绑定游标。 */
    @Test void cursorBindsEveryDimensionAndRejectsNonLists() {
        var app = TestAppIds.id("demo-app");
        var first = query.issues(app, from, to, JankQueryCommand.empty().withLimit(1));
        var continuation = JankQueryCommand.empty().withCursor(first.nextCursor());
        for (var changed : java.util.List.of(continuation.withAppVersion("2"), continuation.withChannel("x"),
                continuation.withEnvironment("x"), continuation.withOsVersion("x"), continuation.withDeviceModel("x"),
                continuation.withFingerprint("x"), continuation.withScene("x"), continuation.withAlgorithmVersion("x"))) {
            invalid(() -> query.issues(app, from, to, changed));
        }
        invalid(() -> query.issues(TestAppIds.id("other"),from,to,continuation));
        invalid(() -> query.issues(app,"2026-08-15T10:00:00Z",to,continuation));
        invalid(() -> query.events(app,first.issues().getFirst().fingerprint(),from,to,continuation));
        invalid(() -> query.overview(app,from,to,continuation));
        invalid(() -> query.trend(app,from,to,"hour",continuation));
        for (String bad : java.util.List.of("old-fingerprint", "broken!", "x".repeat(2049))) {
            invalid(() -> query.issues(app,from,to,JankQueryCommand.empty().withCursor(bad)));
        }
        var last = query.issues(app,from,to,continuation).issues().getLast();
        String terminal = com.shanshui.apmserver.jank.internal.application.JankCursor.issue(
                query.filter(app,from,to,JankQueryCommand.empty()),last);
        assertEquals(0,query.issues(app,from,to,JankQueryCommand.empty().withCursor(terminal)).issues().size());
    }

    /** 缺省时间仅首查询生成，续页逐纳秒恢复，不随当前时钟漂移。 */
    @Test void restoresDefaultWindowExactly() {
        var store = org.mockito.Mockito.mock(com.shanshui.apmserver.jank.internal.port.JankAggregationRepository.class);
        var captured = new java.util.ArrayList<com.shanshui.apmserver.jank.internal.domain.JankQueryFilter>();
        org.mockito.Mockito.when(store.issues(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            var filter = (com.shanshui.apmserver.jank.internal.domain.JankQueryFilter)call.getArgument(0);
            captured.add(filter);
            var time = filter.to().minusSeconds(1);
            var percentiles = new com.shanshui.apmserver.jank.api.JankDurationPercentiles(0.0,0.0,0.0);
            return java.util.List.of(new com.shanshui.apmserver.jank.api.JankIssueSummary("a","v1","s","v1",1,1,1,time,time,percentiles,percentiles),
                    new com.shanshui.apmserver.jank.api.JankIssueSummary("b","v1","s","v1",1,1,1,time,time,percentiles,percentiles));
        });
        var defaultQuery = new JankQueryService(store,CrashTestSupport.queryProperties());
        var page = defaultQuery.issues(TestAppIds.id("demo-app"),null,null,JankQueryCommand.empty().withLimit(1));
        defaultQuery.issues(TestAppIds.id("demo-app"),null,null,JankQueryCommand.empty().withLimit(1).withCursor(page.nextCursor()));
        assertEquals(captured.getFirst().from(),captured.getLast().from());
        assertEquals(captured.getFirst().to(),captured.getLast().to());
    }

    /** 统一检查失败契约，避免只验证抛出任意异常。 */
    private void invalid(org.junit.jupiter.api.function.Executable operation) {
        assertEquals("INVALID_CURSOR",org.junit.jupiter.api.Assertions.assertThrows(
                com.shanshui.apmserver.platform.api.QueryValidationException.class,operation).getCode());
    }

    private EventBatchRequest fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/jank-dataset.json")) {
            return mapper.readValue(input, EventBatchRequest.class);
        }
    }
}
