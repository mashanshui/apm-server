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
import static org.junit.jupiter.api.Assertions.assertThrows;

class CrashDatasetStatisticsTests {

    private final ObjectMapper objectMapper = CrashTestSupport.objectMapper();
    private InMemoryCrashRepository repository;
    /** 查询和后台回填共用当前数据集。 */
    private com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashStateStore states;
    private BatchIngestionService ingestion;
    private CrashQueryService query;
    private String from;
    private String to;

    /** JVM 与 Native 共用启动分母，同会话多次崩溃和重复上报只按既有口径统计。 */
    @Test
    void mixedDatasetPreservesSessionDenominatorAndNativeIssue() throws Exception {
        var app = TestAppIds.id("demo-app");
        ingestion.ingest(app, fixture());
        EventBatchRequest nativeBatch;
        try (InputStream input = getClass().getResourceAsStream("/fixtures/native-crash-dataset.json")) {
            nativeBatch = objectMapper.readValue(input, EventBatchRequest.class);
        }
        var accepted = ingestion.ingest(app, nativeBatch);
        assertEquals(2, accepted.accepted());
        assertEquals(1, accepted.duplicate());
        var stats = query.overview(app, from, to, CrashQueryCommand.empty()).stats();
        assertEquals(8, stats.startedSessions());
        assertEquals(6, stats.crashEvents());
        assertEquals(5, stats.crashedSessions());
        assertEquals(5, stats.affectedDevices());
        assertEquals(625.0, stats.crashRatePer1000Sessions());
        assertEquals(0.375, stats.crashFreeSessionRate());
        assertEquals(6, query.trend(app, from, to, "hour", CrashQueryCommand.empty()).points().getFirst().stats().crashEvents());
        var issues = query.issues(app, from, to, CrashQueryCommand.empty()).issues();
        assertEquals(4, issues.size());
        var nativeIssue = issues.stream().filter(item -> "native-tmp-v1".equals(item.fingerprintVersion())).findFirst().orElseThrow();
        assertEquals(2, nativeIssue.eventCount());
        assertEquals("build-320", nativeIssue.buildId());
        var events = query.events(app, nativeIssue.fingerprint(), from, to, CrashQueryCommand.empty()).events();
        assertEquals(java.util.List.of("native-302", "native-301"), events.stream().map(item -> item.eventId()).toList());
        assertEquals(Instant.ofEpochMilli(1786788960000L), events.getFirst().occurredAt());
        var noStart = query.overview(app, "2026-08-15T10:14:00Z", "2026-08-15T10:17:00Z", CrashQueryCommand.empty()).stats();
        assertEquals("denominator_insufficient", noStart.status());
        assertEquals(2, noStart.crashEvents());
        assertNull(noStart.crashRatePer1000Sessions());
    }

