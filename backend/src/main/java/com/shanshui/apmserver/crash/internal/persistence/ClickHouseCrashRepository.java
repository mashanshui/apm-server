package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.internal.domain.AppStartEvent;
import com.shanshui.apmserver.crash.internal.domain.CrashEvent;
import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.crash.internal.port.CrashQueryPort;
import com.shanshui.apmserver.crash.internal.port.CrashWritePort;
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

/** Crash/app_start 专属 ClickHouse 写入与查询适配器。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseCrashRepository implements CrashWritePort, CrashQueryPort {

    private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");
    private final ClickHouseHttpClient client;
    private final ObjectMapper objectMapper;

    public ClickHouseCrashRepository(ClickHouseHttpClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public AppendResult append(UUID appId, List<CrashStoredSignal> events) {
        Map<String, CrashStoredSignal> unique = new LinkedHashMap<>();
        events.forEach(event -> unique.putIfAbsent(event.eventId(), event));
        Set<String> existing = findExistingIds(appId, List.copyOf(unique.values()));
        List<CrashStoredSignal> fresh = unique.values().stream()
                .filter(event -> !existing.contains(event.eventId())).toList();
        if (!fresh.isEmpty()) {
            insertRaw(fresh);
            List<CrashEvent> crashes = fresh.stream().filter(CrashEvent.class::isInstance)
                    .map(CrashEvent.class::cast).toList();
            if (!crashes.isEmpty()) {
                insertDetails(crashes);
            }
        }
        return new AppendResult(fresh.size(), events.size() - fresh.size());
    }

    @Override
    public List<CrashStoredSignal> findAll(UUID appId) {
        String where = "r.app_id = '" + escape(appId.toString())
                + "' AND r.event_type IN ('crash','app_start')";
        return parseRows(client.execute(selectSql(where) + " ORDER BY r.event_time, r.event_id FORMAT JSONEachRow"));
    }

    @Override
    public Optional<CrashStoredSignal> findByEventId(UUID appId, String eventId) {
        String where = "r.app_id = '" + escape(appId.toString()) + "' AND r.event_id = '"
                + escape(eventId) + "' AND r.event_type IN ('crash','app_start')";
        return parseRows(client.execute(selectSql(where) + " LIMIT 1 FORMAT JSONEachRow")).stream().findFirst();
    }

    private String selectSql(String where) {
        return "SELECT r.app_id AS app_id, r.package_name AS package_name, r.event_id AS event_id, "
                + "r.event_type AS event_type, r.event_time AS event_time, r.received_time AS received_time, "
                + "r.schema_version AS schema_version, r.app_version AS app_version, r.version_code AS version_code, "
                + "r.build_id AS build_id, r.channel AS channel, r.environment AS environment, r.session_id AS session_id, "
                + "r.anonymous_device_id AS anonymous_device_id, r.os_version AS os_version, "
                + "r.device_model AS device_model, r.network_type AS network_type, r.crash_kind AS crash_kind, "
                + "r.crash_fatal AS crash_fatal, r.crash_exception_type AS crash_exception_type, "
                + "r.crash_fingerprint AS crash_fingerprint, r.fingerprint_version AS fingerprint_version, "
                + "r.symbolication_status AS symbolication_status, ifNull(d.raw_stack_json, '') AS raw_crash_json "
                + "FROM apm_event_raw AS r FINAL LEFT ANY JOIN apm_crash_detail AS d FINAL "
                + "ON d.app_id = r.app_id AND d.event_id = r.event_id WHERE " + where;
    }

    private Set<String> findExistingIds(UUID appId, List<CrashStoredSignal> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        String ids = events.stream().map(CrashStoredSignal::eventId).distinct()
                .map(id -> "'" + escape(id) + "'").reduce((left, right) -> left + "," + right).orElse("");
        String sql = "SELECT event_id FROM apm_event_raw FINAL WHERE app_id = '" + escape(appId.toString())
                + "' AND event_id IN (" + ids + ") GROUP BY event_id FORMAT JSONEachRow";
        Set<String> existing = new HashSet<>();
        rows(client.execute(sql)).forEach(row -> existing.add(row.path("event_id").asText()));
        return existing;
    }

    private void insertRaw(List<CrashStoredSignal> events) {
        StringBuilder body = new StringBuilder();
        for (CrashStoredSignal event : events) {
            Map<String, Object> row = rawRow(event);
            row.put("crash_kind", valueOrEmpty(event.crashKind()));
            row.put("crash_fatal", Boolean.TRUE.equals(event.crashFatal()) ? 1 : 0);
            row.put("crash_exception_type", valueOrEmpty(event.crashExceptionType()));
            row.put("crash_fingerprint", valueOrEmpty(event.crashFingerprint()));
            row.put("fingerprint_version", valueOrEmpty(event.fingerprintVersion()));
            row.put("symbolication_status", valueOrEmpty(event.symbolicationStatus()));
            body.append(write(row)).append('\n');
        }
        client.execute("INSERT INTO apm_event_raw FORMAT JSONEachRow", body.toString());
    }

    private Map<String, Object> rawRow(CrashStoredSignal event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("app_id", event.appId().toString());
        row.put("package_name", event.packageName());
        row.put("event_id", event.eventId());
        row.put("event_type", event.eventType());
        row.put("event_time", formatTime(event.occurredAt()));
        row.put("received_time", formatTime(event.receivedAt()));
        row.put("schema_version", event.schemaVersion());
        row.put("app_version", event.appVersion());
        row.put("version_code", event.versionCode());
        row.put("build_id", event.buildId());
        row.put("channel", event.channel());
        row.put("environment", event.environment());
        row.put("session_id", event.sessionId());
        row.put("anonymous_device_id", event.anonymousDeviceId());
        row.put("os_version", event.osVersion());
        row.put("device_model", event.deviceModel());
        row.put("network_type", event.networkType());
        row.put("duration_ms", null);
        row.put("status", "");
        row.put("measurements", event.measurements());
        row.put("attributes", event.attributes());
        return row;
    }

    private void insertDetails(List<CrashEvent> events) {
        StringBuilder body = new StringBuilder();
        for (CrashEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", event.appId().toString());
            row.put("event_id", event.eventId());
            row.put("event_time", formatTime(event.occurredAt()));
            row.put("received_time", formatTime(event.receivedAt()));
            row.put("build_id", event.buildId());
            row.put("crash_fingerprint", event.fingerprint());
            row.put("fingerprint_version", event.fingerprintVersion());
            row.put("symbolication_status", event.symbolicationStatus());
            String crashJson = write(event.payload());
            row.put("throwable_chain_json", crashJson);
            row.put("raw_stack_json", crashJson);
            row.put("symbolicated_stack_json", "");
            row.put("symbolicated_fingerprint", "");
            body.append(write(row)).append('\n');
        }
        client.execute("INSERT INTO apm_crash_detail FORMAT JSONEachRow", body.toString());
    }

    private List<CrashStoredSignal> parseRows(String response) {
        List<CrashStoredSignal> events = new ArrayList<>();
        for (JsonNode row : rows(response)) {
            try {
                EventMetadata metadata = metadata(row);
                if ("app_start".equals(metadata.eventType())) {
                    events.add(new AppStartEvent(metadata));
                    continue;
                }
                String raw = text(row, "raw_crash_json");
                CrashPayload payload = raw == null || raw.isBlank() ? null : objectMapper.readValue(raw, CrashPayload.class);
                events.add(new CrashEvent(metadata, emptyToNull(text(row, "crash_kind")),
                        row.path("crash_fatal").asInt() == 1, emptyToNull(text(row, "crash_exception_type")),
                        emptyToNull(text(row, "crash_fingerprint")), emptyToNull(text(row, "fingerprint_version")),
                        emptyToNull(text(row, "symbolication_status")), payload));
            } catch (RuntimeException ex) {
                throw new EventStoreUnavailableException();
            }
        }
        return List.copyOf(events);
    }

    private EventMetadata metadata(JsonNode row) {
        return new EventMetadata(UUID.fromString(text(row, "app_id")), text(row, "package_name"),
                text(row, "event_id"), text(row, "event_type"), parseTime(text(row, "event_time")),
                parseTime(text(row, "received_time")), row.path("schema_version").asInt(), text(row, "session_id"),
                text(row, "anonymous_device_id"), text(row, "app_version"), row.path("version_code").asInt(),
                text(row, "build_id"), text(row, "environment"), text(row, "channel"), text(row, "os_version"),
                text(row, "device_model"), text(row, "network_type"), Map.of(), Map.of());
    }

    private List<JsonNode> rows(String response) {
        List<JsonNode> rows = new ArrayList<>();
        if (response == null || response.isBlank()) return rows;
        for (String line : response.split("\\R")) if (!line.isBlank()) rows.add(objectMapper.readTree(line));
        return rows;
    }

    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (RuntimeException ex) { throw new EventStoreUnavailableException(); }
    }
    private String text(JsonNode row, String name) { JsonNode value = row.get(name); return value == null || value.isNull() ? null : value.asText(); }
    private Instant parseTime(String value) { return value.contains("T") ? Instant.parse(value.endsWith("Z") ? value : value + "Z") : LocalDateTime.parse(value, CLICKHOUSE_TIME).toInstant(ZoneOffset.UTC); }
    private String formatTime(Instant value) { return CLICKHOUSE_TIME.format(LocalDateTime.ofInstant(value, ZoneOffset.UTC)); }
    private String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'"); }
    private String valueOrEmpty(String value) { return value == null ? "" : value; }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
