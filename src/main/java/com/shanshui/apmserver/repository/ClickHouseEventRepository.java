package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.config.ClickHouseProperties;
import com.shanshui.apmserver.domain.AppendResult;
import com.shanshui.apmserver.domain.CrashPayload;
import com.shanshui.apmserver.domain.StoredEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseEventRepository implements EventRepository {

    private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    private final ClickHouseProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ClickHouseEventRepository(ClickHouseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public AppendResult append(String projectId, List<StoredEvent> events) {
        Set<String> existing = findExistingIds(projectId, events);
        List<StoredEvent> newEvents = events.stream()
                .filter(event -> !existing.contains(event.eventId()))
                .toList();
        if (newEvents.isEmpty()) {
            return new AppendResult(0, events.size());
        }
        insertRaw(newEvents);
        List<StoredEvent> crashEvents = newEvents.stream().filter(StoredEvent::isCrash).toList();
        if (!crashEvents.isEmpty()) {
            insertDetails(crashEvents);
        }
        return new AppendResult(newEvents.size(), events.size() - newEvents.size());
    }

    @Override
    public List<StoredEvent> findAll(String projectId) {
        String query = "SELECT project_id, app_id, event_id, event_type, event_time, received_time, "
                + "schema_version, app_version, version_code, build_id, channel, environment, session_id, "
                + "anonymous_device_id, os_version, device_model, network_type, crash_kind, crash_fatal, "
                + "crash_exception_type, crash_fingerprint, fingerprint_version, symbolication_status, "
                + "ifNull(d.raw_stack_json, '') AS raw_crash_json "
                + "FROM apm_event_raw AS r FINAL LEFT ANY JOIN apm_crash_detail AS d FINAL "
                + "ON d.project_id = r.project_id AND d.event_id = r.event_id "
                + "WHERE r.project_id = '" + escape(projectId) + "' "
                + "ORDER BY event_time, event_id FORMAT JSONEachRow";
        return parseRows(execute(query));
    }

    @Override
    public Optional<StoredEvent> findByEventId(String projectId, String eventId) {
        String query = "SELECT project_id, app_id, event_id, event_type, event_time, received_time, "
                + "schema_version, app_version, version_code, build_id, channel, environment, session_id, "
                + "anonymous_device_id, os_version, device_model, network_type, crash_kind, crash_fatal, "
                + "crash_exception_type, crash_fingerprint, fingerprint_version, symbolication_status, "
                + "ifNull(d.raw_stack_json, '') AS raw_crash_json "
                + "FROM apm_event_raw AS r FINAL LEFT ANY JOIN apm_crash_detail AS d FINAL "
                + "ON d.project_id = r.project_id AND d.event_id = r.event_id "
                + "WHERE r.project_id = '" + escape(projectId)
                + "' AND r.event_id = '" + escape(eventId) + "' LIMIT 1 FORMAT JSONEachRow";
        return parseRows(execute(query)).stream().findFirst();
    }

    private Set<String> findExistingIds(String projectId, List<StoredEvent> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        String ids = events.stream().map(StoredEvent::eventId)
                .distinct().map(id -> "'" + escape(id) + "'").reduce((left, right) -> left + "," + right).orElse("");
        String query = "SELECT event_id FROM apm_event_raw FINAL WHERE project_id = '" + escape(projectId)
                + "' AND event_id IN (" + ids + ") GROUP BY event_id FORMAT JSONEachRow";
        Set<String> existing = new HashSet<>();
        for (JsonNode row : rows(execute(query))) {
            existing.add(row.path("event_id").asText());
        }
        return existing;
    }

    private void insertRaw(List<StoredEvent> events) {
        StringBuilder body = new StringBuilder();
        for (StoredEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project_id", event.projectId());
            row.put("app_id", event.appId());
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
            row.put("crash_kind", valueOrEmpty(event.crashKind()));
            row.put("crash_fatal", Boolean.TRUE.equals(event.crashFatal()) ? 1 : 0);
            row.put("crash_exception_type", valueOrEmpty(event.crashExceptionType()));
            row.put("crash_fingerprint", valueOrEmpty(event.crashFingerprint()));
            row.put("fingerprint_version", valueOrEmpty(event.fingerprintVersion()));
            row.put("symbolication_status", valueOrEmpty(event.symbolicationStatus()));
            body.append(write(row)).append('\n');
        }
        execute("INSERT INTO apm_event_raw FORMAT JSONEachRow", body.toString());
    }

    private void insertDetails(List<StoredEvent> events) {
        StringBuilder body = new StringBuilder();
        for (StoredEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("project_id", event.projectId());
            row.put("event_id", event.eventId());
            row.put("event_time", formatTime(event.occurredAt()));
            row.put("received_time", formatTime(event.receivedAt()));
            row.put("build_id", event.buildId());
            row.put("crash_fingerprint", event.crashFingerprint());
            row.put("fingerprint_version", event.fingerprintVersion());
            row.put("symbolication_status", event.symbolicationStatus());
            String crashJson = write(event.crash());
            row.put("throwable_chain_json", crashJson);
            row.put("raw_stack_json", crashJson);
            row.put("symbolicated_stack_json", "");
            row.put("symbolicated_fingerprint", "");
            body.append(write(row)).append('\n');
        }
        execute("INSERT INTO apm_crash_detail FORMAT JSONEachRow", body.toString());
    }

    private List<StoredEvent> parseRows(String response) {
        List<StoredEvent> events = new ArrayList<>();
        for (JsonNode row : rows(response)) {
            try {
                String rawCrashJson = text(row, "raw_crash_json");
                CrashPayload crash = rawCrashJson == null || rawCrashJson.isBlank()
                        ? null : objectMapper.readValue(rawCrashJson, CrashPayload.class);
                events.add(new StoredEvent(
                        text(row, "project_id"), text(row, "app_id"), text(row, "event_id"), text(row, "event_type"),
                        parseTime(text(row, "event_time")), parseTime(text(row, "received_time")),
                        row.path("schema_version").asInt(), text(row, "session_id"), text(row, "anonymous_device_id"),
                        text(row, "app_version"), row.path("version_code").asInt(), text(row, "build_id"),
                        text(row, "environment"), text(row, "channel"), text(row, "os_version"),
                        text(row, "device_model"), text(row, "network_type"), Map.of(), Map.of(),
                        emptyToNull(text(row, "crash_kind")), row.path("crash_fatal").asInt() == 1,
                        emptyToNull(text(row, "crash_exception_type")), emptyToNull(text(row, "crash_fingerprint")),
                        emptyToNull(text(row, "fingerprint_version")), emptyToNull(text(row, "symbolication_status")), crash));
            } catch (RuntimeException ex) {
                throw new EventStoreUnavailableException();
            }
        }
        return List.copyOf(events);
    }

    private List<JsonNode> rows(String response) {
        List<JsonNode> rows = new ArrayList<>();
        if (response == null || response.isBlank()) {
            return rows;
        }
        for (String line : response.split("\\R")) {
            if (!line.isBlank()) {
                rows.add(objectMapper.readTree(line));
            }
        }
        return rows;
    }

    private String execute(String query) {
        return execute(query, "");
    }

    private String execute(String query, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getUrl() + "?database=" + encode(properties.getDatabase())))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain; charset=utf-8");
            if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
                String credentials = properties.getUsername() + ":" + properties.getPassword();
                builder.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(
                    body.isBlank() ? query : query + "\n" + body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 500 || response.statusCode() == 429) {
                throw new EventStoreUnavailableException();
            }
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("ClickHouse 请求被拒绝，状态码=" + response.statusCode());
            }
            return response.body();
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new EventStoreUnavailableException();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            throw new EventStoreUnavailableException();
        }
    }

    private String text(JsonNode row, String name) {
        JsonNode value = row.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Instant parseTime(String value) {
        if (value == null) {
            return Instant.EPOCH;
        }
        if (value.contains("T")) {
            return Instant.parse(value.endsWith("Z") ? value : value + "Z");
        }
        return LocalDateTime.parse(value, CLICKHOUSE_TIME).toInstant(ZoneOffset.UTC);
    }

    private String formatTime(Instant value) {
        return CLICKHOUSE_TIME.format(LocalDateTime.ofInstant(value, ZoneOffset.UTC));
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
