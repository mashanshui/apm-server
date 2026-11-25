package com.shanshui.apmserver.anr.internal.persistence;

import com.shanshui.apmserver.anr.api.AnrJavaView;
import com.shanshui.apmserver.anr.internal.port.AnrSymbolicationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 隔离内存模式的分区派生视图。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryAnrSymbolicationStore implements AnrSymbolicationStore {
    /** 应用、事件和分区的唯一键。 */
    private record Key(UUID appId, String eventId, String section) { }
    /** 包含版本的状态。 */
    private record State(long version, AnrJavaView view) { }
    /** 当前已发布状态。 */
    private final Map<Key, State> views = new ConcurrentHashMap<>();

    /** 获取单分区视图。 */
    @Override
    public Optional<AnrJavaView> find(UUID appId, String eventId, String section) {
        return Optional.ofNullable(views.get(new Key(appId, eventId, section))).map(State::view);
    }

    /** 保留较高版本，相同版本只接受相同内容。 */
    @Override
    public void write(UUID appId, String eventId, Instant occurredAt, String section, long version, AnrJavaView view) {
        views.compute(new Key(appId, eventId, section), (key, previous) -> {
            if (previous == null || version > previous.version()) return new State(version, view);
            if (version == previous.version() && !previous.view().equals(view)) {
                throw new IllegalStateException("相同 ANR 还原版本的内容不一致");
            }
            return previous;
        });
    }
}
