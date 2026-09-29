package com.shanshui.apmserver.memory.internal.application;

import com.shanshui.apmserver.memory.api.MemoryLeakIssuesResponse;
import com.shanshui.apmserver.memory.api.MemoryLeakQueries;
import com.shanshui.apmserver.memory.api.MemoryLeakTrendResponse;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import com.shanshui.apmserver.platform.api.QueryValidationException;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 收敛网页与 Agent 的同一套内存异常参数白名单和查询口径。 */
@Service
public class MemoryLeakQueryFacade implements MemoryLeakQueries {

    private static final Set<String> COMMON = Set.of("from", "to", "appVersion", "deviceModel", "processName",
            "scene", "manufacturer", "sdkInt", "dumpReason", "anonymousDeviceId", "signature", "keyword");
    private static final Set<String> ISSUE = union(COMMON, Set.of("page", "pageSize", "sort", "order"));
    private static final Set<String> TREND = union(COMMON, Set.of("interval"));

    private final MemoryLeakQueryService queryService;

    public MemoryLeakQueryFacade(MemoryLeakQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public MemoryLeakIssuesResponse issues(UUID appId, Map<String, String> query) {
        validateNames(query.keySet(), ISSUE);
        MemoryLeakQueryFilter filter = filter(appId, query);
        int page = positive(query, "page", 1);
        int pageSize = positive(query, "pageSize", 20);
        if (pageSize > 100) throw invalid("pageSize 最大为 100");
        String sort = query.getOrDefault("sort", "occurrences");
        String order = query.getOrDefault("order", "desc");
        if (!Set.of("occurrences", "affectedDevices", "lastOccurredAt").contains(sort)
                || !Set.of("asc", "desc").contains(order)) throw invalid("sort/order 参数无效");
        return queryService.issues(filter, page, pageSize, sort, order);
    }

    @Override
    public MemoryLeakTrendResponse trend(UUID appId, Map<String, String> query) {
        validateNames(query.keySet(), TREND);
        MemoryLeakQueryFilter filter = filter(appId, query);
        String interval = query.getOrDefault("interval", "hour");
        if (!Set.of("5m", "hour", "day").contains(interval)) throw invalid("interval 参数无效");
        return queryService.trend(filter, interval);
    }

    private MemoryLeakQueryFilter filter(UUID appId, Map<String, String> query) {
        Instant to = query.containsKey("to") ? instant(query.get("to")) : Instant.now();
        Instant from = query.containsKey("from") ? instant(query.get("from")) : to.minus(24, ChronoUnit.HOURS);
        if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
            throw invalid("时间范围必须为正且不超过 31 天");
        }
        Integer sdk = query.get("sdkInt") == null ? null : integer(query.get("sdkInt"), "sdkInt");
        return new MemoryLeakQueryFilter(appId, from, to, query.get("appVersion"), query.get("deviceModel"),
                query.get("processName"), query.get("scene"), query.get("manufacturer"), sdk,
                query.get("dumpReason"), query.get("anonymousDeviceId"), query.get("signature"), query.get("keyword"));
    }

    private Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException ex) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(value));
            } catch (RuntimeException ignored) {
                throw invalid("from/to 必须为 ISO-8601 或 Unix 毫秒");
            }
        }
    }

    private int integer(String value, String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalid(field + " 必须是非负整数");
        }
    }

    private int positive(Map<String, String> query, String field, int defaultValue) {
        int value = query.containsKey(field) ? integer(query.get(field), field) : defaultValue;
        if (value < 1) throw invalid(field + " 必须大于 0");
        return value;
    }

    private void validateNames(Set<String> names, Set<String> allowed) {
        for (String name : names) if (!allowed.contains(name)) throw invalid("查询参数不在允许白名单中: " + name);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> values = new HashSet<>(left);
        values.addAll(right);
        return Set.copyOf(values);
    }

    private QueryValidationException invalid(String message) {
        return new QueryValidationException("INVALID_FILTER", message, 400);
    }
}
