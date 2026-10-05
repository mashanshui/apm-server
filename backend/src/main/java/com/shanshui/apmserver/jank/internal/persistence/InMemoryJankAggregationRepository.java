package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.internal.port.JankAggregationRepository;
import com.shanshui.apmserver.jank.internal.port.JankEventRepository;

import com.shanshui.apmserver.platform.api.QueryValidationException;

import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import com.shanshui.apmserver.jank.api.*;
import com.shanshui.apmserver.jank.internal.application.JankReferenceQueries;
import com.shanshui.apmserver.jank.internal.application.JankCursor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 固定数据集和契约测试使用的卡顿查询实现；逻辑与 ClickHouse 结果口径保持一致。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryJankAggregationRepository implements JankAggregationRepository {

    private final JankEventRepository eventRepository;

    public InMemoryJankAggregationRepository(JankEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** 小数据参考扫描仅用于内存模式。 */
    private List<JankEvent> find(JankQueryFilter filter) {
        long deadline = System.nanoTime() + filter.timeoutMs() * 1_000_000L;
        List<JankEvent> result = new ArrayList<>();
        for (JankStoredSignal stored : eventRepository.findAll(filter.appId())) {
            if (System.nanoTime() > deadline) {
                throw new com.shanshui.apmserver.platform.api.QueryValidationException("QUERY_TIMEOUT", "查询超过执行时间限制", 408);
            }
            if (!(stored instanceof JankEvent event)
                    || event.occurredAt().isBefore(filter.from()) || !event.occurredAt().isBefore(filter.to())) {
                continue;
            }
            if (!matches(filter.appVersion(), event.appVersion()) || !matches(filter.channel(), event.channel())
                    || !matches(filter.environment(), event.environment()) || !matches(filter.osVersion(), event.osVersion())
                    || !matches(filter.deviceModel(), event.deviceModel())
                    || (filter.scene() != null && (event.jank() == null || !filter.scene().equals(event.jank().scene())))
                    || (filter.algorithmVersion() != null && (event.jank() == null
                    || !filter.algorithmVersion().equals(event.jank().algorithmVersion())))
                    || (filter.fingerprint() != null && !filter.fingerprint().equals(event.crashFingerprint()))) {
                continue;
            }
            result.add(event);
        }
        result.sort(Comparator.comparing(JankEvent::occurredAt).thenComparing(JankEvent::eventId));
        return List.copyOf(result);
    }

    /** 全范围计数与分位数参考。 */
    @Override
    public JankStats overview(JankQueryFilter filter) { return JankReferenceQueries.stats(find(filter)); }

    /** 按 UTC 时间分桶，保留现有非空桶策略。 */
    @Override
    public List<JankTrendPoint> trend(JankQueryFilter filter, String interval) {
        // 内存参考数据按时间有序分桶。
        Map<Instant, List<JankEvent>> buckets = new TreeMap<>();
        for (JankEvent event : find(filter)) {
            buckets.computeIfAbsent(JankReferenceQueries.bucket(event.occurredAt(), interval),
                    ignored -> new ArrayList<>()).add(event);
        }
        return buckets.entrySet().stream().map(entry -> new JankTrendPoint(entry.getKey(),
                entry.getKey().plus(1, "hour".equals(interval) ? ChronoUnit.HOURS : ChronoUnit.DAYS),
                JankReferenceQueries.stats(entry.getValue()))).toList();
    }

    /** 聚合先于游标过滤与 limit+1，完整分母不受页大小影响。 */
    @Override
    public List<JankIssueSummary> issues(JankQueryFilter filter, JankCursor.State cursor) {
        // 每个指纹对应其全部事件。
        Map<String, List<JankEvent>> groups = new TreeMap<>();
        for (JankEvent event : find(filter)) {
            if (event.crashFingerprint() != null && !event.crashFingerprint().isBlank())
                groups.computeIfAbsent(event.crashFingerprint(), ignored -> new ArrayList<>()).add(event);
        }
        return groups.entrySet().stream().map(entry -> JankReferenceQueries.issueSummary(entry.getKey(), entry.getValue()))
                .filter(value -> cursor == null || value.eventCount() < cursor.count()
                        || value.eventCount() == cursor.count() && (value.lastSeenAt().isBefore(cursor.time())
                        || value.lastSeenAt().equals(cursor.time()) && value.fingerprint().compareTo(cursor.id()) > 0))
                .sorted(Comparator.comparingLong(JankIssueSummary::eventCount).reversed()
                        .thenComparing(JankIssueSummary::lastSeenAt, Comparator.reverseOrder())
                        .thenComparing(JankIssueSummary::fingerprint))
                .limit((long) filter.limit() + 1).toList();
    }

    /** 按完整时间/ID 元组续页，摘要保留缺失估算。 */
    @Override
    public List<JankEventSummary> events(JankQueryFilter filter, JankCursor.State cursor) {
        return find(filter).stream()
                .filter(value -> cursor == null || value.occurredAt().isBefore(cursor.time())
                        || value.occurredAt().equals(cursor.time()) && value.eventId().compareTo(cursor.id()) > 0)
                .sorted(Comparator.comparing(JankEvent::occurredAt).reversed().thenComparing(JankEvent::eventId))
                .limit((long) filter.limit() + 1).map(JankReferenceQueries::toSummary).toList();
    }

    @Override
    public Optional<JankEvent> findByEventId(java.util.UUID appId, String eventId) {
        return eventRepository.findByEventId(appId, eventId)
                .filter(JankEvent.class::isInstance).map(JankEvent.class::cast);
    }

    @Override
    public String dataSource() {
        return "memory";
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }
}
