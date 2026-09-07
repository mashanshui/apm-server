package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.internal.port.JankMetricsRepository;
import com.shanshui.apmserver.jank.internal.port.JankEventRepository;

import com.shanshui.apmserver.jank.api.FpsMetricAggregate;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.jank.api.MetricDimensionAggregate;
import com.shanshui.apmserver.jank.internal.domain.MetricQueryFilter;
import com.shanshui.apmserver.jank.api.MetricTrendAggregate;
import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import com.shanshui.apmserver.jank.api.SuspensionMetricAggregate;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 固定数据集和本地开发使用的内存指标聚合实现。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryJankMetricsRepository implements JankMetricsRepository {

    private final JankEventRepository eventRepository;

    public InMemoryJankMetricsRepository(JankEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public List<FpsMetricAggregate> queryFps(MetricQueryFilter filter) {
        long deadline = deadline(filter);
        Map<String, List<Double>> values = new LinkedHashMap<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, Long> valid = new LinkedHashMap<>();
        for (JankStoredSignal event : matchingEvents(filter, false, deadline)) {
            checkDeadline(deadline);
            FrameSceneSummaryPayload payload = event.frameSceneSummary();
            if (payload == null || !matches(filter.scene(), payload.scene())
                    || !matches(filter.algorithmVersion(), payload.algorithmVersion())) {
                continue;
            }
            String version = payload.algorithmVersion();
            totals.merge(version, 1L, Long::sum);
            Double fps = validFps(payload);
            if (fps != null) {
                valid.merge(version, 1L, Long::sum);
                values.computeIfAbsent(version, ignored -> new ArrayList<>()).add(fps);
            }
        }
        List<FpsMetricAggregate> result = totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(String::compareTo)))
                .map(entry -> fpsAggregate(entry.getKey(), entry.getValue(), valid.getOrDefault(entry.getKey(), 0L),
                        values.getOrDefault(entry.getKey(), List.of())))
                .limit(filter.limit())
                .toList();
        checkDeadline(deadline);
        return result;
    }

    @Override
    public List<SuspensionMetricAggregate> querySuspension(MetricQueryFilter filter) {
        long deadline = deadline(filter);
        Map<SuspensionDayKey, SuspensionDay> days = suspensionDays(filter, null, deadline);
        Map<String, List<Double>> rates = new LinkedHashMap<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        Map<String, Long> valid = new LinkedHashMap<>();
        for (SuspensionDay day : days.values()) {
            checkDeadline(deadline);
            totals.merge(day.algorithmVersion(), day.segmentRecords(), Long::sum);
            Double rate = day.rate();
            if (rate != null) {
                valid.merge(day.algorithmVersion(), 1L, Long::sum);
                rates.computeIfAbsent(day.algorithmVersion(), ignored -> new ArrayList<>()).add(rate);
            }
        }
        List<SuspensionMetricAggregate> result = totals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(String::compareTo)))
                .map(entry -> suspensionAggregate(entry.getKey(), entry.getValue(),
                        valid.getOrDefault(entry.getKey(), 0L), rates.getOrDefault(entry.getKey(), List.of())))
                .limit(filter.limit())
                .toList();
        checkDeadline(deadline);
        return result;
    }

    @Override
    public List<MetricTrendAggregate> queryFpsTrend(MetricQueryFilter filter, String interval) {
        long deadline = deadline(filter);
        Map<TrendKey, List<Double>> values = new LinkedHashMap<>();
        Map<TrendKey, Long> totals = new LinkedHashMap<>();
        Map<TrendKey, Long> valid = new LinkedHashMap<>();
        for (JankStoredSignal event : matchingEvents(filter, false, deadline)) {
            checkDeadline(deadline);
            FrameSceneSummaryPayload payload = event.frameSceneSummary();
            if (payload == null || !matches(filter.scene(), payload.scene())
                    || !matches(filter.algorithmVersion(), payload.algorithmVersion())) {
                continue;
            }
            Instant bucketStart = bucketStart(event.occurredAt(), interval);
            TrendKey key = new TrendKey(bucketStart, payload.algorithmVersion());
            totals.merge(key, 1L, Long::sum);
            Double fps = validFps(payload);
            if (fps != null) {
                valid.merge(key, 1L, Long::sum);
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fps);
            }
        }
        List<MetricTrendAggregate> result = totals.entrySet().stream()
                .sorted(trendEntryComparator())
                .map(entry -> trendAggregate(entry.getKey(), interval, entry.getValue(),
                        valid.getOrDefault(entry.getKey(), 0L),
                        values.getOrDefault(entry.getKey(), List.of()), true))
                .limit(filter.limit())
                .toList();
        checkDeadline(deadline);
        return result;
    }

    @Override
    public List<MetricTrendAggregate> querySuspensionTrend(MetricQueryFilter filter) {
        long deadline = deadline(filter);
        Map<SuspensionDayKey, SuspensionDay> days = suspensionDays(filter, null, deadline);
        Map<TrendKey, List<Double>> values = new LinkedHashMap<>();
        Map<TrendKey, Long> totals = new LinkedHashMap<>();
        Map<TrendKey, Long> valid = new LinkedHashMap<>();
        for (Map.Entry<SuspensionDayKey, SuspensionDay> entry : days.entrySet()) {
            checkDeadline(deadline);
            SuspensionDayKey dayKey = entry.getKey();
            SuspensionDay day = entry.getValue();
            TrendKey key = new TrendKey(dayKey.utcDate().atStartOfDay().toInstant(ZoneOffset.UTC),
                    day.algorithmVersion());
            totals.merge(key, day.segmentRecords(), Long::sum);
            Double rate = day.rate();
            if (rate != null) {
                valid.merge(key, 1L, Long::sum);
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rate);
            }
        }
        List<MetricTrendAggregate> result = totals.entrySet().stream()
                .sorted(trendEntryComparator())
                .map(entry -> trendAggregate(entry.getKey(), "day", entry.getValue(),
                        valid.getOrDefault(entry.getKey(), 0L),
                        values.getOrDefault(entry.getKey(), List.of()), false))
                .limit(filter.limit())
                .toList();
        checkDeadline(deadline);
        return result;
    }

    @Override
    public List<MetricDimensionAggregate> queryDimensions(MetricQueryFilter filter, String metric, String dimension) {
        if ("fps".equals(metric)) {
            return fpsDimensions(filter, dimension);
        }
        return suspensionDimensions(filter, dimension);
    }

    @Override
    public String dataSource() {
        return "memory";
    }

    private List<MetricDimensionAggregate> fpsDimensions(MetricQueryFilter filter, String dimension) {
        long deadline = deadline(filter);
        Map<DimensionKey, List<Double>> values = new LinkedHashMap<>();
        Map<DimensionKey, Long> totals = new LinkedHashMap<>();
        Map<DimensionKey, Long> valid = new LinkedHashMap<>();
        for (JankStoredSignal event : matchingEvents(filter, false, deadline)) {
            checkDeadline(deadline);
            FrameSceneSummaryPayload payload = event.frameSceneSummary();
            if (payload == null || !matches(filter.scene(), payload.scene())
                    || !matches(filter.algorithmVersion(), payload.algorithmVersion())) {
                continue;
            }
            DimensionKey key = new DimensionKey(dimensionValue(event, payload, dimension), payload.algorithmVersion());
            totals.merge(key, 1L, Long::sum);
            Double fps = validFps(payload);
            if (fps != null) {
                valid.merge(key, 1L, Long::sum);
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fps);
            }
        }
        List<MetricDimensionAggregate> result = totals.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<DimensionKey, Long> entry) -> entry.getKey().dimensionValue(),
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(entry -> entry.getKey().algorithmVersion(), Comparator.nullsFirst(String::compareTo)))
                .map(entry -> {
                    DimensionKey key = entry.getKey();
                    FpsMetricAggregate stats = fpsAggregate(key.algorithmVersion(), entry.getValue(),
                            valid.getOrDefault(key, 0L), values.getOrDefault(key, List.of()));
                    return new MetricDimensionAggregate(metricName("fps"), dimension, key.dimensionValue(),
                            key.algorithmVersion(), stats.totalRecords(), stats.validRecords(), 0,
                            stats.averageFps(), stats.p50Fps(), stats.p90Fps(), stats.p99Fps(),
                            null, null, null, null, stats.status());
                }).limit(filter.limit()).toList();
        checkDeadline(deadline);
        return result;
    }

    private List<MetricDimensionAggregate> suspensionDimensions(MetricQueryFilter filter, String dimension) {
        long deadline = deadline(filter);
        Map<SuspensionDayKey, SuspensionDay> days = suspensionDays(filter, dimension, deadline);
        Map<DimensionKey, List<Double>> rates = new LinkedHashMap<>();
        Map<DimensionKey, Long> totals = new LinkedHashMap<>();
        Map<DimensionKey, Long> valid = new LinkedHashMap<>();
        for (SuspensionDay day : days.values()) {
            checkDeadline(deadline);
            DimensionKey key = new DimensionKey(day.dimensionValue(), day.algorithmVersion());
            totals.merge(key, day.segmentRecords(), Long::sum);
            Double rate = day.rate();
            if (rate != null) {
                valid.merge(key, 1L, Long::sum);
                rates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rate);
            }
        }
        List<MetricDimensionAggregate> result = totals.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<DimensionKey, Long> entry) -> entry.getKey().dimensionValue(),
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(entry -> entry.getKey().algorithmVersion(), Comparator.nullsFirst(String::compareTo)))
                .map(entry -> {
                    DimensionKey key = entry.getKey();
                    SuspensionMetricAggregate stats = suspensionAggregate(key.algorithmVersion(), entry.getValue(),
                            valid.getOrDefault(key, 0L), rates.getOrDefault(key, List.of()));
                    return new MetricDimensionAggregate(metricName("suspension_rate"), dimension, key.dimensionValue(),
                            key.algorithmVersion(), stats.totalRecords(), stats.validDeviceDayRecords(), stats.validDeviceDayRecords(),
                            null, null, null, null, stats.averageSecondsPerHour(), stats.p50SecondsPerHour(),
                            stats.p90SecondsPerHour(), stats.p99SecondsPerHour(), stats.status());
                }).limit(filter.limit()).toList();
        checkDeadline(deadline);
        return result;
    }

    private Map<SuspensionDayKey, SuspensionDay> suspensionDays(MetricQueryFilter filter, String dimension,
                                                                  long deadline) {
        Map<SuspensionDayKey, SuspensionDay> days = new LinkedHashMap<>();
        for (JankStoredSignal event : matchingEvents(filter, true, deadline)) {
            checkDeadline(deadline);
            ForegroundSuspensionSummaryPayload payload = event.foregroundSuspensionSummary();
            if (payload == null || !matches(filter.algorithmVersion(), payload.algorithmVersion())) {
                continue;
            }
            String dimensionValue = dimension == null ? null : dimensionValue(event, payload, dimension);
            SuspensionDayKey key = new SuspensionDayKey(payload.algorithmVersion(), event.anonymousDeviceId(),
                    LocalDate.ofInstant(event.occurredAt(), ZoneOffset.UTC), event.appVersion(), event.channel(),
                    event.environment(), event.osVersion(), event.deviceModel(), dimensionValue);
            SuspensionDay day = days.computeIfAbsent(key, ignored -> new SuspensionDay(payload.algorithmVersion(),
                    dimensionValue));
            day.add(payload);
        }
        return days;
    }

    private List<JankStoredSignal> matchingEvents(MetricQueryFilter filter, boolean suspension, long deadline) {
        List<JankStoredSignal> result = new ArrayList<>();
        for (JankStoredSignal event : eventRepository.findAll(filter.appId())) {
            checkDeadline(deadline);
            if ((suspension ? !event.isForegroundSuspensionSummary() : !event.isFrameSceneSummary())
                    || event.occurredAt().isBefore(filter.from()) || !event.occurredAt().isBefore(filter.to())
                    || !matches(filter.appVersion(), event.appVersion())
                    || !matches(filter.channel(), event.channel())
                    || !matches(filter.environment(), event.environment())
                    || !matches(filter.osVersion(), event.osVersion())
                    || !matches(filter.deviceModel(), event.deviceModel())) {
                continue;
            }
            result.add(event);
        }
        return List.copyOf(result);
    }

    private long deadline(MetricQueryFilter filter) {
        return System.nanoTime() + filter.timeoutMs() * 1_000_000L;
    }

    private void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline) {
            throw new QueryValidationException("QUERY_TIMEOUT", "查询超过执行时间限制", 408);
        }
    }

    private Double validFps(FrameSceneSummaryPayload payload) {
        if (payload.activeDurationMs() == null || payload.activeDurationMs() <= 0
                || payload.uiRefreshFrameCount() == null || payload.uiRefreshFrameCount() <= 0
                || payload.refreshRateHz() == null || !Double.isFinite(payload.refreshRateHz())
                || payload.refreshRateHz() <= 0 || payload.normalizedFps60() == null
                || !Double.isFinite(payload.normalizedFps60()) || payload.normalizedFps60() < 0) {
            return null;
        }
        return payload.normalizedFps60();
    }

    private FpsMetricAggregate fpsAggregate(String version, long total, long valid, List<Double> values) {
        if (valid == 0) {
            return new FpsMetricAggregate(version, total, 0, null, null, null, null,
                    total == 0 ? "no_data" : "no_valid_data");
        }
        return new FpsMetricAggregate(version, total, valid, average(values), percentile(values, .50, true),
                percentile(values, .90, true), percentile(values, .99, true), "ok");
    }

    private SuspensionMetricAggregate suspensionAggregate(String version, long total, long valid, List<Double> values) {
        if (valid == 0) {
            return new SuspensionMetricAggregate(version, total, 0, null, null, null, null,
                    total == 0 ? "no_data" : "denominator_insufficient");
        }
        return new SuspensionMetricAggregate(version, total, valid, average(values), percentile(values, .50, false),
                percentile(values, .90, false), percentile(values, .99, false), "ok");
    }

    private MetricTrendAggregate trendAggregate(TrendKey key, String interval, long total, long valid,
                                                 List<Double> values, boolean descending) {
        String emptyStatus = descending ? "no_valid_data" : "denominator_insufficient";
        if (valid == 0) {
            return new MetricTrendAggregate(key.bucketStart(), bucketEnd(key.bucketStart(), interval),
                    key.algorithmVersion(), total, 0, null, null, null, null,
                    total == 0 ? "no_data" : emptyStatus);
        }
        return new MetricTrendAggregate(key.bucketStart(), bucketEnd(key.bucketStart(), interval),
                key.algorithmVersion(), total, valid, average(values), percentile(values, .50, descending),
                percentile(values, .90, descending), percentile(values, .99, descending), "ok");
    }

    private Instant bucketStart(Instant value, String interval) {
        if ("hour".equals(interval)) {
            return value.truncatedTo(ChronoUnit.HOURS);
        }
        return LocalDate.ofInstant(value, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private Instant bucketEnd(Instant start, String interval) {
        return "hour".equals(interval) ? start.plus(1, ChronoUnit.HOURS) : start.plus(1, ChronoUnit.DAYS);
    }

    private Comparator<Map.Entry<TrendKey, Long>> trendEntryComparator() {
        return Comparator.comparing((Map.Entry<TrendKey, Long> entry) -> entry.getKey().bucketStart())
                .thenComparing(entry -> entry.getKey().algorithmVersion(), Comparator.nullsFirst(String::compareTo));
    }

    private Double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private Double percentile(List<Double> source, double quantile, boolean descending) {
        if (source.isEmpty()) {
            return null;
        }
        List<Double> values = new ArrayList<>(source);
        values.sort(descending ? Comparator.reverseOrder() : Comparator.naturalOrder());
        int index = Math.max(0, (int) Math.ceil(values.size() * quantile) - 1);
        return values.get(Math.min(index, values.size() - 1));
    }

    private String dimensionValue(JankStoredSignal event, FrameSceneSummaryPayload payload, String dimension) {
        return switch (dimension) {
            case "appVersion" -> event.appVersion();
            case "channel" -> event.channel();
            case "environment" -> event.environment();
            case "osVersion" -> event.osVersion();
            case "deviceModel" -> event.deviceModel();
            case "scene" -> payload.scene();
            case "algorithmVersion" -> payload.algorithmVersion();
            default -> throw new IllegalArgumentException("unsupported dimension: " + dimension);
        };
    }

    private String dimensionValue(JankStoredSignal event, ForegroundSuspensionSummaryPayload payload, String dimension) {
        return switch (dimension) {
            case "appVersion" -> event.appVersion();
            case "channel" -> event.channel();
            case "environment" -> event.environment();
            case "osVersion" -> event.osVersion();
            case "deviceModel" -> event.deviceModel();
            case "algorithmVersion" -> payload.algorithmVersion();
            default -> throw new IllegalArgumentException("unsupported dimension: " + dimension);
        };
    }

    private boolean matches(String expected, String actual) {
        return expected == null || Objects.equals(expected, actual);
    }

    private String metricName(String metric) {
        return metric;
    }

    private record DimensionKey(String dimensionValue, String algorithmVersion) {
    }

    private record TrendKey(Instant bucketStart, String algorithmVersion) {
    }

    private record SuspensionDayKey(String algorithmVersion, String deviceId, LocalDate utcDate,
                                     String appVersion, String channel, String environment, String osVersion,
                                     String deviceModel, String dimensionValue) {
    }

    private static final class SuspensionDay {
        private final String algorithmVersion;
        private final String dimensionValue;
        private long segmentRecords;
        private long foregroundMs;
        private long suspensionMs;

        private SuspensionDay(String algorithmVersion, String dimensionValue) {
            this.algorithmVersion = algorithmVersion;
            this.dimensionValue = dimensionValue;
        }

        private void add(ForegroundSuspensionSummaryPayload payload) {
            segmentRecords++;
            if (payload.foregroundDurationMs() != null) {
                foregroundMs += payload.foregroundDurationMs();
            }
            if (payload.suspensionDurationMs() != null) {
                suspensionMs += payload.suspensionDurationMs();
            }
        }

        private Double rate() {
            return foregroundMs > 0 ? suspensionMs / 1000.0 / (foregroundMs / 3_600_000.0) : null;
        }

        private String algorithmVersion() {
            return algorithmVersion;
        }

        private String dimensionValue() {
            return dimensionValue;
        }

        private long segmentRecords() {
            return segmentRecords;
        }
    }
}
