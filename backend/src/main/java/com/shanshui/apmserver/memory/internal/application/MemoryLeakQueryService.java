package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryLeakReportValidationException;
import com.shanshui.apmserver.memory.api.MemoryLeakIssueItem;
import com.shanshui.apmserver.memory.api.MemoryLeakIssuesResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakTrendPoint;
import com.shanshui.apmserver.memory.api.MemoryLeakTrendResponse;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakPath;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakReport;
import com.shanshui.apmserver.memory.internal.port.MemoryLeakReportRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 在查询时从去重后的报告事实展开 signature，确保分页不改变统计分母。 */
@Service
public class MemoryLeakQueryService {
    private final MemoryLeakReportRepository repository;
    public MemoryLeakQueryService(MemoryLeakReportRepository repository) { this.repository = repository; }

    public MemoryLeakIssuesResponse issues(MemoryLeakQueryFilter filter, int page, int pageSize, String sort, String order) {
        List<MemoryLeakReport> reports = repository.findAll(filter);
        Map<String, Aggregate> aggregates = aggregate(reports, filter);
        long totalOccurrences = aggregates.values().stream().mapToLong(a -> a.occurrences).sum();
        Set<String> devices = new HashSet<>(); reports.forEach(r -> r.paths().forEach(p -> {
            if (matches(filter, r, p)) devices.add(r.anonymousDeviceId());
        }));
        Comparator<Aggregate> comparator = comparator(sort, order);
        List<Aggregate> all = aggregates.values().stream().sorted(comparator).toList();
        int start = Math.min((page - 1) * pageSize, all.size());
        int end = Math.min(start + pageSize, all.size());
        List<MemoryLeakIssueItem> items = all.subList(start, end).stream().map(a -> a.item(totalOccurrences, devices.size())).toList();
        String status = all.isEmpty() ? "no_data" : "ok";
        return new MemoryLeakIssuesResponse(filter.appId(), filter.from(), filter.to(), all.size(), totalOccurrences,
                devices.size(), page, pageSize, items, status, repository.dataSource());
    }

    public MemoryLeakTrendResponse trend(MemoryLeakQueryFilter filter, String interval) {
        ChronoUnit unit = switch (interval) { case "5m" -> ChronoUnit.MINUTES; case "hour" -> ChronoUnit.HOURS; case "day" -> ChronoUnit.DAYS; default -> throw new MemoryLeakReportValidationException("interval 不受支持"); };
        long bucketSeconds = unit == ChronoUnit.MINUTES ? 300 : unit.getDuration().toSeconds();
        Instant first = bucket(filter.from(), bucketSeconds); List<MemoryLeakReport> reports = repository.findAll(filter);
        Map<Instant, Set<String>> occurrence = new HashMap<>(); Map<Instant, Set<String>> affected = new HashMap<>();
        for (MemoryLeakReport report : reports) {
            Set<String> signatures = new HashSet<>(); report.paths().forEach(p -> { if (matches(filter, report, p)) signatures.add(p.signature()); });
            if (signatures.isEmpty()) continue; Instant bucket = bucket(report.occurredAt(), bucketSeconds);
            occurrence.computeIfAbsent(bucket, ignored -> new HashSet<>()).addAll(signatures.stream().map(s -> report.eventId() + "\u0000" + s).toList());
            affected.computeIfAbsent(bucket, ignored -> new HashSet<>()).add(report.anonymousDeviceId());
        }
        long buckets = Duration.between(first, filter.to()).getSeconds() / bucketSeconds + (Duration.between(first, filter.to()).getSeconds() % bucketSeconds == 0 ? 0 : 1);
        if (buckets > 2000) throw new MemoryLeakReportValidationException("趋势桶数量超过 2000");
        List<MemoryLeakTrendPoint> points = new ArrayList<>();
        for (Instant current = first; current.isBefore(filter.to()); current = current.plusSeconds(bucketSeconds)) {
            points.add(new MemoryLeakTrendPoint(current, occurrence.getOrDefault(current, Set.of()).size(), affected.getOrDefault(current, Set.of()).size()));
        }
        String status = occurrence.isEmpty() ? "no_data" : "ok";
        return new MemoryLeakTrendResponse(filter.appId(), filter.from(), filter.to(), interval, points, status, repository.dataSource());
    }

    private Map<String, Aggregate> aggregate(List<MemoryLeakReport> reports, MemoryLeakQueryFilter filter) {
        Map<String, Aggregate> result = new LinkedHashMap<>();
        for (MemoryLeakReport report : reports) {
            Map<String, MemoryLeakPath> unique = new LinkedHashMap<>(); report.paths().forEach(path -> { if (matches(filter, report, path)) unique.putIfAbsent(path.signature(), path); });
            for (MemoryLeakPath path : unique.values()) result.computeIfAbsent(path.signature(), Aggregate::new).add(report, path);
        }
        return result;
    }
    private boolean matches(MemoryLeakQueryFilter filter, MemoryLeakReport report, MemoryLeakPath path) {
        if (filter.signature() != null && !filter.signature().equals(path.signature())) return false;
        if (filter.keyword() == null) return true;
        return path.signature().contains(filter.keyword()) || path.gcRoot().contains(filter.keyword()) || path.leakReason().contains(filter.keyword()) || path.path().stream().anyMatch(n -> n.reference().contains(filter.keyword()) || n.referenceType().contains(filter.keyword()) || n.declaredClass() != null && n.declaredClass().contains(filter.keyword()));
    }
    private Comparator<Aggregate> comparator(String sort, String order) {
        Comparator<Aggregate> c = switch (sort) { case "occurrences" -> Comparator.comparingLong((Aggregate a) -> a.occurrences); case "affectedDevices" -> Comparator.comparingLong((Aggregate a) -> a.devices.size()); case "lastOccurredAt" -> Comparator.comparing((Aggregate a) -> a.lastOccurredAt); default -> throw new MemoryLeakReportValidationException("sort 不受支持"); };
        if ("desc".equals(order)) c = c.reversed(); return c.thenComparing(a -> a.signature);
    }
    private Instant bucket(Instant instant, long seconds) { long value = instant.getEpochSecond() / seconds * seconds; return Instant.ofEpochSecond(value); }
    private final class Aggregate {
        private final String signature; private long occurrences; private Instant lastOccurredAt = Instant.EPOCH; private MemoryLeakPath latest; private final Set<String> devices = new HashSet<>(); private final Set<String> versions = new HashSet<>();
        private Aggregate(String signature) { this.signature = signature; }
        private void add(MemoryLeakReport report, MemoryLeakPath path) { occurrences++; devices.add(report.anonymousDeviceId()); versions.add(report.appVersion()); if (report.occurredAt().isAfter(lastOccurredAt)) { lastOccurredAt = report.occurredAt(); latest = path; } }
        private MemoryLeakIssueItem item(long total, int allDevices) { return new MemoryLeakIssueItem(signature, latest.path().get(latest.path().size()-1).reference(), latest.leakReason(), latest.gcRoot(), latest.path(), lastOccurredAt, occurrences, total == 0 ? 0 : (double) occurrences / total, devices.size(), allDevices == 0 ? 0 : (double) devices.size() / allDevices, versions.stream().sorted().toList()); }
    }
}
