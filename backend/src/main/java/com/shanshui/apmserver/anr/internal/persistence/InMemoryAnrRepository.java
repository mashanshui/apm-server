package com.shanshui.apmserver.anr.internal.persistence;

import com.shanshui.apmserver.anr.internal.domain.AnrEvent;
import com.shanshui.apmserver.anr.internal.domain.AnrCounts;
import com.shanshui.apmserver.anr.internal.domain.AnrQueryFilter;
import com.shanshui.apmserver.anr.api.AnrEventSummary;
import com.shanshui.apmserver.anr.internal.port.AnrRepository;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.platform.api.StorageProperties;
import com.shanshui.apmserver.telemetry.api.AppendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** 测试和本地内存模式的不可变 ANR 存储。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryAnrRepository implements AnrRepository {
    /** 应用和事件的独立键。 */
    private record Key(UUID appId, String eventId) { }
    /** 内存存储可用性。 */
    private final StorageProperties properties;
    /** 首次确认的事件。 */
    private final Map<Key, AnrEvent> events = new ConcurrentHashMap<>();

    /** 共享测试存储配置。 */
    public InMemoryAnrRepository(StorageProperties properties) {
        this.properties = properties;
    }

    /** 原子地保留首次事件。 */
    @Override
    public AppendResult append(AnrEvent event) {
        ensureAvailable();
        var key = new Key(event.metadata().appId(), event.metadata().eventId());
        return events.putIfAbsent(key, event) == null ? new AppendResult(1, 0) : new AppendResult(0, 1);
    }

    /** 按应用隔离读取。 */
    @Override
    public Optional<AnrEvent> find(UUID appId, String eventId) {
        ensureAvailable();
        return Optional.ofNullable(events.get(new Key(appId, eventId)));
    }

    /** 对隔离内存数据执行同样的时间和筛选语义。 */
    @Override
    public AnrCounts overview(AnrQueryFilter filter) {
        ensureAvailable();
        var selected = events.values().stream().filter(event -> matches(event, filter)).toList();
        return new AnrCounts(selected.size(), selected.stream().map(event -> event.metadata().anonymousDeviceId()).distinct().count());
    }

    /** 返回按发生时间和 UTF-8 事件标识降序排列的有限摘要。 */
    @Override
    public List<AnrEventSummary> list(AnrQueryFilter filter) {
        ensureAvailable();
        return events.values().stream().filter(event -> matches(event, filter))
                .filter(event -> afterCursor(event, filter))
                .sorted((left, right) -> {
                    int time = right.metadata().occurredAt().compareTo(left.metadata().occurredAt());
                    return time != 0 ? time : compareIds(right.metadata().eventId(), left.metadata().eventId());
                }).limit((long) filter.limit() + 1)
                .map(event -> new AnrEventSummary(event.metadata().eventId(), event.metadata().occurredAt(),
                        event.metadata().appVersion(), event.metadata().versionCode(), event.metadata().deviceModel(),
                        event.payload().source())).toList();
    }

    /** 按应用版本和保留期返回有限候选身份。 */
    @Override
    public List<String> candidateIds(UUID appId, String appVersion, Instant afterTime, String afterEventId, int limit) {
        ensureAvailable();
        return events.values().stream().filter(event -> event.metadata().appId().equals(appId)
                        && event.metadata().appVersion().equals(appVersion) && !event.metadata().occurredAt().isBefore(afterTime))
                .map(event -> event.metadata().eventId())
                .filter(id -> afterEventId == null || compareIds(id, afterEventId) > 0)
                .sorted(this::compareIds).limit(limit).toList();
    }

    /** 判断事件是否属于当前应用和半开时间范围。 */
    private boolean matches(AnrEvent event, AnrQueryFilter filter) {
        var metadata = event.metadata();
        return metadata.appId().equals(filter.appId()) && !metadata.occurredAt().isBefore(filter.from())
                && metadata.occurredAt().isBefore(filter.to())
                && (filter.appVersion() == null || filter.appVersion().equals(metadata.appVersion()))
                && (filter.source() == null || filter.source().equals(event.payload().source()));
    }

    /** 用与数据库相同的排序元组判定下一页。 */
    private boolean afterCursor(AnrEvent event, AnrQueryFilter filter) {
        if (filter.afterTime() == null) return true;
        int time = event.metadata().occurredAt().compareTo(filter.afterTime());
        return time < 0 || time == 0 && compareIds(event.metadata().eventId(), filter.afterId()) < 0;
    }

    /** ClickHouse String 按 UTF-8 字节排序。 */
    private int compareIds(String left, String right) {
        return java.util.Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /** 在测试环境明确清理隔离数据。 */
    public void clear() {
        events.clear();
    }

    /** 存储不可用时不得确认写入。 */
    private void ensureAvailable() {
        if (!properties.isInMemoryAvailable()) throw new EventStoreUnavailableException();
    }
}
