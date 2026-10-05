package com.shanshui.apmserver.jank.internal.application;


import com.shanshui.apmserver.jank.api.JankAnalysis;
import com.shanshui.apmserver.jank.api.JankDurationPercentiles;
import com.shanshui.apmserver.jank.api.JankEventSummary;
import com.shanshui.apmserver.jank.api.JankIssueSummary;
import com.shanshui.apmserver.jank.api.JankStats;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;


import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 内存验证模式的确定性参考聚合，不用于生产全量查询。 */
public final class JankReferenceQueries {
    /** 禁止创建无状态参考计算器实例。 */
    private JankReferenceQueries() {}
    /** 完整范围的计数、非空身份去重和精确耗时统计。 */
    public static JankStats stats(List<JankEvent> events) {
        // 非空会话集合。
        Set<String> sessions = new HashSet<>();
        // 非空设备集合。
        Set<String> devices = new HashSet<>();
        // 有效精确消息耗时。
        List<Long> exact = new ArrayList<>();
        // 可归入稳定 Issue 的事件数。
        long groupable = 0L;
        for (JankEvent event : events) {
            if (event.sessionId() != null && !event.sessionId().isEmpty()) {
                sessions.add(event.sessionId());
            }
            if (event.anonymousDeviceId() != null && !event.anonymousDeviceId().isEmpty()) {
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

    /** 每组使用全部事件，代表字段取最早时间及事件 ID 升序。 */
    public static JankIssueSummary issueSummary(String fingerprint, List<JankEvent> events) {
        // 非空会话集合。
        Set<String> sessions = new HashSet<>();
        // 非空设备集合。
        Set<String> devices = new HashSet<>();
        // 有效精确消息耗时。
        List<Long> exact = new ArrayList<>();
        // 有分析证据的估算耗时。
        List<Long> estimated = new ArrayList<>();
        // 并列时间按事件 ID 固定代表来源。
        JankEvent first = events.stream().min(Comparator.comparing(JankEvent::occurredAt).thenComparing(JankEvent::eventId)).orElseThrow();
        // 最近发生时间用于排序。
        JankEvent last = events.stream().max(Comparator.comparing(JankEvent::occurredAt)).orElseThrow();
        for (JankEvent event : events) {
            if (event.sessionId() != null && !event.sessionId().isEmpty()) {
                sessions.add(event.sessionId());
            }
            if (event.anonymousDeviceId() != null && !event.anonymousDeviceId().isEmpty()) {
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

    /** 摘要保留精确与估算的区别，缺失分析不能补成零。 */
    public static JankEventSummary toSummary(JankEvent event) {
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

    /** 有效值升序按 ceil(N*p) 取秩，空集合返回 null。 */
    public static JankDurationPercentiles percentiles(List<Long> values) {
        if (values.isEmpty()) {
            return new JankDurationPercentiles(null, null, null);
        }
        // 排序仅用于小规模内存参考。
        List<Long> sorted = values.stream().sorted().toList();
        return new JankDurationPercentiles(nanosToMillis(percentile(sorted, 0.50)),
                nanosToMillis(percentile(sorted, 0.90)), nanosToMillis(percentile(sorted, 0.99)));
    }

    /** 计算一基秩并转换成有界的零基数组下标。 */
    private static long percentile(List<Long> sorted, double quantile) {
        // 将 API 的一基秩映射为 Java 下标。
        int index = Math.max(0, (int) Math.ceil(sorted.size() * quantile) - 1);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }

    /** 仅在展示层换算单位，保留缺失耗时。 */
    private static Double nanosToMillis(Long value) {
        return value == null ? null : value / 1_000_000.0;
    }

    /** 将事件归入 UTC 小时或日桶。 */
    public static Instant bucket(Instant value, String interval) {
        return "day".equals(interval) ? value.truncatedTo(ChronoUnit.DAYS) : value.truncatedTo(ChronoUnit.HOURS);
    }

}
