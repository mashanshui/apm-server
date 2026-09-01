package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.config.StorageProperties;
import com.shanshui.apmserver.domain.AppendResult;
import com.shanshui.apmserver.domain.StoredEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryEventRepository implements EventRepository {

    private final Map<String, StoredEvent> events = new ConcurrentHashMap<>();
    private volatile boolean available;

    public InMemoryEventRepository(StorageProperties properties) {
        this.available = properties.isInMemoryAvailable();
    }

    @Override
    public synchronized AppendResult append(java.util.UUID appId, List<StoredEvent> newEvents) {
        ensureAvailable();
        int accepted = 0;
        int duplicate = 0;
        for (StoredEvent event : newEvents) {
            String key = key(appId, event.eventId());
            if (events.putIfAbsent(key, event) == null) {
                accepted++;
            } else {
                duplicate++;
            }
        }
        return new AppendResult(accepted, duplicate);
    }

    @Override
    public List<StoredEvent> findAll(java.util.UUID appId) {
        ensureAvailable();
        List<StoredEvent> result = new ArrayList<>();
        String prefix = appId + "\u0000";
        for (Map.Entry<String, StoredEvent> entry : events.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        result.sort(Comparator.comparing(StoredEvent::occurredAt).thenComparing(StoredEvent::eventId));
        return List.copyOf(result);
    }

    @Override
    public Optional<StoredEvent> findByEventId(java.util.UUID appId, String eventId) {
        ensureAvailable();
        return Optional.ofNullable(events.get(key(appId, eventId)));
    }

    /** 卡顿事实与详情在同一不可变 StoredEvent 中保存，供契约测试读取，且沿用应用+事件 ID 幂等键。 */
    public List<StoredEvent> findJankEvents(java.util.UUID appId) {
        return findAll(appId).stream().filter(StoredEvent::isJank).toList();
    }

    public Optional<StoredEvent> findJankByEventId(java.util.UUID appId, String eventId) {
        return findByEventId(appId, eventId).filter(StoredEvent::isJank);
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

    private String key(java.util.UUID appId, String eventId) {
        return appId + "\u0000" + eventId;
    }
}
