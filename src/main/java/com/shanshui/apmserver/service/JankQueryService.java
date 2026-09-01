package com.shanshui.apmserver.service;

import com.shanshui.apmserver.config.QueryProperties;
import com.shanshui.apmserver.domain.JankAnalysis;
import com.shanshui.apmserver.domain.JankDurationPercentiles;
import com.shanshui.apmserver.domain.JankEventDetailResponse;
import com.shanshui.apmserver.domain.JankEventListResponse;
import com.shanshui.apmserver.domain.JankEventSummary;
import com.shanshui.apmserver.domain.JankIssueResponse;
import com.shanshui.apmserver.domain.JankIssueSummary;
import com.shanshui.apmserver.domain.JankOverviewResponse;
import com.shanshui.apmserver.domain.JankQueryFilter;
import com.shanshui.apmserver.domain.JankStats;
import com.shanshui.apmserver.domain.JankTrendPoint;
import com.shanshui.apmserver.domain.JankTrendResponse;
import com.shanshui.apmserver.domain.StoredEvent;
import com.shanshui.apmserver.repository.EventRepository;
import com.shanshui.apmserver.repository.InMemoryJankAggregationRepository;
import com.shanshui.apmserver.repository.JankAggregationRepository;
import com.shanshui.apmserver.config.StorageProperties;
import com.shanshui.apmserver.web.QueryParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class JankQueryService {

    private final JankAggregationRepository repository;
    private final QueryProperties properties;

    @Autowired
    public JankQueryService(JankAggregationRepository repository, QueryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 供不启动 Spring 的固定数据集测试使用。 */
    public JankQueryService(EventRepository repository, QueryProperties properties,
                            StorageProperties storageProperties, CrashQualityMetrics ignoredMetrics) {
        this(new InMemoryJankAggregationRepository(repository), properties);
    }

    public JankOverviewResponse overview(java.util.UUID appId, String from, String to, QueryParams params) {
        JankQueryFilter filter = filter(appId, from, to, params);
        List<StoredEvent> events = repository.find(filter);
        return new JankOverviewResponse(appId, filter.from(), filter.to(), stats(events), repository.dataSource());
    }

    public JankTrendResponse trend(java.util.UUID appId, String from, String to, String interval, QueryParams params) {
        if (!"hour".equals(interval) && !"day".equals(interval)) {
            throw new QueryValidationException("INVALID_INTERVAL", "interval 只支持 hour 或 day", 400);
        }
        JankQueryFilter filter = filter(appId, from, to, params);
        Map<Instant, List<StoredEvent>> buckets = new TreeMap<>();
        for (StoredEvent event : repository.find(filter)) {
            Instant bucket = bucket(event.occurredAt(), interval);
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(event);
        }
        List<JankTrendPoint> points = buckets.entrySet().stream().map(entry -> {
            Instant end = "hour".equals(interval) ? entry.getKey().plus(1, ChronoUnit.HOURS)
                    : entry.getKey().plus(1, ChronoUnit.DAYS);
            return new JankTrendPoint(entry.getKey(), end, stats(entry.getValue()));
        }).toList();
        return new JankTrendResponse(appId, filter.from(), filter.to(), interval, points,
                points.isEmpty() ? "no_data" : "ok", repository.dataSource());
    }

    public JankIssueResponse issues(java.util.UUID appId, String from, String to, QueryParams params) {
        JankQueryFilter filter = filter(appId, from, to, params);
        Map<String, List<StoredEvent>> grouped = new LinkedHashMap<>();
        for (StoredEvent event : repository.find(filter)) {
            if (event.crashFingerprint() != null && !event.crashFingerprint().isBlank()) {
                grouped.computeIfAbsent(event.crashFingerprint(), ignored -> new ArrayList<>()).add(event);
            }
        }
        List<JankIssueSummary> all = grouped.entrySet().stream().map(entry -> issueSummary(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(JankIssueSummary::eventCount).reversed()
                        .thenComparing(JankIssueSummary::lastSeenAt, Comparator.reverseOrder())
                        .thenComparing(JankIssueSummary::fingerprint))
                .toList();
        int start = cursorIndex(all, filter.cursor());
        List<JankIssueSummary> page = page(all, start, filter.limit());
        String nextCursor = start + page.size() < all.size() && !page.isEmpty()
                ? page.get(page.size() - 1).fingerprint() : null;
        return new JankIssueResponse(appId, filter.from(), filter.to(), page, nextCursor,
                all.isEmpty() ? "no_data" : "ok", repository.dataSource());
    }

    public JankEventListResponse events(java.util.UUID appId, String fingerprint,
                                        String from, String to, QueryParams params) {
        QueryParams effective = params == null ? new QueryParams() : params;
        effective.setFingerprint(fingerprint);
        JankQueryFilter filter = filter(appId, from, to, effective);
        List<StoredEvent> events = repository.find(filter).stream()
                .sorted(Comparator.comparing(StoredEvent::occurredAt).reversed()
                        .thenComparing(StoredEvent::eventId))
                .toList();
        int start = eventCursorIndex(events, filter.cursor());
        List<StoredEvent> page = page(events, start, filter.limit());
        String nextCursor = start + page.size() < events.size() && !page.isEmpty()
                ? page.get(page.size() - 1).eventId() : null;
        return new JankEventListResponse(appId, fingerprint, filter.from(), filter.to(),
                page.stream().map(this::toSummary).toList(), nextCursor,
                events.isEmpty() ? "no_data" : "ok", repository.dataSource());
    }

    public JankEventDetailResponse event(java.util.UUID appId, String eventId) {
        StoredEvent event = repository.findByEventId(appId, eventId)
                .filter(StoredEvent::isJank)
                .orElseThrow(() -> new QueryValidationException("EVENT_NOT_FOUND", "卡顿事件不存在", 404));
        return new JankEventDetailResponse(event.appId(), event.eventId(), event.packageName(), event.occurredAt(),
                event.receivedAt(), event.sessionId(), event.anonymousDeviceId(), event.appVersion(), event.versionCode(),
                event.buildId(), event.channel(), event.environment(), event.osVersion(), event.deviceModel(),
                event.networkType(), event.jank().scene(), event.jank().algorithmVersion(), event.crashFingerprint(),
                event.fingerprintVersion(), event.jank(), event.jankAnalysis());
    }

    public JankQueryFilter filter(java.util.UUID appId, String fromText, String toText, QueryParams params) {
        QueryParams values = params == null ? new QueryParams() : params;
        Instant to = parseInstant(toText, "to", Instant.now());
        Instant from = parseInstant(fromText, "from", to.minus(24, ChronoUnit.HOURS));
        if (!from.isBefore(to)) {
            throw new QueryValidationException("INVALID_TIME_RANGE", "from 必须早于 to", 400);
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(properties.getMaxRangeDays())) > 0) {
            throw new QueryValidationException("TIME_RANGE_TOO_LARGE", "查询时间范围超过上限", 400);
        }
        int limit = values.getLimit() == null ? properties.getDefaultLimit() : values.getLimit();
        if (limit < 1 || limit > properties.getMaxLimit()) {
            throw new QueryValidationException("INVALID_LIMIT", "limit 超出允许范围", 400);
        }
        long timeout = values.getTimeoutMs() == null ? properties.getDefaultTimeoutMs() : values.getTimeoutMs();
        if (timeout < 1 || timeout > properties.getMaxTimeoutMs()) {
            throw new QueryValidationException("INVALID_TIMEOUT", "timeoutMs 超出允许范围", 400);
        }
        return new JankQueryFilter(appId, from, to, clean(values.getAppVersion()), clean(values.getChannel()),
                clean(values.getEnvironment()), clean(values.getOsVersion()), clean(values.getDeviceModel()),
                clean(values.getScene()), clean(values.getAlgorithmVersion()), clean(values.getFingerprint()),
                limit, clean(values.getCursor()), timeout);
    }

    private JankStats stats(List<StoredEvent> events) {
        Set<String> sessions = new HashSet<>();
        Set<String> devices = new HashSet<>();
        List<Long> exact = new ArrayList<>();
        long groupable = 0L;
        for (StoredEvent event : events) {
            if (event.sessionId() != null) {
                sessions.add(event.sessionId());
            }
            if (event.anonymousDeviceId() != null) {
                devices.add(event.anonymousDeviceId());
            }
            if (event.jank() != null && event.jank().messageDurationNs() != null) {
                exact.add(event.jank().messageDurationNs());
            }
            if (event.crashFingerprint() != null && !event.crashFingerprint().isBlank()) {
                groupable++;
            }
        }
        return new JankStats(events.size(), sessions.size(), devices.size(), groupable,
                percentiles(exact), events.isEmpty() ? "no_data" : "ok");
    }

    private JankIssueSummary issueSummary(String fingerprint, List<StoredEvent> events) {
        Set<String> sessions = new HashSet<>();
        Set<String> devices = new HashSet<>();
        List<Long> exact = new ArrayList<>();
        List<Long> estimated = new ArrayList<>();
        StoredEvent first = events.stream().min(Comparator.comparing(StoredEvent::occurredAt)).orElseThrow();
        StoredEvent last = events.stream().max(Comparator.comparing(StoredEvent::occurredAt)).orElseThrow();
        for (StoredEvent event : events) {
            if (event.sessionId() != null) {
                sessions.add(event.sessionId());
            }
            if (event.anonymousDeviceId() != null) {
                devices.add(event.anonymousDeviceId());
            }
            if (event.jank() != null && event.jank().messageDurationNs() != null) {
                exact.add(event.jank().messageDurationNs());
            }
            JankAnalysis analysis = event.jankAnalysis();
            if (analysis != null) {
                estimated.add(analysis.estimatedDurationNs());
            }
        }
        return new JankIssueSummary(fingerprint, first.fingerprintVersion(), first.jank().scene(),
                first.jank().algorithmVersion(), events.size(), sessions.size(), devices.size(), first.occurredAt(),
                last.occurredAt(), percentiles(exact), percentiles(estimated));
    }

    private JankEventSummary toSummary(StoredEvent event) {
        JankAnalysis analysis = event.jankAnalysis();
        return new JankEventSummary(event.eventId(), event.occurredAt(), event.appVersion(), event.versionCode(),
                event.buildId(), event.channel(), event.environment(), event.osVersion(), event.deviceModel(),
                event.sessionId(), event.anonymousDeviceId(), event.jank().scene(), event.jank().algorithmVersion(),
                event.crashFingerprint(), event.fingerprintVersion(),
                nanosToMillis(event.jank().messageDurationNs()), analysis == null ? null : nanosToMillis(analysis.estimatedDurationNs()),
                analysis == null ? null : nanosToMillis(analysis.estimatedUnattributedDurationNs()),
                analysis == null ? null : nanosToMillis(analysis.coveredDurationNs()),
                analysis == null ? null : nanosToMillis(analysis.uncoveredDurationNs()));
    }

    private JankDurationPercentiles percentiles(List<Long> values) {
        if (values.isEmpty()) {
            return new JankDurationPercentiles(null, null, null);
        }
        List<Long> sorted = values.stream().sorted().toList();
        return new JankDurationPercentiles(nanosToMillis(percentile(sorted, 0.50)),
                nanosToMillis(percentile(sorted, 0.90)), nanosToMillis(percentile(sorted, 0.99)));
    }

    private long percentile(List<Long> sorted, double quantile) {
        int index = Math.max(0, (int) Math.ceil(sorted.size() * quantile) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    private Double nanosToMillis(Long value) {
        return value == null ? null : value / 1_000_000.0;
    }

    private Instant bucket(Instant value, String interval) {
        return "day".equals(interval) ? value.truncatedTo(ChronoUnit.DAYS) : value.truncatedTo(ChronoUnit.HOURS);
    }

    private int cursorIndex(List<JankIssueSummary> values, String cursor) {
        if (cursor == null) {
            return 0;
        }
        for (int i = 0; i < values.size(); i++) {
            if (cursor.equals(values.get(i).fingerprint())) {
                return i + 1;
            }
        }
        return 0;
    }

    private int eventCursorIndex(List<StoredEvent> values, String cursor) {
        if (cursor == null) {
            return 0;
        }
        for (int i = 0; i < values.size(); i++) {
            if (cursor.equals(values.get(i).eventId())) {
                return i + 1;
            }
        }
        return 0;
    }

    private <T> List<T> page(List<T> values, int start, int limit) {
        int safeStart = Math.min(Math.max(0, start), values.size());
        int end = Math.min(values.size(), safeStart + limit);
        return values.subList(safeStart, end);
    }

    private Instant parseInstant(String value, String field, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            throw new QueryValidationException("INVALID_" + field.toUpperCase(), field + " 必须是 ISO-8601 时间", 400);
        }
    }

    private String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 256) {
            throw new QueryValidationException("FILTER_TOO_LONG", "查询筛选条件过长", 400);
        }
        return value.trim();
    }
}
