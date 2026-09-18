package com.shanshui.apmserver.memory.internal.persistence;

import com.shanshui.apmserver.memory.api.MemoryLeakEventConflictException;
import com.shanshui.apmserver.memory.api.MemoryLeakPathNode;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakPath;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakQueryFilter;
import com.shanshui.apmserver.memory.internal.domain.MemoryLeakReport;
import com.shanshui.apmserver.memory.internal.port.MemoryLeakReportRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** ClickHouse 内存泄漏报告适配器；所有读取均使用 FINAL 视图完成跨重启去重。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseMemoryLeakReportRepository implements MemoryLeakReportRepository {
    private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final ClickHouseHttpClient client;
    private final ObjectMapper objectMapper;

    public ClickHouseMemoryLeakReportRepository(ClickHouseHttpClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void append(MemoryLeakReport report) {
        Optional<MemoryLeakReport> existing = findByEventId(report.appId(), report.eventId());
        if (existing.isPresent()) {
            MemoryLeakReport value = existing.get();
            if (!Objects.equals(value.payloadHash(), report.payloadHash())
                    || !Objects.equals(value.attachmentDigest(), report.attachmentDigest())) {
                throw new MemoryLeakEventConflictException();
            }
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("app_id", report.appId().toString());
        row.put("event_id", report.eventId().toString());
        row.put("event_time", formatTime(report.occurredAt()));
        row.put("received_time", formatTime(report.receivedAt()));
        row.put("package_name", report.packageName());
        row.put("app_version", report.appVersion());
        row.put("version_code", report.versionCode());
        row.put("anonymous_device_id", report.anonymousDeviceId());
        row.put("process_id", valueOrEmpty(report.processId()));
        row.put("process_name", report.processName());
        row.put("session_id", valueOrEmpty(report.sessionId()));
        row.put("build_id", valueOrEmpty(report.buildId()));
        row.put("environment", valueOrEmpty(report.environment()));
        row.put("channel", valueOrEmpty(report.channel()));
        row.put("device_model", valueOrEmpty(report.deviceModel()));
        row.put("scene", valueOrEmpty(report.scene()));
        row.put("manufacturer", valueOrEmpty(report.manufacturer()));
        row.put("sdk_int", report.sdkInt());
        row.put("dump_reason", valueOrEmpty(report.dumpReason()));
        row.put("report_json", write(report.report()));
        row.put("gc_paths_json", write(report.paths()));
        row.put("payload_hash", report.payloadHash());
        row.put("attachment_digest", report.attachmentDigest());
        row.put("attachment_path", valueOrEmpty(report.attachmentPath()));
        row.put("attachment_bytes", report.attachmentBytes());
        client.execute("INSERT INTO apm_memory_report FORMAT JSONEachRow", write(row));
    }

    @Override
    public Optional<MemoryLeakReport> findByEventId(UUID appId, UUID eventId) {
        String sql = "SELECT app_id, event_id, event_time, received_time, package_name, app_version, version_code, "
                + "anonymous_device_id, process_id, process_name, session_id, build_id, environment, channel, device_model, scene, "
                + "manufacturer, sdk_int, dump_reason, report_json, gc_paths_json, payload_hash, attachment_digest, "
                + "attachment_path, attachment_bytes FROM apm_memory_report FINAL WHERE app_id = '"
                + escape(appId.toString()) + "' AND event_id = '" + escape(eventId.toString())
                + "' LIMIT 1 FORMAT JSONEachRow";
        return rows(client.execute(sql)).stream().map(this::parse).findFirst();
    }

    @Override
    public List<MemoryLeakReport> findAll(MemoryLeakQueryFilter filter) {
        StringBuilder where = new StringBuilder("app_id = '").append(escape(filter.appId().toString()))
                .append("' AND event_time >= '").append(formatTime(filter.from()))
                .append("' AND event_time < '").append(formatTime(filter.to())).append("'");
        exact(where, "app_version", filter.appVersion());
        exact(where, "device_model", filter.deviceModel());
        exact(where, "process_name", filter.processName());
        exact(where, "scene", filter.scene());
        exact(where, "manufacturer", filter.manufacturer());
        exact(where, "dump_reason", filter.dumpReason());
        exact(where, "anonymous_device_id", filter.anonymousDeviceId());
        if (filter.sdkInt() != null) where.append(" AND sdk_int = ").append(filter.sdkInt());
        String sql = "SELECT app_id, event_id, event_time, received_time, package_name, app_version, version_code, "
                + "anonymous_device_id, process_id, process_name, session_id, build_id, environment, channel, device_model, scene, "
                + "manufacturer, sdk_int, dump_reason, report_json, gc_paths_json, payload_hash, attachment_digest, "
                + "attachment_path, attachment_bytes FROM apm_memory_report FINAL WHERE " + where
                + " ORDER BY event_time, event_id FORMAT JSONEachRow";
        return rows(client.execute(sql)).stream().map(this::parse).toList();
    }

    @Override
    public Set<String> findAttachmentPaths() {
        String sql = "SELECT attachment_path FROM apm_memory_report FINAL WHERE attachment_path != '' FORMAT JSONEachRow";
        return rows(client.execute(sql)).stream().map(row -> text(row, "attachment_path"))
                .filter(path -> path != null && !path.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String dataSource() {
        return "clickhouse";
    }

    private MemoryLeakReport parse(JsonNode row) {
        try {
            JsonNode reportNode = objectMapper.readTree(required(row, "report_json"));
            List<MemoryLeakPath> paths = parsePaths(objectMapper.readTree(required(row, "gc_paths_json")));
            return new MemoryLeakReport(UUID.fromString(required(row, "app_id")),
                    UUID.fromString(required(row, "event_id")), parseTime(required(row, "event_time")),
                    parseTime(required(row, "received_time")), required(row, "package_name"),
                    required(row, "app_version"), row.path("version_code").asLong(),
                    required(row, "anonymous_device_id"), nullable(row, "process_id"), required(row, "process_name"), nullable(row, "session_id"),
                    nullable(row, "build_id"), nullable(row, "environment"), nullable(row, "channel"),
                    nullable(row, "device_model"), nullable(row, "scene"), nullable(row, "manufacturer"),
                    nullableInteger(row, "sdk_int"), nullable(row, "dump_reason"), reportNode, paths,
                    required(row, "payload_hash"), nullable(row, "attachment_digest"), nullable(row, "attachment_path"),
                    row.path("attachment_bytes").asLong());
        } catch (RuntimeException ex) {
            if (ex instanceof EventStoreUnavailableException) throw ex;
            throw new EventStoreUnavailableException("ClickHouse 内存泄漏报告响应无效", ex);
        }
    }

    private List<MemoryLeakPath> parsePaths(JsonNode node) {
        if (node == null || !node.isArray()) throw new IllegalArgumentException("gc_paths_json 无效");
        List<MemoryLeakPath> paths = new ArrayList<>();
        for (JsonNode item : node) {
            List<MemoryLeakPathNode> chain = new ArrayList<>();
            JsonNode chainNode = item.get("path");
            if (chainNode == null || !chainNode.isArray()) throw new IllegalArgumentException("引用链无效");
            for (JsonNode n : chainNode) {
                chain.add(new MemoryLeakPathNode(required(n, "reference"), required(n, "referenceType"), nullable(n, "declaredClass")));
            }
            paths.add(new MemoryLeakPath(required(item, "signature"), required(item, "gcRoot"),
                    required(item, "leakReason"), item.path("instanceCount").asLong(), List.copyOf(chain), ""));
        }
        return List.copyOf(paths);
    }

    private List<JsonNode> rows(String response) {
        return client.decodeJsonEachRow(response, objectMapper);
    }

    private void exact(StringBuilder where, String column, String value) {
        if (value != null) where.append(" AND ").append(column).append(" = '").append(escape(value)).append("'");
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (RuntimeException ex) { throw new EventStoreUnavailableException("内存泄漏报告序列化失败", ex); }
    }

    private String required(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少字段: " + field);
        return value;
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String nullable(JsonNode row, String field) {
        String value = text(row, field);
        return value == null || value.isBlank() ? null : value;
    }

    private Integer nullableInteger(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private Instant parseTime(String value) {
        try {
            return value.indexOf('T') >= 0 ? Instant.parse(value)
                    : LocalDateTime.parse(value, CLICKHOUSE_TIME).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            throw new EventStoreUnavailableException("ClickHouse 时间字段无效", ex);
        }
    }

    private String formatTime(Instant value) {
        return CLICKHOUSE_TIME.format(LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private String valueOrEmpty(String value) { return value == null ? "" : value; }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