    @BeforeEach
    void setUp() throws Exception {
        var ingestProperties = CrashTestSupport.ingestProperties();
        states = new com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashStateStore(CrashTestSupport.storageProperties(), objectMapper);
        repository = new InMemoryCrashRepository(CrashTestSupport.storageProperties(), states);
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
        assertEquals(3, issues.issues().size());
        assertEquals(2, issues.issues().get(0).eventCount());
        assertEquals(1, issues.issues().get(1).eventCount());
        CrashQueryCommand fingerprintFilter = CrashQueryCommand.empty();
        fingerprintFilter = fingerprintFilter.withFingerprint(issues.issues().get(0).fingerprint());
        assertEquals(8, query.overview(TestAppIds.id("demo-app"), from, to, fingerprintFilter)
                .stats().startedSessions());
        assertEquals(2, query.overview(TestAppIds.id("demo-app"), from, to, fingerprintFilter)
                .stats().crashEvents());
        var issueEvents = query.events(TestAppIds.id("demo-app"), issues.issues().get(0).fingerprint(), from, to,
                CrashQueryCommand.empty());
        assertEquals(2, issueEvents.events().size());
        assertNotNull(query.event(TestAppIds.id("demo-app"), "crash-202").stack());
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

    @Test
    void issueAndEventCursorsBindQueryAndPreserveOrder() throws Exception {
        ingestion.ingest(TestAppIds.id("demo-app"), fixture());
        var appId = TestAppIds.id("demo-app");
        CrashQueryCommand page = CrashQueryCommand.empty().withLimit(1);
        var first = query.issues(appId, from, to, page);
        var second = query.issues(appId, from, to, page.withCursor(first.nextCursor()));
        assertEquals(1, first.issues().size());
        assertEquals(1, second.issues().size());
        var third = query.issues(appId, from, to, page.withCursor(second.nextCursor()));
        assertEquals(1, third.issues().size());
        assertNull(third.nextCursor());
        assertEquals("INVALID_CURSOR", assertThrows(com.shanshui.apmserver.platform.api.QueryValidationException.class,
                () -> query.issues(appId, from, to, page.withCursor("legacy-fingerprint"))).getCode());
        assertEquals("INVALID_CURSOR", assertThrows(com.shanshui.apmserver.platform.api.QueryValidationException.class,
                () -> query.issues(appId, from, to, page.withAppVersion("other")
                        .withCursor(first.nextCursor()))).getCode());
        var eventFirst = query.events(appId, first.issues().getFirst().fingerprint(), from, to, page);
        var eventSecond = query.events(appId, first.issues().getFirst().fingerprint(), from, to,
                page.withCursor(eventFirst.nextCursor()));
        assertEquals(1, eventFirst.events().size());
        assertEquals(1, eventSecond.events().size());
        assertEquals("INVALID_CURSOR", assertThrows(com.shanshui.apmserver.platform.api.QueryValidationException.class,
                () -> query.events(appId, second.issues().getFirst().fingerprint(), from, to,
                        page.withCursor(eventFirst.nextCursor()))).getCode());
    }

    /** 固定集由三个临时问题转为两个正式问题，事实统计和原日期不变化。 */
    @Test
    void resolvesAcrossBuildsWithoutChangingFixedDatasetTotals() throws Exception {
        var app = TestAppIds.id("demo-app");
        ingestion.ingest(app, fixture());
        var totals = query.overview(app, from, to, CrashQueryCommand.empty()).stats();
        var originalTrend = query.trend(app, from, to, "day", CrashQueryCommand.empty()).points();
        var temporary = query.issues(app, from, to, CrashQueryCommand.empty()).issues();
        assertEquals(3, temporary.size());
        assertEquals(2, temporary.stream().map(com.shanshui.apmserver.crash.api.CrashIssueSummary::buildId).distinct().count());
        long version = 0;
        for (var fact : states.facts(app)) {
            if (!"crash".equals(fact.metadata().eventType())) continue;
            var old = states.current(app, fact.metadata().eventId()).orElseThrow();
            String stack = old.exceptionType() + "\n\tat com.example.app.Checkout.submit(Checkout.java:42)";
            var normalized = new com.shanshui.apmserver.symbol.api.SymbolicationResult(stack,
                    com.shanshui.apmserver.symbol.api.SymbolicationResult.Status.SYMBOLICATED, null).fingerprintInput();
            states.publish(new com.shanshui.apmserver.crash.internal.domain.CrashState(app, old.eventId(), old.buildId(), old.eventTime(), ++version,
                    "symbolicated", null, "retrace_text", stack, old.exceptionType(),
                    new com.shanshui.apmserver.crash.internal.application.CrashFingerprintService().resolved(app, fact.metadata().packageName(), normalized),
                    "resolved-v1", "resolved", java.util.UUID.randomUUID(), 1, "a".repeat(64), Instant.now(), null));
        }
        var resolved = query.issues(app, from, to, CrashQueryCommand.empty()).issues();
        assertEquals(2, resolved.size());
        assertEquals(3, resolved.getFirst().eventCount());
        assertEquals("resolved", resolved.getFirst().groupingKind());
        assertNull(resolved.getFirst().buildId());
        assertEquals(totals, query.overview(app, from, to, CrashQueryCommand.empty()).stats());
        assertEquals(originalTrend, query.trend(app, from, to, "day", CrashQueryCommand.empty()).points());
        for (var old : temporary) assertEquals(0, query.events(app, old.fingerprint(), from, to, CrashQueryCommand.empty()).events().size());
    }

    private EventBatchRequest fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/crash-dataset.json")) {
            return objectMapper.readValue(input, EventBatchRequest.class);
        }
    }
}
