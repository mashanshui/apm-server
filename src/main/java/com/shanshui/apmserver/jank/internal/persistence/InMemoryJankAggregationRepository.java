package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.internal.port.JankAggregationRepository;
import com.shanshui.apmserver.jank.internal.port.JankEventRepository;

import com.shanshui.apmserver.platform.api.QueryValidationException;

import com.shanshui.apmserver.jank.internal.domain.JankQueryFilter;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
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

    private final JankEventRepository eventRepository;

    public InMemoryJankAggregationRepository(JankEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public List<JankEvent> find(JankQueryFilter filter) {
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
