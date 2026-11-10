package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.internal.domain.CrashFact;
import com.shanshui.apmserver.crash.internal.domain.CrashCaptureIdentity;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.domain.CrashWorkItem;
import com.shanshui.apmserver.crash.internal.port.CrashStatePort;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.platform.api.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 当前数据集的测试适配器，内存实现不构成生产持久化或容量证据。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryCrashStateStore implements CrashStatePort {
    /** 不变事实按应用和事件去重。 */
    private final Map<String, CrashFact> facts = new HashMap<>();
    /** 当前最高版本状态。 */
    private final Map<String, CrashState> states = new HashMap<>();
    /** 显式内存联调开关。 */
    private final StorageProperties properties;
    /** 当前 JSON 配置。 */
    private final ObjectMapper mapper;
    /** 当前发布编解码。 */
    private final CrashStateJson codec;

    /** 初始化显式内存存储及确定编解码。 */
    public InMemoryCrashStateStore(StorageProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.codec = new CrashStateJson(mapper);
    }

    /** 确认初始状态后才使事实可见，重复上报不重置成功。 */
    @Override
    public synchronized boolean accept(CrashFact fact, CrashState initialState) {
        available();
        // 仅允许两个当前事实类型，不接受旧联合存储数据。
        var metadata = fact.metadata();
        if (!("crash".equals(metadata.eventType()) && initialState != null && fact.fatal()
                && ("jvm".equals(fact.crashKind()) && "raw_chain".equals(initialState.stackFormat())
                || "native".equals(fact.crashKind()) && "raw_native".equals(initialState.stackFormat())))
                && !("app_start".equals(metadata.eventType()) && initialState == null)) {
            throw new IllegalArgumentException("当前事实必须是类型与状态一致的 fatal Crash 或启动事件");
        }
        String key = key(metadata.appId(), metadata.eventId());
        CrashFact existing = facts.get(key);
        boolean legacy = existing != null && existing.captureSha256().isEmpty();
        if (existing != null && (!existing.metadata().buildId().equals(metadata.buildId())
                || !existing.metadata().occurredAt().equals(metadata.occurredAt()))) {
            throw new IllegalArgumentException("重复 eventId 不得改变构建或发生时间");
        }
        if (existing != null && initialState != null) {
            CrashCaptureIdentity.requireCompatible(existing.captureSha256(), initialState.captureSha256(),
                    !fact.diagnosticsPayload().isEmpty() || "native".equals(fact.crashKind()));
        }
        if (initialState != null) {
            if (initialState.stateVersion() != 0 || !"pending".equals(initialState.status())
                    || !initialState.appId().equals(metadata.appId()) || !initialState.eventId().equals(metadata.eventId())
                    || !initialState.buildId().equals(metadata.buildId()) || !initialState.eventTime().equals(metadata.occurredAt())
                    || !initialState.captureSha256().equals(fact.captureSha256())) {
                throw new IllegalArgumentException("初始状态与事实不一致");
            }
            // 部分写入后的孤立状态同样固定原身份，不能确认一个无法关联状态的新事实。
            CrashState previous = states.get(key);
            if (previous != null && (!previous.buildId().equals(metadata.buildId()) || !previous.eventTime().equals(metadata.occurredAt()))) {
                throw new IllegalArgumentException("重复 eventId 不得改变已保存状态的定位");
            }
            if (previous != null) {
                if (existing != null && !existing.captureSha256().equals(previous.captureSha256())) {
                    throw new EventStoreUnavailableException("Crash 事实与状态摘要不一致");
                }
                CrashCaptureIdentity.requireCompatible(previous.captureSha256(), initialState.captureSha256(),
                        !fact.diagnosticsPayload().isEmpty() || "native".equals(fact.crashKind()));
                legacy |= previous.captureSha256().isEmpty();
            }
            states.putIfAbsent(key, legacy ? CrashCaptureIdentity.withoutDigest(initialState) : initialState);
        }
        CrashFact storedFact = legacy ? new CrashFact(fact.metadata(), fact.crashKind(), fact.fatal(), "", "") : fact;
        return facts.putIfAbsent(key, storedFact) == null;
    }

    /** 孤立状态不暴露，且必须与事实的不可变定位一致。 */
    @Override
    public synchronized Optional<CrashState> current(UUID appId, String eventId) {
        available();
        String key = key(appId, eventId);
        CrashState state = states.get(key);
        CrashFact fact = facts.get(key);
        return fact != null && state != null && fact.metadata().buildId().equals(state.buildId())
                && fact.metadata().occurredAt().equals(state.eventTime()) ? Optional.of(state) : Optional.empty();
    }

    /** 同版本只允许同内容，低版本迟到写入不能回退。 */
    @Override
    public synchronized void publish(CrashState state) {
        available();
        String key = key(state.appId(), state.eventId());
        CrashState previous = states.get(key);
        if (previous == null || previous.stateVersion() < state.stateVersion()) states.put(key, state);
        else if (previous.stateVersion() == state.stateVersion() && !previous.equals(state)) {
            throw new IllegalArgumentException("同一逻辑状态版本内容不同");
        }
    }

    /** 以当前固定编码重放已登记的完整结果。 */
    @Override
    public void replay(String payload) { publish(codec.decode(mapper.readTree(payload))); }

    /** 内存参考实现也仅选取有事实且未过期的最新状态。 */
    @Override
    public synchronized List<CrashWorkItem> candidates(UUID appId, String buildId, UUID symbolId, int revision,
                                                   Instant from, Instant now, int limit) {
        available();
        if (limit < 1 || limit > 100 || from.isBefore(now.minusSeconds(30L * 86400)) || !from.isBefore(now)) {
            throw new IllegalArgumentException("后台候选范围超过保留期或批次限制");
        }
        return states.values().stream().filter(state -> state.appId().equals(appId) && state.buildId().equals(buildId))
                .filter(state -> !state.eventTime().isBefore(from) && state.eventTime().isBefore(now.plusSeconds(901)))
                .filter(state -> current(appId, state.eventId()).isPresent())
                .filter(state -> "jvm".equals(facts.get(key(appId, state.eventId())).crashKind())
                        && "raw_chain".equals(state.stackFormat()))
                .filter(state -> "pending".equals(state.status()) || ("failed".equals(state.status())
                        && (!symbolId.equals(state.symbolId()) || !Integer.valueOf(revision).equals(state.symbolRevision())
                        || (state.retryAt() != null && !state.retryAt().isAfter(now)))))
                .sorted(Comparator.comparing(CrashState::eventTime).thenComparing(CrashState::eventId)).limit(limit)
                .map(state -> new CrashWorkItem(state, facts.get(key(appId, state.eventId())).metadata().packageName())).toList();
    }

    /** 未明确授权内存联调时与其他存储保持同样的不可用语义。 */
    private void available() { if (!properties.isInMemoryAvailable()) throw new EventStoreUnavailableException(); }
    /** 复合身份避免应用间相同 eventId 混用。 */
    private String key(UUID appId, String eventId) { return appId + "\u0000" + eventId; }

    /** 小规模内存参考查询使用的当前不变事实集合，不用于生产数据库聚合。 */
    public synchronized List<CrashFact> facts(UUID appId) {
        available();
        return facts.values().stream().filter(fact -> fact.metadata().appId().equals(appId)).toList();
    }

    /** 测试与固定数据集显式清理当前内存数据，不访问旧表。 */
    public synchronized void clear() { facts.clear(); states.clear(); }
}
