package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.crash.internal.port.CrashQueryPort;
import com.shanshui.apmserver.crash.internal.port.CrashWritePort;
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

/** Crash 模块的内存写入与查询适配器。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryCrashRepository implements CrashWritePort, CrashQueryPort {

    private final Map<String, CrashStoredSignal> events = new ConcurrentHashMap<>();
    private volatile boolean available;

    public InMemoryCrashRepository(StorageProperties properties) {
        this.available = properties.isInMemoryAvailable();
    }

    @Override
    public synchronized AppendResult append(UUID appId, List<CrashStoredSignal> newEvents) {
        ensureAvailable();
        int accepted = 0;
        int duplicate = 0;
        for (CrashStoredSignal event : newEvents) {
            if (events.putIfAbsent(key(appId, event.eventId()), event) == null) {
                accepted++;
            } else {
                duplicate++;
            }
        }
        return new AppendResult(accepted, duplicate);
    }

    @Override
    public List<CrashStoredSignal> findAll(UUID appId) {
        ensureAvailable();
        List<CrashStoredSignal> result = new ArrayList<>();
        String prefix = appId + "\u0000";
        for (Map.Entry<String, CrashStoredSignal> entry : events.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.add(entry.getValue());
            }
        }
        result.sort(Comparator.comparing(CrashStoredSignal::occurredAt).thenComparing(CrashStoredSignal::eventId));
        return List.copyOf(result);
    }

    @Override
    public Optional<CrashStoredSignal> findByEventId(UUID appId, String eventId) {
        ensureAvailable();
        return Optional.ofNullable(events.get(key(appId, eventId)));
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
