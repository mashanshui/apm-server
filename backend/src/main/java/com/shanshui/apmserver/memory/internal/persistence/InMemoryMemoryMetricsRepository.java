package com.shanshui.apmserver.memory.internal.persistence;

import com.shanshui.apmserver.memory.api.MemoryMetricStats;
import com.shanshui.apmserver.memory.internal.application.MemoryStatistics;
import com.shanshui.apmserver.memory.internal.domain.MemoryEvent;
import com.shanshui.apmserver.memory.internal.domain.MemoryQueryFilter;
import com.shanshui.apmserver.memory.internal.domain.MemoryTrendAggregate;
import com.shanshui.apmserver.memory.internal.port.MemoryEventRepository;
import com.shanshui.apmserver.memory.internal.port.MemoryMetricsRepository;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.telemetry.api.AppendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 内存模式适配器，使用 appId/eventId 幂等并在查询时按原始采样重新计算统计。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryMemoryMetricsRepository implements MemoryEventRepository, MemoryMetricsRepository {

    private final Map<String, MemoryEvent> events = new ConcurrentHashMap<>();
    private volatile boolean available;

    public InMemoryMemoryMetricsRepository(StorageProperties properties) {
        this.available = properties.isInMemoryAvailable();
    }

    @Override
    public synchronized AppendResult append(UUID appId, List<MemoryEvent> newEvents) {
        ensureAvailable();
        int accepted = 0;
        int duplicate = 0;
        for (MemoryEvent event : newEvents) {
            if (events.putIfAbsent(key(appId, event.eventId()), event) == null) {
                accepted++;
            } else {
                duplicate++;
            }
        }
        return new AppendResult(accepted, duplicate);
    }

    @Override
    public List<MemoryEvent> findAll(UUID appId) {
        ensureAvailable();
        String prefix = appId + "\u0000";
        List<MemoryEvent> result = new ArrayList<>();
        for (Map.Entry<String, MemoryEvent> entry : events.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        result.sort(Comparator.comparing(MemoryEvent::occurredAt).thenComparing(MemoryEvent::eventId));
        return List.copyOf(result);
    }

    @Override
    public Optional<MemoryEvent> findByEventId(UUID appId, String eventId) {
        ensureAvailable();
        return Optional.ofNullable(events.get(key(appId, eventId)));
    }

    @Override
    public MemoryMetricStats queryPss(MemoryQueryFilter filter) {
        return stats(filter, MemoryEvent::pssBytes);
    }

    @Override
    public MemoryMetricStats queryVss(MemoryQueryFilter filter) {
        return stats(filter, MemoryEvent::vssBytes);
    }

    @Override
    public MemoryMetricStats queryJavaHeap(MemoryQueryFilter filter) {
        return stats(filter, MemoryEvent::javaHeapUsedBytes);
    }

    @Override
    public List<MemoryTrendAggregate> queryTrend(MemoryQueryFilter filter, String metric, String interval) {
        ensureAvailable();
        java.util.function.Function<MemoryEvent, Long> extractor = extractor(metric);
        ChronoUnit unit = unit(interval);
        Map<Instant, List<MemoryEvent>> grouped = new LinkedHashMap<>();
        for (MemoryEvent event : filtered(filter)) {
            Instant bucket = event.occurredAt().truncatedTo(unit);
            grouped.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(event);
        }
        // 趋势需要覆盖查询区间内的全部 UTC 桶；limit 只保留在接口兼容校验，不截断时间轴。
        return grouped.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Instant end = "hour".equals(interval) ? entry.getKey().plus(1, ChronoUnit.HOURS)
                            : entry.getKey().plus(1, ChronoUnit.DAYS);
                    return new MemoryTrendAggregate(entry.getKey(), end,
                            MemoryStatistics.of(entry.getValue().stream().map(extractor).toList()));
                }).toList();
    }

    @Override
    public String dataSource() {
        return "memory";
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void clear() {
        events.clear();
    }

    private MemoryMetricStats stats(MemoryQueryFilter filter, java.util.function.Function<MemoryEvent, Long> extractor) {
        ensureAvailable();
        return MemoryStatistics.of(filtered(filter).stream().map(extractor).toList());
    }

    private List<MemoryEvent> filtered(MemoryQueryFilter filter) {
        List<MemoryEvent> result = new ArrayList<>();
        for (MemoryEvent event : events.values()) {
            if (!event.appId().equals(filter.appId()) || event.occurredAt().isBefore(filter.from())
                    || !event.occurredAt().isBefore(filter.to())) {
                continue;
            }
            if (!matches(filter.appVersion(), event.appVersion())
                    || !matches(filter.osVersion(), event.osVersion())
                    || !matches(filter.deviceModel(), event.deviceModel())
                    || !matches(filter.processName(), event.processName())
                    || !matches(filter.scene(), event.scene())
                    || (filter.foreground() != null && !filter.foreground().equals(event.foreground()))) {
                continue;
            }
            result.add(event);
        }
        return result;
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }

    private java.util.function.Function<MemoryEvent, Long> extractor(String metric) {
        return switch (metric) {
            case "pss" -> MemoryEvent::pssBytes;
            case "vss" -> MemoryEvent::vssBytes;
            case "java_heap" -> MemoryEvent::javaHeapUsedBytes;
            default -> throw new IllegalArgumentException("不支持的内存指标: " + metric);
        };
    }

    private ChronoUnit unit(String interval) {
        return switch (interval) {
            case "hour" -> ChronoUnit.HOURS;
            case "day" -> ChronoUnit.DAYS;
            default -> throw new IllegalArgumentException("不支持的内存趋势粒度: " + interval);
        };
    }

    private void ensureAvailable() {
        if (!available) {
            throw new EventStoreUnavailableException();
        }
    }

    private String key(UUID appId, String eventId) {
        return appId + "\u0000" + eventId;
    }
}
