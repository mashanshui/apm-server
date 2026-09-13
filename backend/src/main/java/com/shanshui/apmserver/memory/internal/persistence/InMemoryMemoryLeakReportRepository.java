package com.shanshui.apmserver.memory.internal.persistence;

import com.shanshui.apmserver.memory.internal.domain.MemoryLeakReport;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import com.shanshui.apmserver.memory.internal.port.MemoryLeakReportRepository;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.platform.api.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 内存验证适配器，按应用和事件 UUID 保存完整报告事实。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryMemoryLeakReportRepository implements MemoryLeakReportRepository {
    private final Map<String, MemoryLeakReport> reports = new ConcurrentHashMap<>();
    private volatile boolean available;
    public InMemoryMemoryLeakReportRepository(StorageProperties properties) { available = properties.isInMemoryAvailable(); }
    @Override public synchronized void append(MemoryLeakReport report) { ensure(); reports.putIfAbsent(key(report.appId(), report.eventId()), report); }
    @Override public Optional<MemoryLeakReport> findByEventId(UUID appId, UUID eventId) { ensure(); return Optional.ofNullable(reports.get(key(appId, eventId))); }
    @Override public List<MemoryLeakReport> findAll(MemoryLeakQueryFilter filter) {
        ensure(); List<MemoryLeakReport> result = new ArrayList<>();
        for (MemoryLeakReport report : reports.values()) {
            if (!report.appId().equals(filter.appId()) || report.occurredAt().isBefore(filter.from()) || !report.occurredAt().isBefore(filter.to())) continue;
            if (!matches(filter.appVersion(), report.appVersion()) || !matches(filter.deviceModel(), report.deviceModel()) ||
                    !matches(filter.processName(), report.processName()) || !matches(filter.scene(), report.scene()) ||
                    !matches(filter.manufacturer(), report.manufacturer()) || (filter.sdkInt() != null && !filter.sdkInt().equals(report.sdkInt())) ||
                    !matches(filter.dumpReason(), report.dumpReason()) || !matches(filter.anonymousDeviceId(), report.anonymousDeviceId())) continue;
            if (filter.signature() != null && report.paths().stream().noneMatch(p -> filter.signature().equals(p.signature()))) continue;
            if (filter.keyword() != null && report.paths().stream().noneMatch(p -> keyword(filter.keyword(), p))) continue;
            result.add(report);
        }
        result.sort(Comparator.comparing(MemoryLeakReport::occurredAt).thenComparing(MemoryLeakReport::eventId));
        return List.copyOf(result);
    }
    @Override public String dataSource() { return "memory"; }
    @Override public Set<String> findAttachmentPaths() {
        ensure();
        return reports.values().stream().map(MemoryLeakReport::attachmentPath)
                .filter(path -> path != null && !path.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public void setAvailable(boolean value) { available = value; }
    public void clear() { reports.clear(); }
    private boolean matches(String expected, String actual) { return expected == null || expected.equals(actual); }
    private boolean keyword(String value, com.shanshui.apmserver.memory.internal.domain.MemoryLeakPath path) {
        if (path.signature().contains(value) || path.leakReason().contains(value) || path.gcRoot().contains(value)) return true;
        return path.path().stream().anyMatch(n -> n.reference().contains(value) || n.referenceType().contains(value) || (n.declaredClass() != null && n.declaredClass().contains(value)));
    }
    private String key(UUID appId, UUID eventId) { return appId + "\u0000" + eventId; }
    private void ensure() { if (!available) throw new EventStoreUnavailableException(); }
}
