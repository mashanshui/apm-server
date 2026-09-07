package com.shanshui.apmserver.jank.internal.persistence;

import com.shanshui.apmserver.jank.api.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.jank.api.FrameSceneSummaryPayload;
import com.shanshui.apmserver.jank.api.JankAnalysis;
import com.shanshui.apmserver.jank.api.JankPayload;
import com.shanshui.apmserver.jank.internal.domain.ForegroundSuspensionSummary;
import com.shanshui.apmserver.jank.internal.domain.FrameSceneSummary;
import com.shanshui.apmserver.jank.internal.domain.JankEvent;
import com.shanshui.apmserver.jank.internal.domain.JankStoredSignal;
import com.shanshui.apmserver.jank.internal.port.JankEventRepository;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.telemetry.api.AppendResult;
import com.shanshui.apmserver.jank.api.JankMetrics;
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

/** Jank 个例、事实、详情及指标事件专属 ClickHouse 写入适配器。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseJankEventRepository implements JankEventRepository {

    private static final DateTimeFormatter CLICKHOUSE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");
    private final ClickHouseHttpClient client;
    private final ObjectMapper objectMapper;
    private final JankMetrics metrics;

    public ClickHouseJankEventRepository(ClickHouseHttpClient client, ObjectMapper objectMapper,
                                         JankMetrics metrics) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public AppendResult append(UUID appId, List<JankStoredSignal> events) {
        Map<String, JankStoredSignal> unique = new LinkedHashMap<>();
        events.forEach(event -> unique.putIfAbsent(event.eventId(), event));
        List<JankStoredSignal> uniqueEvents = List.copyOf(unique.values());
        Set<String> existing = findExistingIds("apm_event_raw", appId, uniqueEvents);
        List<JankStoredSignal> fresh = uniqueEvents.stream().filter(event -> !existing.contains(event.eventId())).toList();
        if (!fresh.isEmpty()) {
            insertRaw(fresh);
            insertDedicated(fresh);
        }
        repairExistingJank(appId, uniqueEvents, existing);
        return new AppendResult(fresh.size(), events.size() - fresh.size());
    }

    @Override
    public List<JankStoredSignal> findAll(UUID appId) {
        String sql = ClickHouseJankQuerySql.selectEvents(new com.shanshui.apmserver.jank.internal.domain.JankQueryFilter(
                appId, Instant.EPOCH, Instant.parse("9999-12-31T23:59:59Z"),
                null, null, null, null, null, null, null, null, 10_000, null, 10_000));
        return new ArrayList<>(parseJankRows(client.execute(sql)));
    }

    @Override
    public Optional<JankStoredSignal> findByEventId(UUID appId, String eventId) {
        String sql = "SELECT app_id, package_name, event_id, event_time, received_time, schema_version, session_id, "
                + "anonymous_device_id, app_version, version_code, build_id, channel, environment, os_version, "
                + "device_model, network_type, fingerprint, fingerprint_version, jank_payload_json, jank_analysis_json "
                + "FROM apm_jank_event FINAL WHERE app_id = '" + escape(appId.toString()) + "' AND event_id = '"
                + escape(eventId) + "' LIMIT 1 FORMAT JSONEachRow";
        return parseJankRows(client.execute(sql)).stream().map(JankStoredSignal.class::cast).findFirst();
    }

    private void insertDedicated(List<JankStoredSignal> events) {
        List<JankEvent> janks = events.stream().filter(JankEvent.class::isInstance).map(JankEvent.class::cast).toList();
        if (!janks.isEmpty()) {
            insertJankFacts(janks);
            try { insertJankDetails(janks); }
            catch (EventStoreUnavailableException ex) { metrics.jankFactDetailInconsistency(); throw ex; }
        }
        List<FrameSceneSummary> frames = events.stream().filter(FrameSceneSummary.class::isInstance)
                .map(FrameSceneSummary.class::cast).toList();
        if (!frames.isEmpty()) insertFrameSummaries(frames);
        List<ForegroundSuspensionSummary> suspensions = events.stream()
                .filter(ForegroundSuspensionSummary.class::isInstance).map(ForegroundSuspensionSummary.class::cast).toList();
        if (!suspensions.isEmpty()) insertSuspensionSegments(suspensions);
    }

    private void repairExistingJank(UUID appId, List<JankStoredSignal> events, Set<String> existingRawIds) {
        List<JankEvent> existingJank = events.stream().filter(JankEvent.class::isInstance).map(JankEvent.class::cast)
                .filter(event -> existingRawIds.contains(event.eventId())).toList();
        if (existingJank.isEmpty()) return;
        Set<String> facts = findExistingIds("apm_jank_event", appId, new ArrayList<>(existingJank));
        List<JankEvent> missingFacts = existingJank.stream().filter(event -> !facts.contains(event.eventId())).toList();
        if (!missingFacts.isEmpty()) insertJankFacts(missingFacts);
        Set<String> details = findExistingIds("apm_jank_detail", appId, new ArrayList<>(existingJank));
        List<JankEvent> missingDetails = existingJank.stream().filter(event -> !details.contains(event.eventId())).toList();
        if (!missingDetails.isEmpty()) {
            try { insertJankDetails(missingDetails); }
            catch (EventStoreUnavailableException ex) { metrics.jankFactDetailInconsistency(); throw ex; }
        }
    }

    private Set<String> findExistingIds(String table, UUID appId, List<? extends JankStoredSignal> events) {
        if (events.isEmpty()) return Set.of();
        String ids = events.stream().map(JankStoredSignal::eventId).distinct()
                .map(id -> "'" + escape(id) + "'").reduce((left, right) -> left + "," + right).orElse("");
        String sql = "SELECT event_id FROM " + table + " FINAL WHERE app_id = '" + escape(appId.toString())
                + "' AND event_id IN (" + ids + ") GROUP BY event_id FORMAT JSONEachRow";
        Set<String> existing = new HashSet<>();
        rows(client.execute(sql)).forEach(row -> existing.add(row.path("event_id").asText()));
        return existing;
    }

    private void insertRaw(List<JankStoredSignal> events) {
        StringBuilder body = new StringBuilder();
        for (JankStoredSignal event : events) {
            Map<String, Object> row = commonRow(event);
            row.put("duration_ms", null);
            row.put("status", "");
            row.put("measurements", event.measurements());
            row.put("attributes", event.attributes());
            row.put("crash_kind", "");
            row.put("crash_fatal", 0);
            row.put("crash_exception_type", "");
            row.put("crash_fingerprint", valueOrEmpty(event.crashFingerprint()));
            row.put("fingerprint_version", valueOrEmpty(event.fingerprintVersion()));
            row.put("symbolication_status", valueOrEmpty(event.symbolicationStatus()));
            body.append(write(row)).append('\n');
        }
        client.execute("INSERT INTO apm_event_raw FORMAT JSONEachRow", body.toString());
    }

    private Map<String, Object> commonRow(JankStoredSignal event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("app_id", event.appId().toString());
        row.put("package_name", event.packageName());
        row.put("event_id", event.eventId());
        row.put("event_type", event.eventType());
        row.put("event_time", formatTime(event.occurredAt()));
        row.put("received_time", formatTime(event.receivedAt()));
        row.put("schema_version", event.schemaVersion());
        row.put("session_id", event.sessionId());
        row.put("anonymous_device_id", event.anonymousDeviceId());
        row.put("app_version", event.appVersion());
        row.put("version_code", event.versionCode());
        row.put("build_id", event.buildId());
        row.put("channel", event.channel());
        row.put("environment", event.environment());
        row.put("os_version", event.osVersion());
        row.put("device_model", event.deviceModel());
        row.put("network_type", event.networkType());
        return row;
    }

    private void insertJankFacts(List<JankEvent> events) {
        StringBuilder body = new StringBuilder();
        for (JankEvent event : events) {
            JankPayload payload = event.payload(); JankAnalysis analysis = event.analysis();
            Map<String, Object> row = commonRow(event);
            row.put("scene", payload.scene()); row.put("algorithm_version", payload.algorithmVersion());
            row.put("message_duration_ns", payload.messageDurationNs()); row.put("threshold_ns", payload.thresholdNs());
            row.put("sampling_interval_ns", payload.samplingIntervalNs());
            row.put("estimated_duration_ns", analysis.estimatedDurationNs());
            row.put("estimated_unattributed_duration_ns", analysis.estimatedUnattributedDurationNs());
            row.put("covered_duration_ns", analysis.coveredDurationNs()); row.put("uncovered_duration_ns", analysis.uncoveredDurationNs());
            row.put("expected_sample_count", analysis.expectedSampleCount()); row.put("parsed_sample_count", analysis.parsedSampleCount());
            row.put("missing_sample_count", analysis.missingSampleCount()); row.put("fingerprint", valueOrEmpty(event.fingerprint()));
            row.put("fingerprint_version", valueOrEmpty(event.fingerprintVersion()));
            row.put("jank_payload_json", write(payload)); row.put("jank_analysis_json", write(analysis));
            body.append(write(row)).append('\n');
        }
        client.execute("INSERT INTO apm_jank_event FORMAT JSONEachRow", body.toString());
    }

    private void insertJankDetails(List<JankEvent> events) {
        StringBuilder body = new StringBuilder();
        for (JankEvent event : events) {
            JankAnalysis analysis = event.analysis(); Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", event.appId().toString()); row.put("event_id", event.eventId());
            row.put("event_time", formatTime(event.occurredAt())); row.put("received_time", formatTime(event.receivedAt()));
            row.put("fingerprint", valueOrEmpty(event.fingerprint())); row.put("fingerprint_version", valueOrEmpty(event.fingerprintVersion()));
            row.put("stack_dictionary_json", write(analysis.stackDictionary())); row.put("samples_json", write(analysis.sampleSlices()));
            row.put("call_tree_json", write(analysis.callTree()));
            row.put("evidence_json", write(Map.of("exactMessageDurationNs", analysis.exactMessageDurationNs(),
                    "estimatedDurationNs", analysis.estimatedDurationNs(), "estimatedUnattributedDurationNs", analysis.estimatedUnattributedDurationNs(),
                    "coveredDurationNs", analysis.coveredDurationNs(), "uncoveredDurationNs", analysis.uncoveredDurationNs(),
                    "algorithmVersion", analysis.algorithmVersion(), "warnings", analysis.warnings())));
            body.append(write(row)).append('\n');
        }
        client.execute("INSERT INTO apm_jank_detail FORMAT JSONEachRow", body.toString());
    }

    private void insertFrameSummaries(List<FrameSceneSummary> events) {
        StringBuilder body = new StringBuilder();
        for (FrameSceneSummary event : events) {
            FrameSceneSummaryPayload payload = event.payload(); Map<String, Object> row = commonRow(event);
            row.put("scene", payload.scene()); row.put("algorithm_version", payload.algorithmVersion());
            row.put("active_duration_ms", payload.activeDurationMs()); row.put("ui_refresh_frame_count", payload.uiRefreshFrameCount());
            row.put("refresh_rate_hz", payload.refreshRateHz()); row.put("normalized_fps60", payload.normalizedFps60());
            row.put("frame_duration_histogram_json", write(payload.frameDurationHistogram())); body.append(write(row)).append('\n');
        }
        client.execute("INSERT INTO apm_frame_scene_summary FORMAT JSONEachRow", body.toString());
    }

    private void insertSuspensionSegments(List<ForegroundSuspensionSummary> events) {
        StringBuilder body = new StringBuilder();
        for (ForegroundSuspensionSummary event : events) {
            ForegroundSuspensionSummaryPayload payload = event.payload(); Map<String, Object> row = commonRow(event);
            row.put("algorithm_version", payload.algorithmVersion()); row.put("foreground_duration_ms", payload.foregroundDurationMs());
            row.put("suspension_duration_ms", payload.suspensionDurationMs()); row.put("suspension_count", payload.suspensionCount());
            row.put("threshold_ms", payload.thresholdMs()); body.append(write(row)).append('\n');
        }
        client.execute("INSERT INTO apm_device_suspension_segment FORMAT JSONEachRow", body.toString());
    }

    List<JankEvent> parseJankRows(String response) {
        List<JankEvent> events = new ArrayList<>();
        for (JsonNode row : rows(response)) {
            try {
                JankPayload payload = objectMapper.readValue(text(row, "jank_payload_json"), JankPayload.class);
                JankAnalysis analysis = objectMapper.readValue(text(row, "jank_analysis_json"), JankAnalysis.class);
                EventMetadata metadata = new EventMetadata(UUID.fromString(text(row, "app_id")), text(row, "package_name"),
                        text(row, "event_id"), "jank", parseTime(text(row, "event_time")), parseTime(text(row, "received_time")),
                        row.path("schema_version").asInt(), text(row, "session_id"), text(row, "anonymous_device_id"),
                        text(row, "app_version"), row.path("version_code").asInt(), text(row, "build_id"),
                        text(row, "environment"), text(row, "channel"), text(row, "os_version"), text(row, "device_model"),
                        text(row, "network_type"), Map.of(), Map.of());
                events.add(new JankEvent(metadata, emptyToNull(text(row, "fingerprint")),
                        emptyToNull(text(row, "fingerprint_version")), "raw_only", payload, analysis));
            } catch (RuntimeException ex) { throw new EventStoreUnavailableException(); }
        }
        return List.copyOf(events);
    }

    private List<JsonNode> rows(String response) { List<JsonNode> rows = new ArrayList<>(); if (response == null || response.isBlank()) return rows; for (String line : response.split("\\R")) if (!line.isBlank()) rows.add(objectMapper.readTree(line)); return rows; }
    private String write(Object value) { try { return objectMapper.writeValueAsString(value); } catch (RuntimeException ex) { throw new EventStoreUnavailableException(); } }
    private String text(JsonNode row, String name) { JsonNode value = row.get(name); return value == null || value.isNull() ? null : value.asText(); }
    private Instant parseTime(String value) { return value.contains("T") ? Instant.parse(value.endsWith("Z") ? value : value + "Z") : LocalDateTime.parse(value, CLICKHOUSE_TIME).toInstant(ZoneOffset.UTC); }
    private String formatTime(Instant value) { return CLICKHOUSE_TIME.format(LocalDateTime.ofInstant(value, ZoneOffset.UTC)); }
    private String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'"); }
    private String valueOrEmpty(String value) { return value == null ? "" : value; }
    private String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
