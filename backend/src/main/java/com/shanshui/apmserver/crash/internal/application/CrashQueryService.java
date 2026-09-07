package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashMetrics;
import com.shanshui.apmserver.platform.api.QueryValidationException;

import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.crash.api.CrashEventDetailResponse;
import com.shanshui.apmserver.crash.api.CrashEventListResponse;
import com.shanshui.apmserver.crash.api.CrashEventSummary;
import com.shanshui.apmserver.crash.api.CrashIssueResponse;
import com.shanshui.apmserver.crash.api.CrashIssueSummary;
import com.shanshui.apmserver.crash.api.CrashOverviewResponse;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryFilter;
import com.shanshui.apmserver.crash.api.CrashStats;
import com.shanshui.apmserver.crash.api.CrashTrendPoint;
import com.shanshui.apmserver.crash.api.CrashTrendResponse;
import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.crash.internal.port.CrashQueryPort;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryCommand;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

@Service
public class CrashQueryService {

    private final CrashQueryPort repository;
    private final QueryProperties properties;
    private final StorageProperties storageProperties;
    private final CrashMetrics metrics;

    public CrashQueryService(CrashQueryPort repository,
                             QueryProperties properties,
                             StorageProperties storageProperties,
                             CrashMetrics metrics) {
        this.repository = repository;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.metrics = metrics;
    }

    public CrashOverviewResponse overview(java.util.UUID appId, String from, String to, CrashQueryCommand params) {
        CrashQueryFilter filter = filter(appId, from, to, params);
        List<CrashStoredSignal> events = findFiltered(filter, event -> true);
        return new CrashOverviewResponse(appId, filter.from(), filter.to(), stats(events), dataSource());
    }

    public CrashTrendResponse trend(java.util.UUID appId, String from, String to, String interval, CrashQueryCommand params) {
        if (!"hour".equals(interval) && !"day".equals(interval)) {
            throw new QueryValidationException("INVALID_INTERVAL", "interval 只支持 hour 或 day", 400);
        }
        CrashQueryFilter filter = filter(appId, from, to, params);
        List<CrashStoredSignal> events = findFiltered(filter, event -> true);
        Map<Instant, List<CrashStoredSignal>> buckets = new TreeMap<>();
        for (CrashStoredSignal event : events) {
            Instant bucket = bucket(event.occurredAt(), interval);
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(event);
        }
        List<CrashTrendPoint> points = buckets.entrySet().stream()
                .map(entry -> {
                    Instant end = "hour".equals(interval)
                            ? entry.getKey().plus(1, ChronoUnit.HOURS)
                            : entry.getKey().plus(1, ChronoUnit.DAYS);
                    return new CrashTrendPoint(entry.getKey(), end, stats(entry.getValue()));
                })
                .toList();
        return new CrashTrendResponse(appId, filter.from(), filter.to(), interval, points, dataSource());
    }

