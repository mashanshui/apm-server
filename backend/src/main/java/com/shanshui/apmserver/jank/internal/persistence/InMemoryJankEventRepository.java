package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.port.JankEventRepository;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.telemetry.api.AppendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Jank 个例、FPS 与挂起率事件的内存存储适配器。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryJankEventRepository implements JankEventRepository {

    private final Map<String, JankStoredSignal> events = new ConcurrentHashMap<>();
    private volatile boolean available;

    public InMemoryJankEventRepository(StorageProperties properties) {
        this.available = properties.isInMemoryAvailable();
    }

    @Override
    public synchronized AppendResult append(UUID appId, List<JankStoredSignal> newEvents) {
        ensureAvailable();
        int accepted = 0;
        int duplicate = 0;
        for (JankStoredSignal event : newEvents) {
            if (events.putIfAbsent(key(appId, event.eventId()), event) == null) {
                accepted++;
            } else {
                duplicate++;
            }
        }
        return new AppendResult(accepted, duplicate);
    }

    @Override
    public List<JankStoredSignal> findAll(UUID appId) {
        ensureAvailable();
        List<JankStoredSignal> result = new ArrayList<>();
        String prefix = appId + "\u0000";
        for (Map.Entry<String, JankStoredSignal> entry : events.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        result.sort(Comparator.comparing(JankStoredSignal::occurredAt).thenComparing(JankStoredSignal::eventId));
        return List.copyOf(result);
    }

    @Override
    public Optional<JankStoredSignal> findByEventId(UUID appId, String eventId) {
        ensureAvailable();
        return Optional.ofNullable(events.get(key(appId, eventId)));
    }

    public List<JankEvent> findJankEvents(UUID appId) {
        return findAll(appId).stream().filter(JankEvent.class::isInstance).map(JankEvent.class::cast).toList();
    }

    public Optional<JankEvent> findJankByEventId(UUID appId, String eventId) {
        return findByEventId(appId, eventId).filter(JankEvent.class::isInstance).map(JankEvent.class::cast);
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void clear() {
        events.clear();
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
