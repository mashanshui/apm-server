package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryLeakReportValidationException;
import com.shanshui.apmserver.memory.api.MemoryLeakIssueItem;
import com.shanshui.apmserver.memory.api.MemoryLeakIssuesResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakTrendPoint;
import com.shanshui.apmserver.memory.api.MemoryLeakTrendResponse;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakPath;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakReport;

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
public class MemoryLeakReferenceQueries {
    /** 仅供内存验证的完整报告集合。 */
    private final List<MemoryLeakReport> reports;
    /** 接收已按事实维度筛选的报告。 */
    public MemoryLeakReferenceQueries(List<MemoryLeakReport> reports) { this.reports = reports; }

    /** 仅在内存验证模式对完整匹配报告计算总计和问题页。 */
    public MemoryLeakIssuesResponse issues(MemoryLeakQueryFilter filter, int page, int pageSize, String sort, String order) {
        Map<String, Aggregate> aggregates = aggregate(reports, filter);
        long totalOccurrences = aggregates.values().stream().mapToLong(a -> a.occurrences).sum();
        Set<String> devices = new HashSet<>(); reports.forEach(r -> r.paths().forEach(p -> {
            if (matches(filter, r, p)) devices.add(r.anonymousDeviceId());
        }));
        Comparator<Aggregate> comparator = comparator(sort, order);
        List<Aggregate> all = aggregates.values().stream().sorted(comparator).toList();
        int start = (int) Math.min((long) (page - 1) * pageSize, all.size());
        int end = Math.min(start + pageSize, all.size());
        List<MemoryLeakIssueItem> items = all.subList(start, end).stream().map(a -> a.item(totalOccurrences, devices.size())).toList();
        String status = all.isEmpty() ? "no_data" : "ok";
        return new MemoryLeakIssuesResponse(filter.appId(), filter.from(), filter.to(), all.size(), totalOccurrences,
                devices.size(), page, pageSize, items, status, "memory");
    }

    /** 参考逐桶去重实现，用于与数据库趋势逐字段比较。 */
    public MemoryLeakTrendResponse trend(MemoryLeakQueryFilter filter, String interval) {
        ChronoUnit unit = switch (interval) { case "5m" -> ChronoUnit.MINUTES; case "hour" -> ChronoUnit.HOURS; case "day" -> ChronoUnit.DAYS; default -> throw new MemoryLeakReportValidationException("interval 不受支持"); };
        long bucketSeconds = unit == ChronoUnit.MINUTES ? 300 : unit.getDuration().toSeconds();
        Instant first = bucket(filter.from(), bucketSeconds);
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
        return new MemoryLeakTrendResponse(filter.appId(), filter.from(), filter.to(), interval, points, status, "memory");
    }

    /** 匹配后每个报告每个 signature 仅选择第一条路径。 */
    private Map<String, Aggregate> aggregate(List<MemoryLeakReport> reports, MemoryLeakQueryFilter filter) {
        Map<String, Aggregate> result = new LinkedHashMap<>();
        for (MemoryLeakReport report : reports) {
            Map<String, MemoryLeakPath> unique = new LinkedHashMap<>(); report.paths().forEach(path -> { if (matches(filter, report, path)) unique.putIfAbsent(path.signature(), path); });
            for (MemoryLeakPath path : unique.values()) result.computeIfAbsent(path.signature(), Aggregate::new).add(report, path);
        }
        return result;
    }
    /** 路径字段执行相同的大小写敏感字面筛选。 */
    private boolean matches(MemoryLeakQueryFilter filter, MemoryLeakReport report, MemoryLeakPath path) {
        if (filter.signature() != null && !filter.signature().equals(path.signature())) return false;
        if (filter.keyword() == null) return true;
        return path.signature().contains(filter.keyword()) || path.gcRoot().contains(filter.keyword()) || path.leakReason().contains(filter.keyword()) || path.path().stream().anyMatch(n -> n.reference().contains(filter.keyword()) || n.referenceType().contains(filter.keyword()) || n.declaredClass() != null && n.declaredClass().contains(filter.keyword()));
    }
    /** 排序使用标量指标，signature 升序作为固定并列键。 */
    private Comparator<Aggregate> comparator(String sort, String order) {
        Comparator<Aggregate> c = switch (sort) { case "occurrences" -> Comparator.comparingLong((Aggregate a) -> a.occurrences); case "affectedDevices" -> Comparator.comparingLong((Aggregate a) -> a.devices.size()); case "lastOccurredAt" -> Comparator.comparing((Aggregate a) -> a.lastOccurredAt); default -> throw new MemoryLeakReportValidationException("sort 不受支持"); };
        if ("desc".equals(order)) c = c.reversed(); return c.thenComparing(a -> a.signature);
    }
    /** 参考数据映射到 UTC 桶起点。 */
    private Instant bucket(Instant instant, long seconds) { long value = instant.getEpochSecond() / seconds * seconds; return Instant.ofEpochSecond(value); }
    /** 一个 signature 的完整次数、设备、版本与代表路径。 */
    private final class Aggregate {
        /** 问题归组标识。 */
        private final String signature;
        /** 去重后的发生次数。 */
        private long occurrences;
        /** 完整范围内最近发生时间。 */
        private Instant lastOccurredAt = Instant.MIN;
        /** 最近时间并列时的代表事件 ID。 */
        private UUID latestEventId;
        /** 代表事件内首条匹配路径。 */
        private MemoryLeakPath latest;
        /** 完整设备集合，用于当前问题去重数。 */
        private final Set<String> devices = new HashSet<>();
        /** 完整版本集合，不因页大小或展示长度截断。 */
        private final Set<String> versions = new HashSet<>();

        /** 初始化问题归组标识。 */
        private Aggregate(String signature) {
            this.signature = signature;
        }

        /** 最新时间并列时按 eventId 字符串升序选取路径。 */
        private void add(MemoryLeakReport report, MemoryLeakPath path) {
            occurrences++;
            devices.add(report.anonymousDeviceId());
            versions.add(report.appVersion());
            if (report.occurredAt().isAfter(lastOccurredAt)
                    || report.occurredAt().equals(lastOccurredAt)
                    && report.eventId().toString().compareTo(latestEventId.toString()) < 0) {
                lastOccurredAt = report.occurredAt();
                latestEventId = report.eventId();
                latest = path;
            }
        }

        /** 以全范围分母生成完整当前页展示，不截断路径或版本。 */
        private MemoryLeakIssueItem item(long total, int allDevices) {
            return new MemoryLeakIssueItem(signature, latest.path().get(latest.path().size()-1).reference(),
                    latest.leakReason(), latest.gcRoot(), latest.path(), lastOccurredAt, occurrences,
                    total == 0 ? 0 : (double) occurrences / total, devices.size(),
                    allDevices == 0 ? 0 : (double) devices.size() / allDevices, versions.stream().sorted().toList());
        }
    }
}
