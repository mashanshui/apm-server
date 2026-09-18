package com.shanshui.apmserver;

import com.shanshui.apmserver.crash.internal.domain.AppStartEvent;
import com.shanshui.apmserver.crash.internal.domain.CrashEvent;
import com.shanshui.apmserver.crash.internal.persistence.InMemoryCrashRepository;
import com.shanshui.apmserver.jank.internal.domain.ForegroundSuspensionSummary;
import com.shanshui.apmserver.jank.internal.domain.FrameSceneSummary;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证领域专属存储模型不丢失公共字段和信号载荷。 */
class SignalDomainMappingTests {

    @Test
    void mapsCrashAndAppStartWithoutLosingMetadataOrPayload() {
        InMemoryCrashRepository repository = new InMemoryCrashRepository(CrashTestSupport.storageProperties());
        CrashTestSupport.ingestion(repository, CrashTestSupport.ingestProperties()).ingest(
                TestAppIds.id("app-a"), CrashTestSupport.batch(java.util.List.of(
                        CrashTestSupport.event("crash-map", "crash", "session", "device", "3.2.0",
                                CrashTestSupport.nowMillis(), CrashTestSupport.crash("E", "boom", 1, "A")),
                        CrashTestSupport.event("start-map", "app_start", "session", "device", "3.2.0",
                                CrashTestSupport.nowMillis(), null))));

        var crash = (CrashEvent) repository.findByEventId(TestAppIds.id("app-a"), "crash-map").orElseThrow();
        var start = (AppStartEvent) repository.findByEventId(TestAppIds.id("app-a"), "start-map").orElseThrow();
        assertThat(crash.metadata().eventId()).isEqualTo("crash-map");
        assertThat(crash.processId()).isEqualTo(CrashTestSupport.PROCESS_ID);
        assertThat(crash.payload().throwableChain()).hasSize(1);
        assertThat(start.metadata().eventType()).isEqualTo("app_start");
        assertThat(start.processId()).isEqualTo(CrashTestSupport.PROCESS_ID);
    }

    @Test
    void mapsJankAndMetricSummariesWithoutLosingDedicatedPayloads() throws Exception {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        try (InputStream input = getClass().getResourceAsStream("/fixtures/jank-dataset.json")) {
            var batch = CrashTestSupport.objectMapper().readValue(input,
                    com.shanshui.apmserver.ingest.api.EventBatchRequest.class);
            JankTestSupport.appendFixture(TestAppIds.id("app-a"), repository, batch);
        }
        var events = repository.findAll(TestAppIds.id("app-a"));
        var jank = (JankEvent) events.stream().filter(JankEvent.class::isInstance).findFirst().orElseThrow();
        var frame = (FrameSceneSummary) events.stream().filter(FrameSceneSummary.class::isInstance)
                .findFirst().orElseThrow();
        var suspension = (ForegroundSuspensionSummary) events.stream()
                .filter(ForegroundSuspensionSummary.class::isInstance).findFirst().orElseThrow();
        assertThat(jank.payload()).isNotNull();
        assertThat(jank.processId()).isEqualTo(CrashTestSupport.PROCESS_ID);
        assertThat(frame.payload().algorithmVersion()).isNotBlank();
        assertThat(frame.processId()).isEqualTo(CrashTestSupport.PROCESS_ID);
        assertThat(suspension.payload().foregroundDurationMs()).isPositive();
        assertThat(suspension.processId()).isEqualTo(CrashTestSupport.PROCESS_ID);
    }
}
