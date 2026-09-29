package com.shanshui.apmserver.crash.internal.application;

import com.shanshui.apmserver.crash.api.CrashEventSummary;
import com.shanshui.apmserver.crash.api.CrashIssueSummary;
import com.shanshui.apmserver.crash.api.CrashStats;
import com.shanshui.apmserver.crash.api.CrashTrendPoint;
import com.shanshui.apmserver.crash.internal.domain.CrashPage;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryFilter;
import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.platform.api.QueryValidationException;

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

/** 内存查询参考口径，供 ClickHouse 固定集对照使用。 */
public final class CrashReferenceQueries {

    private CrashReferenceQueries() {
    }

    /** 过滤时间窗和白名单维度；指纹不影响启动分母。 */
    public static List<CrashStoredSignal> filtered(List<CrashStoredSignal> all, CrashQueryFilter filter) {
        long deadline = System.nanoTime() + filter.timeoutMs() * 1_000_000L;
        List<CrashStoredSignal> result = new ArrayList<>();
        for (CrashStoredSignal event : all) {
            if (System.nanoTime() > deadline) {
                throw new QueryValidationException("QUERY_TIMEOUT", "查询超过执行时间限制", 408);
            }
            if (event.occurredAt().isBefore(filter.from()) || !event.occurredAt().isBefore(filter.to())) {
                continue;
            }
            if (matches(filter.appVersion(), event.appVersion())
                    && matches(filter.channel(), event.channel())
                    && matches(filter.environment(), event.environment())
                    && matches(filter.osVersion(), event.osVersion())
                    && matches(filter.deviceModel(), event.deviceModel())
                    && (filter.fingerprint() == null || event.isAppStart()
                    || filter.fingerprint().equals(event.crashFingerprint()))) {
                result.add(event);
            }
        }
        return result;
    }

    /** 根据逻辑事件集合计算精确 distinct 统计。 */
    public static CrashStats stats(List<CrashStoredSignal> events) {
        Set<String> starts = new HashSet<>();
        Set<String> crashes = new HashSet<>();
        Set<String> sessions = new HashSet<>();
        Set<String> devices = new HashSet<>();
        for (CrashStoredSignal event : events) {
            if (event.isAppStart() && event.sessionId() != null) starts.add(event.sessionId());
            if (event.isCrash()) {
                crashes.add(event.eventId());
                if (event.sessionId() != null) sessions.add(event.sessionId());
                if (event.anonymousDeviceId() != null) devices.add(event.anonymousDeviceId());
            }
        }
        long denominator = starts.size();
        return new CrashStats(denominator, crashes.size(), sessions.size(), devices.size(),
                denominator == 0 ? null : (double) sessions.size() / denominator * 1000.0,
                denominator == 0 ? null : 1.0 - (double) sessions.size() / denominator,
                denominator == 0 ? (events.isEmpty() ? "no_data" : "denominator_insufficient") : "ok");
    }

    /** 内存概览。 */
    public static CrashStats overview(List<CrashStoredSignal> all, CrashQueryFilter filter) {
        return stats(filtered(all, filter));
    }

    /** 内存趋势，与数据库一样按 UTC 桶边界聚合。 */
    public static List<CrashTrendPoint> trend(List<CrashStoredSignal> all, CrashQueryFilter filter, String interval) {
        Map<Instant, List<CrashStoredSignal>> buckets = new TreeMap<>();
        for (CrashStoredSignal event : filtered(all, filter)) {
            Instant start = event.occurredAt().truncatedTo("day".equals(interval) ? ChronoUnit.DAYS : ChronoUnit.HOURS);
            buckets.computeIfAbsent(start, ignored -> new ArrayList<>()).add(event);
        }
        return buckets.entrySet().stream().map(entry -> new CrashTrendPoint(entry.getKey(),
                entry.getKey().plus(1, "day".equals(interval) ? ChronoUnit.DAYS : ChronoUnit.HOURS),
                stats(entry.getValue()))).toList();
    }

