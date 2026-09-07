package com.shanshui.apmserver;

import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankMetricsRepository;

import com.shanshui.apmserver.platform.api.StorageProperties;

import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import com.shanshui.apmserver.jank.internal.persistence.InMemoryJankEventRepository;
import com.shanshui.apmserver.jank.internal.application.JankMetricsQueryService;
import com.shanshui.apmserver.jank.internal.domain.JankQueryCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JVM-only smoke benchmark；不把本机结果当成 ClickHouse 生产容量结论。 */
class JankMetricsPerformanceTests {

    @Test
    void fixedBatchMeasuresP95P99ForInMemoryMetricQueries() {
        InMemoryJankEventRepository repository = new InMemoryJankEventRepository(CrashTestSupport.storageProperties());
        List<JankStoredSignal> events = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 1_000; i++) {
            String device = "device-" + (i % 100);
            Instant occurredAt = now.minusSeconds(i % 86_400);
            events.add(TestJankSignals.storedEvent(TestAppIds.id("perf-app"), "app", "frame-" + i, "frame_scene_summary", occurredAt,
                    now, 1, "session-" + i, device, "1.0", 1, "build", "prod", "official", "16", "Pixel-8", "wifi",
                    Map.of(), Map.of(), null, null, null, null, null, null, null, null, null,
                    new FrameSceneSummaryPayload(i % 2 == 0 ? "home" : "detail", "fps-v1", 1_000L,
                            45 + (i % 16), 60.0, 45.0 + (i % 16), Map.of()), null));
            events.add(TestJankSignals.storedEvent(TestAppIds.id("perf-app"), "app", "susp-" + i, "foreground_suspension_summary", occurredAt,
                    now, 1, "session-" + i, device, "1.0", 1, "build", "prod", "official", "16", "Pixel-8", "wifi",
                    Map.of(), Map.of(), null, null, null, null, null, null, null, null, null,
                    null,
                    new ForegroundSuspensionSummaryPayload("suspension-v1", 60_000L, 10L, 1, 200L)));
        }
        repository.append(TestAppIds.id("perf-app"), events);
        JankMetricsQueryService query = new JankMetricsQueryService(new InMemoryJankMetricsRepository(repository), queryProperties());
        JankQueryCommand params = JankQueryCommand.empty();
        String from = now.minus(Duration.ofDays(2)).toString();
        String to = now.plusSeconds(1).toString();
        for (int i = 0; i < 5; i++) {
            query.fps(TestAppIds.id("perf-app"), from, to, params);
            query.suspensionRate(TestAppIds.id("perf-app"), from, to, params);
            query.dimensions(TestAppIds.id("perf-app"), "fps", "scene", from, to, params);
        }
        long[] samples = new long[20];
        long usedBefore = usedMemory();
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            query.fps(TestAppIds.id("perf-app"), from, to, params);
            query.suspensionRate(TestAppIds.id("perf-app"), from, to, params);
            query.dimensions(TestAppIds.id("perf-app"), "suspension_rate", "deviceModel", from, to, params);
            samples[i] = System.nanoTime() - start;
        }
        long usedAfter = usedMemory();
        java.util.Arrays.sort(samples);
        long p95 = samples[(int) Math.ceil(samples.length * .95) - 1];
        long p99 = samples[(int) Math.ceil(samples.length * .99) - 1];
        System.err.printf("JANK_METRICS_BENCH records=%d p95Ms=%.3f p99Ms=%.3f heapDeltaMb=%.3f%n",
                events.size(), p95 / 1_000_000.0, p99 / 1_000_000.0,
                Math.max(0L, usedAfter - usedBefore) / 1024.0 / 1024.0);
        assertEquals(1_000, query.fps(TestAppIds.id("perf-app"), from, to, params).metrics().get(0).totalRecords());
        assertTrue(p95 > 0 && p99 >= p95);
    }

    private QueryProperties queryProperties() {
        QueryProperties properties = CrashTestSupport.queryProperties();
        properties.setMaxRangeDays(3);
        return properties;
    }

    private long usedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
