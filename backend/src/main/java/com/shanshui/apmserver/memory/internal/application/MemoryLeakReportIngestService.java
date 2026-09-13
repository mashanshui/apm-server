package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryLeakReportResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakEventConflictException;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakReport;
import com.shanshui.apmserver.memory.internal.port.MemoryLeakReportRepository;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 统一编排报告校验、附件持久化和事件幂等；报告不会进入 memory_sample。 */
@Service
public class MemoryLeakReportIngestService {
    private final MemoryLeakReportParser parser;
    private final MemoryLeakReportRepository repository;
    private final MemoryLeakArtifactStore artifactStore;
    private final ConcurrentHashMap<String, EventLock> eventLocks = new ConcurrentHashMap<>();

    public MemoryLeakReportIngestService(MemoryLeakReportParser parser, MemoryLeakReportRepository repository,
                                         MemoryLeakArtifactStore artifactStore) {
        this.parser = parser; this.repository = repository; this.artifactStore = artifactStore;
    }

    public MemoryLeakReportResponse ingest(UUID appId, String expectedPackageName, JsonNode metadata,
                                           JsonNode report, InputStream attachment) {
        String lockKey = appId + "\u0000" + eventId(metadata);
        EventLock holder = eventLocks.compute(lockKey, (ignored, current) -> {
            EventLock value = current == null ? new EventLock() : current;
            value.references++;
            return value;
        });
        holder.lock.lock();
        try {
            return ingestLocked(appId, expectedPackageName, metadata, report, attachment);
        } finally {
            holder.lock.unlock();
            // 引用计数和按 key 的原子移除避免新请求复用旧锁时被误删，防止锁表随 eventId 无限增长。
            eventLocks.computeIfPresent(lockKey, (ignored, current) -> {
                if (current != holder) return current;
                return --current.references == 0 ? null : current;
            });
        }
    }

    private static final class EventLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    private MemoryLeakReportResponse ingestLocked(UUID appId, String expectedPackageName, JsonNode metadata,
                                                  JsonNode report, InputStream attachment) {
        MemoryLeakReport parsed = parser.parse(appId, metadata, report, Instant.now());
        if (!expectedPackageName.equals(parsed.packageName())) {
            throw new com.shanshui.apmserver.identity.api.PackageNameMismatchException();
        }
        MemoryLeakReport existing = repository.findByEventId(appId, parsed.eventId()).orElse(null);
        if (existing != null) {
            if (!existing.payloadHash().equals(parsed.payloadHash())) throw new MemoryLeakEventConflictException();
            if (attachment == null && existing.attachmentDigest() != null
                    || attachment != null && existing.attachmentDigest() == null) throw new MemoryLeakEventConflictException();
            if (attachment != null && !artifactStore.digest(attachment).equals(existing.attachmentDigest())) {
                throw new MemoryLeakEventConflictException();
            }
            return new MemoryLeakReportResponse(parsed.eventId(), "duplicate", existing.paths().size(),
                    existing.attachmentDigest() == null ? "absent" : "stored");
        }
        MemoryLeakReport storedReport = parsed;
        if (attachment != null) {
            MemoryLeakArtifactStore.StoredArtifact stored = artifactStore.store(appId, parsed.eventId(), attachment);
            storedReport = new MemoryLeakReport(parsed.appId(), parsed.eventId(), parsed.occurredAt(), parsed.receivedAt(),
                    parsed.packageName(), parsed.appVersion(), parsed.versionCode(), parsed.anonymousDeviceId(), parsed.processName(),
                    parsed.sessionId(), parsed.buildId(), parsed.environment(), parsed.channel(), parsed.deviceModel(), parsed.scene(),
                    parsed.manufacturer(), parsed.sdkInt(), parsed.dumpReason(), parsed.report(), parsed.paths(), parsed.payloadHash(),
                    stored.digest(), stored.path(), stored.bytes());
        }
        try {
            repository.append(storedReport);
            return new MemoryLeakReportResponse(parsed.eventId(), "accepted", parsed.paths().size(),
                    attachment == null ? "absent" : "stored");
        } catch (RuntimeException ex) {
            // 数据库结果不确定时保留附件，允许同一事件重试恢复；不主动删除可能已经关联的文件。
            if (ex instanceof EventStoreUnavailableException) throw ex;
            throw ex;
        }
    }

    private String eventId(JsonNode envelope) {
        if (envelope == null || !envelope.isObject()) return UUID.randomUUID().toString();
        JsonNode value = envelope.get("eventId");
        return value == null || value.isNull() ? UUID.randomUUID().toString() : value.asText();
    }
}
