package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.domain.JankQueryFilter;
import com.shanshui.apmserver.domain.StoredEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 固定数据集和契约测试使用的卡顿查询实现；逻辑与 ClickHouse 结果口径保持一致。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryJankAggregationRepository implements JankAggregationRepository {

    private final EventRepository eventRepository;

    public InMemoryJankAggregationRepository(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public List<StoredEvent> find(JankQueryFilter filter) {
        long deadline = System.nanoTime() + filter.timeoutMs() * 1_000_000L;
        List<StoredEvent> result = new ArrayList<>();
        for (StoredEvent event : eventRepository.findAll(filter.appId())) {
            if (System.nanoTime() > deadline) {
                throw new com.shanshui.apmserver.service.QueryValidationException("QUERY_TIMEOUT", "查询超过执行时间限制", 408);
            }
            if (!event.isJank() || event.occurredAt().isBefore(filter.from()) || !event.occurredAt().isBefore(filter.to())) {
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
        result.sort(Comparator.comparing(StoredEvent::occurredAt).thenComparing(StoredEvent::eventId));
        return List.copyOf(result);
    }

    @Override
    public Optional<StoredEvent> findByEventId(java.util.UUID appId, String eventId) {
        return eventRepository.findByEventId(appId, eventId).filter(StoredEvent::isJank);
    }

    @Override
    public String dataSource() {
        return "memory";
    }

    private boolean matches(String expected, String actual) {
        return expected == null || expected.equals(actual);
    }
}