    public CrashIssueResponse issues(java.util.UUID appId, String from, String to, CrashQueryCommand params) {
        CrashQueryFilter filter = filter(appId, from, to, params);
        List<CrashStoredSignal> events = findFiltered(filter, CrashStoredSignal::isCrash);
        Map<String, List<CrashStoredSignal>> grouped = groupByFingerprint(events);
        List<CrashIssueSummary> all = grouped.entrySet().stream()
                .map(entry -> issueSummary(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(CrashIssueSummary::eventCount).reversed()
                        .thenComparing(CrashIssueSummary::lastSeenAt, Comparator.reverseOrder())
                        .thenComparing(CrashIssueSummary::fingerprint))
                .toList();
        int start = cursorIndex(all, filter.cursor());
        List<CrashIssueSummary> page = page(all, start, filter.limit());
        String nextCursor = start + page.size() < all.size() && !page.isEmpty()
                ? page.get(page.size() - 1).fingerprint() : null;
        return new CrashIssueResponse(appId, filter.from(), filter.to(), page, nextCursor, dataSource());
    }

    public CrashEventListResponse events(java.util.UUID appId, String fingerprint,
                                         String from, String to, CrashQueryCommand params) {
        CrashQueryCommand effective = params == null ? CrashQueryCommand.empty() : params;
        effective = effective.withFingerprint(fingerprint);
        CrashQueryFilter filter = filter(appId, from, to, effective);
        List<CrashStoredSignal> events = findFiltered(filter, event -> event.isCrash()
                && fingerprint.equals(event.crashFingerprint()));
        events = events.stream()
                .sorted(Comparator.comparing(CrashStoredSignal::occurredAt).reversed()
                        .thenComparing(CrashStoredSignal::eventId))
                .toList();
        int start = eventCursorIndex(events, filter.cursor());
        List<CrashStoredSignal> page = page(events, start, filter.limit());
        String nextCursor = start + page.size() < events.size() && !page.isEmpty()
                ? page.get(page.size() - 1).eventId() : null;
        return new CrashEventListResponse(appId, fingerprint, filter.from(), filter.to(),
                page.stream().map(this::toSummary).toList(), nextCursor, dataSource());
    }

    public CrashEventDetailResponse event(java.util.UUID appId, String eventId) {
        CrashStoredSignal event = repository.findByEventId(appId, eventId)
                .filter(CrashStoredSignal::isCrash)
                .orElseThrow(() -> new QueryValidationException("EVENT_NOT_FOUND", "Crash 事件不存在", 404));
        return new CrashEventDetailResponse(
                event.appId(), event.eventId(), event.packageName(), event.occurredAt(), event.receivedAt(),
                event.sessionId(), event.anonymousDeviceId(), event.appVersion(), event.versionCode(),
                event.buildId(), event.channel(), event.environment(), event.osVersion(), event.deviceModel(),
                event.networkType(), event.crashExceptionType(), event.crashFingerprint(),
                event.fingerprintVersion(), event.symbolicationStatus(), event.crash());
    }

    public CrashQueryFilter filter(java.util.UUID appId, String fromText, String toText, CrashQueryCommand params) {
        CrashQueryCommand values = params == null ? CrashQueryCommand.empty() : params;
        Instant to = parseInstant(toText, "to", Instant.now());
        Instant from = parseInstant(fromText, "from", to.minus(24, ChronoUnit.HOURS));
        if (!from.isBefore(to)) {
            throw new QueryValidationException("INVALID_TIME_RANGE", "from 必须早于 to", 400);
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(properties.getMaxRangeDays())) > 0) {
            throw new QueryValidationException("TIME_RANGE_TOO_LARGE", "查询时间范围超过上限", 400);
        }
        int limit = values.limit() == null ? properties.getDefaultLimit() : values.limit();
        if (limit < 1 || limit > properties.getMaxLimit()) {
            throw new QueryValidationException("INVALID_LIMIT", "limit 超出允许范围", 400);
        }
        long timeout = values.timeoutMs() == null ? properties.getDefaultTimeoutMs() : values.timeoutMs();
        if (timeout < 1 || timeout > properties.getMaxTimeoutMs()) {
            throw new QueryValidationException("INVALID_TIMEOUT", "timeoutMs 超出允许范围", 400);
        }
        return new CrashQueryFilter(appId, from, to,
                clean(values.appVersion()), clean(values.channel()), clean(values.environment()),
                clean(values.osVersion()), clean(values.deviceModel()), clean(values.fingerprint()),
                limit, clean(values.cursor()), timeout);
    }

    private List<CrashStoredSignal> findFiltered(CrashQueryFilter filter, Predicate<CrashStoredSignal> predicate) {
        long deadline = System.nanoTime() + filter.timeoutMs() * 1_000_000L;
        List<CrashStoredSignal> all = repository.findAll(filter.appId());
        List<CrashStoredSignal> result = new ArrayList<>();
        for (CrashStoredSignal event : all) {
            if (System.nanoTime() > deadline) {
                throw new QueryValidationException("QUERY_TIMEOUT", "查询超过执行时间限制", 408);
            }
            if (event.occurredAt().isBefore(filter.from()) || !event.occurredAt().isBefore(filter.to())) {
                continue;
            }
            if (!matchesDimensionFilters(event, filter) || !predicate.test(event)) {
                continue;
            }
            result.add(event);
        }
        return result;
    }

