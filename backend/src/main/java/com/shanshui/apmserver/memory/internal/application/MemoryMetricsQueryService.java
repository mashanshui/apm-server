package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryMetricStats;
import com.shanshui.apmserver.memory.api.MemoryMetricQuery;
import com.shanshui.apmserver.memory.api.MemoryMetricQueries;
import com.shanshui.apmserver.memory.api.MemoryMetricsSummaryResponse;
import com.shanshui.apmserver.memory.api.MemoryTrendPoint;
import com.shanshui.apmserver.memory.api.MemoryTrendResponse;
import com.shanshui.apmserver.memory.internal.domain.MemoryQueryCommand;
import com.shanshui.apmserver.memory.internal.domain.MemoryQueryFilter;
import com.shanshui.apmserver.memory.internal.domain.MemoryTrendAggregate;
import com.shanshui.apmserver.memory.internal.port.MemoryMetricsRepository;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 内存概览和趋势查询服务，集中执行时间、筛选和白名单校验。 */
@Service
public class MemoryMetricsQueryService implements MemoryMetricQueries {

    private static final int MAX_MEMORY_RANGE_DAYS = 31;

    private final MemoryMetricsRepository repository;
    private final QueryProperties properties;
    private final Clock clock;

    @Autowired
    public MemoryMetricsQueryService(MemoryMetricsRepository repository, QueryProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    public MemoryMetricsQueryService(MemoryMetricsRepository repository, QueryProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public MemoryMetricsSummaryResponse summary(UUID appId, String from, String to, MemoryQueryCommand params) {
        MemoryQueryFilter filter = filter(appId, from, to, params);
        MemoryMetricStats pss = repository.queryPss(filter);
        MemoryMetricStats vss = repository.queryVss(filter);
        MemoryMetricStats javaHeap = repository.queryJavaHeap(filter);
        return new MemoryMetricsSummaryResponse(appId, filter.from(), filter.to(), pss, vss, javaHeap,
                overallStatus(List.of(pss, vss, javaHeap)), repository.dataSource());
    }

    /** 公共契约在域内转为已有查询命令。 */
    @Override
    public MemoryMetricsSummaryResponse summary(UUID appId, String from, String to, MemoryMetricQuery params) {
        return summary(appId, from, to, command(params));
    }

    @Override
    public MemoryTrendResponse trend(UUID appId, String metric, String interval, String from, String to,
                                     MemoryMetricQuery params) {
        return trend(appId, metric, interval, from, to, command(params));
    }

    private MemoryQueryCommand command(MemoryMetricQuery params) {
        return new MemoryQueryCommand(params.appVersion(), params.osVersion(), params.deviceModel(),
                params.processName(), params.scene(), params.foreground(), params.limit(), params.timeoutMs());
    }

    public MemoryTrendResponse trend(UUID appId, String metric, String interval, String from, String to,
                                     MemoryQueryCommand params) {
        String normalizedMetric = normalizeMetric(metric);
        String normalizedInterval = normalizeInterval(interval);
        MemoryQueryFilter filter = filter(appId, from, to, params);
        List<MemoryTrendAggregate> aggregates = repository.queryTrend(filter, normalizedMetric, normalizedInterval);
        Map<Instant, MemoryTrendAggregate> byStart = new HashMap<>();
        aggregates.forEach(value -> byStart.put(value.bucketStart(), value));
        Instant cursor = floor(filter.from(), normalizedInterval);
        List<MemoryTrendPoint> points = new java.util.ArrayList<>();
        while (cursor.isBefore(filter.to())) {
            Instant end = plusBucket(cursor, normalizedInterval);
            MemoryTrendAggregate aggregate = byStart.get(cursor);
            MemoryMetricStats stats = aggregate == null
                    ? new MemoryMetricStats(0, null, null, null, null, null, "no_data")
                    : aggregate.stats();
            points.add(MemoryTrendPoint.from(cursor, end, stats));
            cursor = end;
        }
        String status = points.stream().anyMatch(point -> "ok".equals(point.status())) ? "ok" : "no_data";
        return new MemoryTrendResponse(appId, filter.from(), filter.to(), normalizedMetric, normalizedInterval,
                points, status, repository.dataSource());
    }

    private MemoryQueryFilter filter(UUID appId, String fromText, String toText, MemoryQueryCommand params) {
        MemoryQueryCommand values = params == null ? MemoryQueryCommand.empty() : params;
        Instant to = parseInstant(toText, "to", Instant.now(clock));
        Instant from = parseInstant(fromText, "from", to.minus(24, ChronoUnit.HOURS));
        if (!from.isBefore(to)) {
            throw new QueryValidationException("INVALID_TIME_RANGE", "from 必须早于 to", 400);
        }
        int maxRangeDays = Math.min(MAX_MEMORY_RANGE_DAYS, Math.max(1, properties.getMaxRangeDays()));
        if (Duration.between(from, to).compareTo(Duration.ofDays(maxRangeDays)) > 0) {
            throw new QueryValidationException("TIME_RANGE_TOO_LARGE", "查询时间范围超过 31 天上限", 400);
        }
        int limit = values.limit() == null ? properties.getDefaultLimit() : values.limit();
        if (limit < 1 || limit > properties.getMaxLimit()) {
            throw new QueryValidationException("INVALID_LIMIT", "limit 超出允许范围", 400);
        }
        long timeout = values.timeoutMs() == null ? properties.getDefaultTimeoutMs() : values.timeoutMs();
        if (timeout < 1 || timeout > properties.getMaxTimeoutMs()) {
            throw new QueryValidationException("INVALID_TIMEOUT", "timeoutMs 超出允许范围", 400);
        }
        return new MemoryQueryFilter(appId, from, to, clean(values.appVersion(), 256, "appVersion"),
                clean(values.osVersion(), 256, "osVersion"), clean(values.deviceModel(), 256, "deviceModel"),
                clean(values.processName(), 256, "processName"), clean(values.scene(), 128, "scene"),
                values.foreground(), limit, timeout);
    }

    private String normalizeMetric(String metric) {
        String value = metric == null || metric.isBlank() ? "pss" : metric.trim();
        if (!"pss".equals(value) && !"vss".equals(value) && !"java_heap".equals(value)) {
            throw new QueryValidationException("INVALID_METRIC", "metric 只支持 pss、vss 或 java_heap", 400);
        }
        return value;
    }

    private String normalizeInterval(String interval) {
        String value = interval == null || interval.isBlank() ? "hour" : interval.trim();
        if (!"hour".equals(value) && !"day".equals(value)) {
            throw new QueryValidationException("INVALID_INTERVAL", "interval 只支持 hour 或 day", 400);
        }
        return value;
    }

    private Instant floor(Instant value, String interval) {
        return value.truncatedTo("hour".equals(interval) ? ChronoUnit.HOURS : ChronoUnit.DAYS);
    }

    private Instant plusBucket(Instant value, String interval) {
        return "hour".equals(interval) ? value.plus(1, ChronoUnit.HOURS) : value.plus(1, ChronoUnit.DAYS);
    }

    private String overallStatus(List<MemoryMetricStats> values) {
        return values.stream().anyMatch(value -> "ok".equals(value.status())) ? "ok" : "no_data";
    }

    private Instant parseInstant(String value, String field, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new QueryValidationException("INVALID_" + field.toUpperCase(),
                    field + " 必须是 ISO-8601 时间", 400);
        }
    }

    private String clean(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String result = value.trim();
        if (result.length() > maxLength) {
            throw new QueryValidationException("FILTER_TOO_LONG", field + " 超过长度上限", 400);
        }
        return result;
    }
}
