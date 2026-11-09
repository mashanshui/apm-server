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

    /** 当前模型唯一内存存储。 */
    private final InMemoryCrashStateStore store;
    /** 内存可用性配置。 */
    private final StorageProperties properties;
    private volatile boolean available;

    /** Spring 共用当前状态存储，后台和查询读取同一份结果。 */
    @org.springframework.beans.factory.annotation.Autowired
    public InMemoryCrashRepository(StorageProperties properties, InMemoryCrashStateStore store) {
        this.properties = properties;
        this.store = store;
        this.available = properties.isInMemoryAvailable();
    }

    /** 纯单元测试构造独立数据集，不提供旧模型转换。 */
    public InMemoryCrashRepository(StorageProperties properties) {
        this(properties, new InMemoryCrashStateStore(properties, new tools.jackson.databind.ObjectMapper()));
    }

    @Override
    public synchronized AppendResult append(UUID appId, List<CrashStoredSignal> newEvents) {
        ensureAvailable();
        int accepted = 0;
        int duplicate = 0;
        for (CrashStoredSignal event : newEvents) {
            var crash = event instanceof com.shanshui.apmserver.crash.internal.domain.CrashEvent item ? item : null;
            if (store.accept(new com.shanshui.apmserver.crash.internal.domain.CrashFact(event.metadata(),
                    event.crashKind(), Boolean.TRUE.equals(event.crashFatal()), crash == null ? "" : crash.diagnosticsPayload(),
                    crash == null ? "" : crash.state().captureSha256()), crash == null ? null : crash.state())) {
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
        for (var fact : store.facts(appId)) {
            if ("app_start".equals(fact.metadata().eventType())) {
                result.add(new com.shanshui.apmserver.crash.internal.domain.AppStartEvent(fact.metadata()));
            } else {
                store.current(appId, fact.metadata().eventId()).ifPresent(state -> result.add(
                        new com.shanshui.apmserver.crash.internal.domain.CrashEvent(fact.metadata(), fact.crashKind(), fact.fatal(), state,
                                fact.diagnosticsPayload())));
            }
        }
        result.sort(Comparator.comparing(CrashStoredSignal::occurredAt).thenComparing(CrashStoredSignal::eventId));
        return List.copyOf(result);
    }

    @Override
    public Optional<CrashStoredSignal> findByEventId(UUID appId, String eventId) {
        ensureAvailable();
        return findAll(appId).stream().filter(event -> event.eventId().equals(eventId)).findFirst();
    }

    public void setAvailable(boolean available) {
        this.available = available;
        this.properties.setInMemoryAvailable(available);
    }

    public void clear() {
        store.clear();
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
