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
    public synchronized AppendResult append(String projectId, List<StoredEvent> newEvents) {
        ensureAvailable();
        int accepted = 0;
        int duplicate = 0;
        for (StoredEvent event : newEvents) {
            String key = key(projectId, event.eventId());
            if (events.putIfAbsent(key, event) == null) {
                accepted++;
            } else {
                duplicate++;
            }
        }
        return new AppendResult(accepted, duplicate);
    }

    @Override
    public List<StoredEvent> findAll(String projectId) {
        ensureAvailable();
        List<StoredEvent> result = new ArrayList<>();
        String prefix = projectId + "\u0000";
        for (Map.Entry<String, StoredEvent> entry : events.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        result.sort(Comparator.comparing(StoredEvent::occurredAt).thenComparing(StoredEvent::eventId));
        return List.copyOf(result);
    }

    @Override
    public Optional<StoredEvent> findByEventId(String projectId, String eventId) {
        ensureAvailable();
        return Optional.ofNullable(events.get(key(projectId, eventId)));
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

    private String key(String projectId, String eventId) {
        return projectId + "\u0000" + eventId;
    }
}
