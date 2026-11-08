package com.shanshui.apmserver.memory.internal.persistence;

import com.shanshui.apmserver.memory.api.MemoryMetricStats;
import com.shanshui.apmserver.memory.api.MemoryPeakSummaryPayload;
import com.shanshui.apmserver.memory.internal.domain.MemoryEvent;
import com.shanshui.apmserver.memory.internal.domain.MemoryQueryFilter;
import com.shanshui.apmserver.memory.internal.domain.MemoryTrendAggregate;
import com.shanshui.apmserver.memory.internal.port.MemoryEventRepository;
import com.shanshui.apmserver.memory.internal.port.MemoryMetricsRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.telemetry.api.AppendResult;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ClickHouse 内存采样表适配器，FINAL 查询保证跨接收日期的最终去重视图。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseMemoryMetricsRepository implements MemoryEventRepository, MemoryMetricsRepository {

    private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    private final ClickHouseHttpClient client;
    private final ObjectMapper objectMapper;
    /** 复用平台查询预算。 */
    private final com.shanshui.apmserver.platform.api.QueryProperties queryProperties;

    public ClickHouseMemoryMetricsRepository(ClickHouseHttpClient client, ObjectMapper objectMapper, com.shanshui.apmserver.platform.api.QueryProperties queryProperties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.queryProperties = queryProperties;
    }

    @Override
    public synchronized AppendResult append(UUID appId, List<MemoryEvent> events) {
        try {
            Map<String, MemoryEvent> unique = new LinkedHashMap<>();
            events.forEach(event -> unique.putIfAbsent(event.eventId(), event));
            List<MemoryEvent> uniqueEvents = List.copyOf(unique.values());
            Set<String> existing = findExistingIds(appId, uniqueEvents);
            List<MemoryEvent> fresh = uniqueEvents.stream().filter(event -> !existing.contains(event.eventId())).toList();
            if (!fresh.isEmpty()) {
                insert(fresh);
            }
            return new AppendResult(fresh.size(), events.size() - fresh.size());
        } catch (EventStoreUnavailableException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // 数据库协议/DDL等写入错误均不能向客户端声称已持久化。
            throw new EventStoreUnavailableException("运行峰值存储暂时不可用", failure);
        }
    }

    @Override
    public List<MemoryEvent> findAll(UUID appId) {
        String sql = selectAll(appId);
        return parseEvents(client.execute(sql));
    }

    @Override
    public Optional<MemoryEvent> findByEventId(UUID appId, String eventId) {
        String sql = "SELECT * FROM apm_memory_peak_summary FINAL WHERE app_id = '" + escape(appId.toString())
                + "' AND event_id = '" + escape(eventId) + "' LIMIT 1 FORMAT JSONEachRow";
        return parseEvents(client.execute(sql)).stream().findFirst();
    }

    @Override
    public MemoryMetricStats queryPss(MemoryQueryFilter filter) {
        return querySummary(filter, "pss");
    }

    @Override
    public MemoryMetricStats queryVss(MemoryQueryFilter filter) {
        return querySummary(filter, "vss");
    }

    @Override
    public MemoryMetricStats queryJavaHeap(MemoryQueryFilter filter) {
        return querySummary(filter, "java_heap");
    }

    @Override
    public List<MemoryTrendAggregate> queryTrend(MemoryQueryFilter filter, String metric, String interval) {
        List<MemoryTrendAggregate> result = new ArrayList<>();
        for (JsonNode row : rows(client.executeQuery(ClickHouseMemoryMetricsQuerySql.selectTrend(filter, metric, interval), budget(filter)))) {
            long count = row.path("run_count").asLong();
            MemoryMetricStats stats = new MemoryMetricStats(count, number(row, "average_bytes", count),
                    number(row, "p50_bytes", count), number(row, "p90_bytes", count),
                    number(row, "p95_bytes", count), number(row, "p99_bytes", count),
                    count == 0 ? "no_data" : "ok");
            result.add(new MemoryTrendAggregate(parseTime(row, "bucket_start"), parseTime(row, "bucket_end"), stats));
        }
        return List.copyOf(result);
    }

    @Override
    public String dataSource() {
        return "clickhouse";
    }

    private MemoryMetricStats querySummary(MemoryQueryFilter filter, String metric) {
        JsonNode row = rows(client.executeQuery(ClickHouseMemoryMetricsQuerySql.selectSummary(filter), budget(filter))).stream()
                .findFirst().orElse(null);
        if (row == null) {
            return new MemoryMetricStats(0, null, null, null, null, null, "no_data");
        }
        String prefix = metric;
        long count = row.path(prefix + "_run_count").asLong();
        return new MemoryMetricStats(count, number(row, prefix + "_average_bytes", count),
                number(row, prefix + "_p50_bytes", count), number(row, prefix + "_p90_bytes", count),
                number(row, prefix + "_p95_bytes", count), number(row, prefix + "_p99_bytes", count),
                count == 0 ? "no_data" : "ok");
    }

    /** 将本次超时和平台资源上限一起传给有界查询客户端。 */
    private com.shanshui.apmserver.platform.api.QueryBudget budget(com.shanshui.apmserver.memory.internal.domain.MemoryQueryFilter filter) {
        return new com.shanshui.apmserver.platform.api.QueryBudget(filter.timeoutMs(), queryProperties.getMaxRowsToRead(),
                queryProperties.getMaxBytesToRead(), queryProperties.getMaxMemoryUsage(), queryProperties.getMaxResponseBytes());
    }

    private Set<String> findExistingIds(UUID appId, List<MemoryEvent> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        String ids = events.stream().map(MemoryEvent::eventId).distinct()
                .map(id -> "'" + escape(id) + "'").reduce((left, right) -> left + "," + right).orElse("");
        String sql = "SELECT event_id FROM apm_memory_peak_summary FINAL WHERE app_id = '" + escape(appId.toString())
                + "' AND event_id IN (" + ids + ") GROUP BY event_id FORMAT JSONEachRow";
        Set<String> existing = new HashSet<>();
        rows(client.execute(sql)).forEach(row -> {
            JsonNode id = row.get("event_id");
            if (id != null && !id.isNull()) {
                existing.add(id.asText());
            }
        });
        return existing;
    }

    private void insert(List<MemoryEvent> events) {
        StringBuilder body = new StringBuilder();
        for (MemoryEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", event.appId().toString());
            row.put("package_name", event.packageName());
            row.put("event_id", event.eventId());
            row.put("event_time", formatTime(event.occurredAt()));
            row.put("received_time", formatTime(event.metadata().receivedAt()));
            row.put("schema_version", event.metadata().schemaVersion());
            row.put("session_id", event.metadata().sessionId());
            row.put("process_id", valueOrEmpty(event.processId()));
            row.put("anonymous_device_id", event.metadata().anonymousDeviceId());
            row.put("app_version", event.appVersion());
            row.put("version_code", event.metadata().versionCode());
            row.put("build_id", event.metadata().buildId());
            row.put("channel", event.metadata().channel());
            row.put("environment", event.metadata().environment());
            row.put("os_version", event.osVersion());
            row.put("device_model", event.deviceModel());
            row.put("network_type", event.metadata().networkType());
            row.put("pss_bytes", event.pssBytes());
            row.put("vss_bytes", event.vssBytes());
            row.put("java_heap_used_bytes", event.javaHeapUsedBytes());
            row.put("process_name", event.processName());
            row.put("collection_started_at", event.payload().collectionStartedAt());
            putPeak(row, "pss", event.payload().pss());
            putPeak(row, "vss", event.payload().vss());
            putPeak(row, "java_heap", event.payload().javaHeap());
            body.append(write(row)).append('\n');
        }
        client.execute("INSERT INTO apm_memory_peak_summary FORMAT JSONEachRow", body.toString());
    }

    private String selectAll(UUID appId) {
        return "SELECT * FROM apm_memory_peak_summary FINAL WHERE app_id = '" + escape(appId.toString())
                + "' ORDER BY event_time, event_id FORMAT JSONEachRow";
    }

    private List<MemoryEvent> parseEvents(String response) {
        List<MemoryEvent> result = new ArrayList<>();
        for (JsonNode row : rows(response)) {
            try {
                EventMetadata metadata = new EventMetadata(UUID.fromString(text(row, "app_id")),
                        text(row, "package_name"), text(row, "event_id"), "memory_peak_summary",
                        parseTime(row, "event_time"), parseTime(row, "received_time"), row.path("schema_version").asInt(),
                         text(row, "session_id"), emptyToNull(text(row, "process_id")), text(row, "anonymous_device_id"), text(row, "app_version"),
                        row.path("version_code").asInt(), text(row, "build_id"), text(row, "environment"),
                        text(row, "channel"), text(row, "os_version"), text(row, "device_model"),
                        text(row, "network_type"), Map.of(), Map.of());
                MemoryPeakSummaryPayload payload = new MemoryPeakSummaryPayload(text(row, "process_name"),
                        nullableLong(row, "collection_started_at"), metadata.occurredAt().toEpochMilli(),
                        readPeak(row, "pss", "pss_bytes"), readPeak(row, "vss", "vss_bytes"),
                        readPeak(row, "java_heap", "java_heap_used_bytes"));
                result.add(new MemoryEvent(metadata, payload));
            } catch (RuntimeException ex) {
                throw new EventStoreUnavailableException("ClickHouse 内存事件响应无效", ex);
            }
        }
        return List.copyOf(result);
    }

    /** 将各项原峰值上下文独立写入列，空指标的所有上下文保持 null。 */
    private void putPeak(Map<String, Object> row, String prefix, com.shanshui.apmserver.memory.api.MemoryPeak peak) {
        row.put(prefix + "_occurred_at", peak == null ? null : peak.occurredAt());
        row.put(prefix + "_foreground", peak == null ? null : (peak.foreground() ? 1 : 0));
        row.put(prefix + "_scene", peak == null ? null : peak.scene());
    }

    /** 从同一指标的列恢复峰值，不能借用其他指标上下文。 */
    private com.shanshui.apmserver.memory.api.MemoryPeak readPeak(JsonNode row, String prefix, String valueColumn) {
        // 缺失字节代表整项指标不存在，不能生成虚假上下文。
        Long bytes = nullableLong(row, valueColumn);
        return bytes == null ? null : new com.shanshui.apmserver.memory.api.MemoryPeak(bytes,
                nullableLong(row, prefix + "_occurred_at"), row.path(prefix + "_foreground").asInt() != 0,
                text(row, prefix + "_scene"));
    }

    private List<JsonNode> rows(String response) {
        return client.decodeJsonEachRow(response, objectMapper);
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            throw new EventStoreUnavailableException("内存事件序列化失败", ex);
        }
    }

    private Double number(JsonNode row, String field, long count) {
        if (count == 0) {
            return null;
        }
        JsonNode value = row.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        double number = value.asDouble();
        return Double.isFinite(number) ? number : null;
    }

    private Long nullableLong(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Instant parseTime(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.isBlank()) {
            throw new EventStoreUnavailableException("ClickHouse 内存事件缺少时间字段: " + field);
        }
        try {
            return value.indexOf('T') >= 0 ? Instant.parse(value)
                    : LocalDateTime.parse(value, CLICKHOUSE_TIME).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            throw new EventStoreUnavailableException("ClickHouse 内存事件时间格式无效: " + field, ex);
        }
    }

    private String formatTime(Instant value) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC).format(value);
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** 将历史或测试对象中的缺失身份转换为空字符串，适配 ClickHouse String 列。 */
    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 将 ClickHouse 默认空字符串还原为 API 语义中的缺失身份。 */
    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