    private boolean matchesDimensionFilters(CrashStoredSignal event, CrashQueryFilter filter) {
        return matches(filter.appVersion(), event.appVersion())
                && matches(filter.channel(), event.channel())
                && matches(filter.environment(), event.environment())
                && matches(filter.osVersion(), event.osVersion())
                && matches(filter.deviceModel(), event.deviceModel())
                && (filter.fingerprint() == null || event.isAppStart()
                || filter.fingerprint().equals(event.crashFingerprint()));
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private CrashStats stats(List<CrashStoredSignal> events) {
        Set<String> startedSessions = new HashSet<>();
        Set<String> crashEvents = new HashSet<>();
        Set<String> crashedSessions = new HashSet<>();
        Set<String> affectedDevices = new HashSet<>();
        for (CrashStoredSignal event : events) {
            if (event.isAppStart() && event.sessionId() != null) {
                startedSessions.add(event.sessionId());
            }
            if (event.isCrash()) {
                crashEvents.add(event.eventId());
                if (event.sessionId() != null) {
                    crashedSessions.add(event.sessionId());
                }
                if (event.anonymousDeviceId() != null) {
                    affectedDevices.add(event.anonymousDeviceId());
                }
            }
        }
        if (startedSessions.isEmpty()) {
            metrics.denominatorInsufficient();
        }
        double crashRate = startedSessions.isEmpty()
                ? 0.0 : ((double) crashedSessions.size() / startedSessions.size()) * 1000.0;
        double crashFreeRate = startedSessions.isEmpty()
                ? 0.0 : 1.0 - ((double) crashedSessions.size() / startedSessions.size());
        Double crashRateValue = startedSessions.isEmpty() ? null : crashRate;
        Double crashFreeRateValue = startedSessions.isEmpty() ? null : crashFreeRate;
        String status = startedSessions.isEmpty()
                ? (events.isEmpty() ? "no_data" : "denominator_insufficient") : "ok";
        return new CrashStats(startedSessions.size(), crashEvents.size(), crashedSessions.size(),
                affectedDevices.size(), crashRateValue, crashFreeRateValue, status);
    }

    private Map<String, List<CrashStoredSignal>> groupByFingerprint(List<CrashStoredSignal> events) {
        Map<String, List<CrashStoredSignal>> grouped = new LinkedHashMap<>();
        for (CrashStoredSignal event : events) {
            grouped.computeIfAbsent(event.crashFingerprint(), ignored -> new ArrayList<>()).add(event);
        }
        return grouped;
    }

    private CrashIssueSummary issueSummary(String fingerprint, List<CrashStoredSignal> events) {
        Set<String> eventIds = new HashSet<>();
        Set<String> sessions = new HashSet<>();
        Set<String> devices = new HashSet<>();
        CrashStoredSignal first = events.stream().min(Comparator.comparing(CrashStoredSignal::occurredAt)).orElseThrow();
        CrashStoredSignal last = events.stream().max(Comparator.comparing(CrashStoredSignal::occurredAt)).orElseThrow();
        for (CrashStoredSignal event : events) {
            eventIds.add(event.eventId());
            if (event.sessionId() != null) {
                sessions.add(event.sessionId());
            }
            if (event.anonymousDeviceId() != null) {
                devices.add(event.anonymousDeviceId());
            }
        }
        return new CrashIssueSummary(fingerprint, first.fingerprintVersion(), first.crashExceptionType(),
                eventIds.size(), sessions.size(), devices.size(), first.occurredAt(), last.occurredAt());
    }

    private CrashEventSummary toSummary(CrashStoredSignal event) {
        return new CrashEventSummary(event.eventId(), event.occurredAt(), event.appVersion(), event.versionCode(),
                event.buildId(), event.channel(), event.environment(), event.osVersion(), event.deviceModel(),
                event.sessionId(), event.anonymousDeviceId(), event.crashExceptionType(), event.crashFingerprint(),
                event.symbolicationStatus());
    }

    private Instant bucket(Instant value, String interval) {
        return "day".equals(interval) ? value.truncatedTo(ChronoUnit.DAYS) : value.truncatedTo(ChronoUnit.HOURS);
    }

    private int cursorIndex(List<CrashIssueSummary> values, String cursor) {
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

    private int eventCursorIndex(List<CrashStoredSignal> values, String cursor) {
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
        int end = Math.min(values.size(), start + limit);
        return values.subList(Math.min(start, values.size()), end);
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

    private String dataSource() {
        return storageProperties.getMode();
    }
}