    /** 内存 Issue 页，后续游标由统一工具绑定查询条件。 */
    public static CrashPage<CrashIssueSummary> issues(List<CrashStoredSignal> all, CrashQueryFilter filter) {
        Map<String, List<CrashStoredSignal>> groups = new LinkedHashMap<>();
        for (CrashStoredSignal event : filtered(all, filter)) {
            if (event.isCrash()) groups.computeIfAbsent(event.crashFingerprint(), ignored -> new ArrayList<>()).add(event);
        }
        List<CrashIssueSummary> values = groups.entrySet().stream().map(entry -> issue(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(CrashIssueSummary::eventCount).reversed()
                        .thenComparing(CrashIssueSummary::lastSeenAt, Comparator.reverseOrder())
                        .thenComparing(CrashIssueSummary::fingerprint)).toList();
        int start = indexAfterIssue(values, filter);
        List<CrashIssueSummary> page = values.subList(Math.min(start, values.size()), Math.min(values.size(), start + filter.limit()));
        return new CrashPage<>(page, start + page.size() < values.size() ? CrashCursor.issue(filter, page.getLast()) : null);
    }

    /** 内存事件摘要页，不载入详情之外的额外证据。 */
    public static CrashPage<CrashEventSummary> events(List<CrashStoredSignal> all, CrashQueryFilter filter,
                                                       String fingerprint) {
        List<CrashStoredSignal> values = filtered(all, filter).stream()
                .filter(event -> event.isCrash() && fingerprint.equals(event.crashFingerprint()))
                .sorted(Comparator.comparing(CrashStoredSignal::occurredAt).reversed()
                        .thenComparing(CrashStoredSignal::eventId)).toList();
        int start = indexAfterEvent(values, filter);
        List<CrashStoredSignal> page = values.subList(Math.min(start, values.size()), Math.min(values.size(), start + filter.limit()));
        List<CrashEventSummary> summaries = page.stream().map(CrashReferenceQueries::summary).toList();
        return new CrashPage<>(summaries,
                start + page.size() < values.size() ? CrashCursor.event(filter, summaries.getLast()) : null);
    }

    /** 事件摘要保留已有 HTTP 字段。 */
    public static CrashEventSummary summary(CrashStoredSignal event) {
        return new CrashEventSummary(event.eventId(), event.occurredAt(), event.appVersion(), event.versionCode(),
                event.buildId(), event.channel(), event.environment(), event.osVersion(), event.deviceModel(),
                event.sessionId(), event.anonymousDeviceId(), event.crashExceptionType(), event.crashFingerprint(),
                event.symbolicationStatus());
    }

    private static CrashIssueSummary issue(String fingerprint, List<CrashStoredSignal> events) {
        Set<String> ids = new HashSet<>();
        Set<String> sessions = new HashSet<>();
        Set<String> devices = new HashSet<>();
        CrashStoredSignal first = events.stream().min(Comparator.comparing(CrashStoredSignal::occurredAt)).orElseThrow();
        CrashStoredSignal last = events.stream().max(Comparator.comparing(CrashStoredSignal::occurredAt)).orElseThrow();
        for (CrashStoredSignal event : events) {
            ids.add(event.eventId());
            if (event.sessionId() != null) sessions.add(event.sessionId());
            if (event.anonymousDeviceId() != null) devices.add(event.anonymousDeviceId());
        }
        return new CrashIssueSummary(fingerprint, first.fingerprintVersion(), first.crashExceptionType(),
                ids.size(), sessions.size(), devices.size(), first.occurredAt(), last.occurredAt());
    }

    private static int indexAfterIssue(List<CrashIssueSummary> values, CrashQueryFilter filter) {
        if (filter.cursor() == null) return 0;
        CrashCursor.State cursor = CrashCursor.validate(filter.cursor(), CrashCursor.Kind.ISSUES, filter);
        for (int index = 0; index < values.size(); index++) {
            CrashIssueSummary value = values.get(index);
            if (value.eventCount() < cursor.count()
                    || value.eventCount() == cursor.count() && value.lastSeenAt().isBefore(cursor.time())
                    || value.eventCount() == cursor.count() && value.lastSeenAt().equals(cursor.time())
                    && value.fingerprint().compareTo(cursor.id()) > 0) return index;
        }
        return values.size();
    }

    private static int indexAfterEvent(List<CrashStoredSignal> values, CrashQueryFilter filter) {
        if (filter.cursor() == null) return 0;
        CrashCursor.State cursor = CrashCursor.validate(filter.cursor(), CrashCursor.Kind.EVENTS, filter);
        for (int index = 0; index < values.size(); index++) {
            CrashStoredSignal value = values.get(index);
            if (value.occurredAt().isBefore(cursor.time())
                    || value.occurredAt().equals(cursor.time()) && value.eventId().compareTo(cursor.id()) > 0) return index;
        }
        return values.size();
    }

    private static boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }
}
