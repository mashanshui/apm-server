package com.shanshui.apmserver.repository;

import com.shanshui.apmserver.config.ClickHouseProperties;
import com.shanshui.apmserver.domain.AppendResult;
import com.shanshui.apmserver.domain.CrashPayload;
import com.shanshui.apmserver.domain.ForegroundSuspensionSummaryPayload;
import com.shanshui.apmserver.domain.FrameSceneSummaryPayload;
import com.shanshui.apmserver.domain.JankAnalysis;
import com.shanshui.apmserver.domain.JankPayload;
import com.shanshui.apmserver.domain.JankQueryFilter;
import com.shanshui.apmserver.domain.FpsMetricAggregate;
import com.shanshui.apmserver.domain.MetricDimensionAggregate;
import com.shanshui.apmserver.domain.MetricQueryFilter;
import com.shanshui.apmserver.domain.MetricTrendAggregate;
import com.shanshui.apmserver.domain.SuspensionMetricAggregate;
import com.shanshui.apmserver.domain.StoredEvent;
import com.shanshui.apmserver.service.CrashQualityMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final CrashQualityMetrics qualityMetrics;

    @Autowired
    public ClickHouseEventRepository(ClickHouseProperties properties, ObjectMapper objectMapper,
                                     CrashQualityMetrics qualityMetrics) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.qualityMetrics = qualityMetrics;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** 兼容无需 Spring 的仓库单元测试。 */
    public ClickHouseEventRepository(ClickHouseProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    @Override
    public AppendResult append(java.util.UUID appId, List<StoredEvent> events) {
        List<StoredEvent> uniqueEvents = uniqueByEventId(events);
        Set<String> existing = findExistingIds(appId, uniqueEvents);
        List<StoredEvent> newEvents = uniqueEvents.stream()
                .filter(event -> !existing.contains(event.eventId()))
                .toList();
        if (newEvents.isEmpty()) {
            repairExistingJank(appId, uniqueEvents, existing);
            return new AppendResult(0, events.size());
        }
        insertRaw(newEvents);
        List<StoredEvent> crashEvents = newEvents.stream().filter(StoredEvent::isCrash).toList();
        if (!crashEvents.isEmpty()) {
            insertDetails(crashEvents);
        }
        List<StoredEvent> jankEvents = newEvents.stream().filter(StoredEvent::isJank).toList();
        if (!jankEvents.isEmpty()) {
            insertJankFacts(jankEvents);
            try {
                insertJankDetails(jankEvents);
            } catch (EventStoreUnavailableException ex) {
                if (qualityMetrics != null) {
                    qualityMetrics.jankFactDetailInconsistency();
                }
                throw ex;
            }
        }
        List<StoredEvent> frameEvents = newEvents.stream().filter(StoredEvent::isFrameSceneSummary).toList();
        if (!frameEvents.isEmpty()) {
            insertFrameSummaries(frameEvents);
        }
        List<StoredEvent> suspensionEvents = newEvents.stream()
                .filter(StoredEvent::isForegroundSuspensionSummary).toList();
        if (!suspensionEvents.isEmpty()) {
            insertSuspensionSegments(suspensionEvents);
        }
        repairExistingJank(appId, uniqueEvents, existing);
        return new AppendResult(newEvents.size(), events.size() - newEvents.size());
    }

    private List<StoredEvent> uniqueByEventId(List<StoredEvent> events) {
        Map<String, StoredEvent> unique = new LinkedHashMap<>();
        for (StoredEvent event : events) {
            unique.putIfAbsent(event.eventId(), event);
        }
        return List.copyOf(unique.values());
    }

    @Override
    public List<StoredEvent> findAll(java.util.UUID appId) {
        String query = "SELECT r.app_id AS app_id, r.package_name AS package_name, r.event_id AS event_id, "
                + "r.event_type AS event_type, r.event_time AS event_time, r.received_time AS received_time, "
                + "r.schema_version AS schema_version, r.app_version AS app_version, r.version_code AS version_code, "
                + "r.build_id AS build_id, r.channel AS channel, r.environment AS environment, r.session_id AS session_id, "
                + "r.anonymous_device_id AS anonymous_device_id, r.os_version AS os_version, "
                + "r.device_model AS device_model, r.network_type AS network_type, r.crash_kind AS crash_kind, "
                + "r.crash_fatal AS crash_fatal, r.crash_exception_type AS crash_exception_type, "
                + "r.crash_fingerprint AS crash_fingerprint, r.fingerprint_version AS fingerprint_version, "
                + "r.symbolication_status AS symbolication_status, "
                + "ifNull(d.raw_stack_json, '') AS raw_crash_json, ifNull(j.jank_payload_json, '') AS jank_payload_json, "
                + "ifNull(j.jank_analysis_json, '') AS jank_analysis_json "
                + "FROM apm_event_raw AS r FINAL LEFT ANY JOIN apm_crash_detail AS d FINAL "
                + "ON d.app_id = r.app_id AND d.event_id = r.event_id "
                + "LEFT ANY JOIN apm_jank_event AS j FINAL ON j.app_id = r.app_id AND j.event_id = r.event_id "
                + "WHERE r.app_id = '" + escape(appId.toString()) + "' "
                + "ORDER BY r.event_time, r.event_id FORMAT JSONEachRow";
        return parseRows(execute(query));
    }

    @Override
    public Optional<StoredEvent> findByEventId(java.util.UUID appId, String eventId) {
        String query = "SELECT r.app_id AS app_id, r.package_name AS package_name, r.event_id AS event_id, "
                + "r.event_type AS event_type, r.event_time AS event_time, r.received_time AS received_time, "
                + "r.schema_version AS schema_version, r.app_version AS app_version, r.version_code AS version_code, "
                + "r.build_id AS build_id, r.channel AS channel, r.environment AS environment, r.session_id AS session_id, "
                + "r.anonymous_device_id AS anonymous_device_id, r.os_version AS os_version, "
                + "r.device_model AS device_model, r.network_type AS network_type, r.crash_kind AS crash_kind, "
                + "r.crash_fatal AS crash_fatal, r.crash_exception_type AS crash_exception_type, "
                + "r.crash_fingerprint AS crash_fingerprint, r.fingerprint_version AS fingerprint_version, "
                + "r.symbolication_status AS symbolication_status, "
                + "ifNull(d.raw_stack_json, '') AS raw_crash_json, ifNull(j.jank_payload_json, '') AS jank_payload_json, "
                + "ifNull(j.jank_analysis_json, '') AS jank_analysis_json "
                + "FROM apm_event_raw AS r FINAL LEFT ANY JOIN apm_crash_detail AS d FINAL "
                + "ON d.app_id = r.app_id AND d.event_id = r.event_id "
                + "LEFT ANY JOIN apm_jank_event AS j FINAL ON j.app_id = r.app_id AND j.event_id = r.event_id "
                + "WHERE r.app_id = '" + escape(appId.toString())
                + "' AND r.event_id = '" + escape(eventId) + "' LIMIT 1 FORMAT JSONEachRow";
        return parseRows(execute(query)).stream().findFirst();
    }

    /**
     * 独立卡顿聚合仓库可调用的受限事实查询入口。SQL 由白名单构造器生成，包含 FINAL、应用、时间、行数和执行时间约束。
     */
    public List<StoredEvent> findJankEvents(JankQueryFilter filter) {
        return parseJankRows(execute(ClickHouseJankQuerySql.selectEvents(filter)));
    }

    /** 查询场景 FPS 聚合，结果在 ClickHouse 侧按算法版本隔离。 */
    public List<FpsMetricAggregate> queryFpsMetrics(MetricQueryFilter filter) {
        List<FpsMetricAggregate> result = new ArrayList<>();
        for (JsonNode row : rows(execute(ClickHouseJankMetricsQuerySql.selectFps(filter)))) {
            long total = row.path("total_records").asLong();
            long valid = row.path("valid_records").asLong();
            result.add(new FpsMetricAggregate(text(row, "algorithm_version"), total, valid,
                    metricValue(row, "average_fps", valid), metricValue(row, "p50_fps", valid),
                    metricValue(row, "p90_fps", valid), metricValue(row, "p99_fps", valid),
                    valid == 0 ? (total == 0 ? "no_data" : "no_valid_data") : "ok"));
        }
        return List.copyOf(result);
    }

    /** 查询设备日挂起率聚合，ClickHouse 先合并设备/UTC 日再计算分位数。 */
    public List<SuspensionMetricAggregate> querySuspensionMetrics(MetricQueryFilter filter) {
        List<SuspensionMetricAggregate> result = new ArrayList<>();
        for (JsonNode row : rows(execute(ClickHouseJankMetricsQuerySql.selectSuspension(filter)))) {
            long total = row.path("total_records").asLong();
            long valid = row.path("valid_device_day_records").asLong();
            result.add(new SuspensionMetricAggregate(text(row, "algorithm_version"), total, valid,
                    metricValue(row, "average_seconds_per_hour", valid),
                    metricValue(row, "p50_seconds_per_hour", valid),
                    metricValue(row, "p90_seconds_per_hour", valid),
                    metricValue(row, "p99_seconds_per_hour", valid),
                    valid == 0 ? (total == 0 ? "no_data" : "denominator_insufficient") : "ok"));
        }
        return List.copyOf(result);
    }

    /** 查询 FPS UTC 小时/天趋势，分位数方向与范围汇总保持一致。 */
    public List<MetricTrendAggregate> queryFpsTrend(MetricQueryFilter filter, String interval) {
        return metricTrendRows(ClickHouseJankMetricsQuerySql.selectFpsTrend(filter, interval), "no_valid_data");
    }

    /** 查询设备日挂起率 UTC 天趋势，数据库侧先合并设备日。 */
    public List<MetricTrendAggregate> querySuspensionTrend(MetricQueryFilter filter) {
        return metricTrendRows(ClickHouseJankMetricsQuerySql.selectSuspensionTrend(filter),
                "denominator_insufficient");
    }

    private List<MetricTrendAggregate> metricTrendRows(String sql, String emptyStatus) {
        List<MetricTrendAggregate> result = new ArrayList<>();
        for (JsonNode row : rows(execute(sql))) {
            long total = row.path("total_records").asLong();
            long valid = row.path("valid_records").asLong();
            result.add(new MetricTrendAggregate(metricInstant(row, "bucket_start"),
                    metricInstant(row, "bucket_end"), text(row, "algorithm_version"), total, valid,
                    metricValue(row, "average_value", valid), metricValue(row, "p50_value", valid),
                    metricValue(row, "p90_value", valid), metricValue(row, "p99_value", valid),
                    valid == 0 ? (total == 0 ? "no_data" : emptyStatus) : "ok"));
        }
        return List.copyOf(result);
    }

    private Instant metricInstant(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null || value.isBlank()) {
            throw new EventStoreUnavailableException("ClickHouse 指标趋势缺少时间桶字段: " + field);
        }
        try {
            return value.indexOf('T') >= 0 ? Instant.parse(value)
                    : java.time.LocalDateTime.parse(value,
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]"))
                    .toInstant(java.time.ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            throw new EventStoreUnavailableException("ClickHouse 指标趋势时间桶格式无效: " + field, ex);
        }
    }

    /** 查询白名单维度聚合；metric 由查询服务校验后传入。 */
    public List<MetricDimensionAggregate> queryMetricDimensions(MetricQueryFilter filter,
                                                                  String metric, String dimension) {
        List<MetricDimensionAggregate> result = new ArrayList<>();
        for (JsonNode row : rows(execute(ClickHouseJankMetricsQuerySql.selectDimensions(filter, metric, dimension)))) {
            long total = row.path("total_records").asLong();
            long valid = "fps".equals(metric) ? row.path("valid_records").asLong()
                    : row.path("valid_device_day_records").asLong();
            boolean fps = "fps".equals(metric);
            result.add(new MetricDimensionAggregate(metric, dimension, text(row, "dimension_value"),
                    text(row, "algorithm_version"), total, valid, fps ? 0 : valid,
                    fps ? metricValue(row, "average_fps", valid) : null,
                    fps ? metricValue(row, "p50_fps", valid) : null,
                    fps ? metricValue(row, "p90_fps", valid) : null,
                    fps ? metricValue(row, "p99_fps", valid) : null,
                    fps ? null : metricValue(row, "average_seconds_per_hour", valid),
                    fps ? null : metricValue(row, "p50_seconds_per_hour", valid),
                    fps ? null : metricValue(row, "p90_seconds_per_hour", valid),
                    fps ? null : metricValue(row, "p99_seconds_per_hour", valid),
                    valid == 0 ? (total == 0 ? "no_data" : (fps ? "no_valid_data" : "denominator_insufficient")) : "ok"));
        }
        return List.copyOf(result);
    }

    private Set<String> findExistingIds(java.util.UUID appId, List<StoredEvent> events) {
        return findExistingIds("apm_event_raw", appId, events);
    }

    private Set<String> findExistingIds(String table, java.util.UUID appId, List<StoredEvent> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        String ids = events.stream().map(StoredEvent::eventId)
                .distinct().map(id -> "'" + escape(id) + "'").reduce((left, right) -> left + "," + right).orElse("");
        String query = "SELECT event_id FROM " + table + " FINAL WHERE app_id = '" + escape(appId.toString())
                + "' AND event_id IN (" + ids + ") GROUP BY event_id FORMAT JSONEachRow";
        Set<String> existing = new HashSet<>();
        for (JsonNode row : rows(execute(query))) {
            existing.add(row.path("event_id").asText());
        }
        return existing;
    }

    /**
     * 原始事件已存在时仍检查专用卡顿事实/详情，补偿上一次部分写入失败的阶段。
     * 补偿不改变 append 的 accepted/duplicate 结果，详情失败继续使用可重试错误语义。
     */
    private void repairExistingJank(java.util.UUID appId, List<StoredEvent> events, Set<String> existingRawIds) {
        List<StoredEvent> existingJank = events.stream()
                .filter(StoredEvent::isJank)
                .filter(event -> existingRawIds.contains(event.eventId()))
                .toList();
        if (existingJank.isEmpty()) {
            return;
        }
        Set<String> existingFacts = findExistingIds("apm_jank_event", appId, existingJank);
        List<StoredEvent> missingFacts = existingJank.stream()
                .filter(event -> !existingFacts.contains(event.eventId()))
                .toList();
        if (!missingFacts.isEmpty()) {
            insertJankFacts(missingFacts);
        }
        Set<String> existingDetails = findExistingIds("apm_jank_detail", appId, existingJank);
        List<StoredEvent> missingDetails = existingJank.stream()
                .filter(event -> !existingDetails.contains(event.eventId()))
                .toList();
        if (!missingDetails.isEmpty()) {
            try {
                insertJankDetails(missingDetails);
            } catch (EventStoreUnavailableException ex) {
                if (qualityMetrics != null) {
                    qualityMetrics.jankFactDetailInconsistency();
                }
                throw ex;
            }
        }
    }

    private void insertRaw(List<StoredEvent> events) {
        StringBuilder body = new StringBuilder();
        for (StoredEvent event : events) {
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
            row.put("app_id", event.appId().toString());
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

    private void insertJankFacts(List<StoredEvent> events) {
        StringBuilder body = new StringBuilder();
        for (StoredEvent event : events) {
            var payload = event.jank();
            var analysis = event.jankAnalysis();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", event.appId().toString());
            row.put("package_name", event.packageName());
            row.put("event_id", event.eventId());
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
            row.put("scene", payload.scene());
            row.put("algorithm_version", payload.algorithmVersion());
            row.put("message_duration_ns", payload.messageDurationNs());
            row.put("threshold_ns", payload.thresholdNs());
            row.put("sampling_interval_ns", payload.samplingIntervalNs());
            row.put("estimated_duration_ns", analysis.estimatedDurationNs());
            row.put("estimated_unattributed_duration_ns", analysis.estimatedUnattributedDurationNs());
            row.put("covered_duration_ns", analysis.coveredDurationNs());
            row.put("uncovered_duration_ns", analysis.uncoveredDurationNs());
            row.put("expected_sample_count", analysis.expectedSampleCount());
            row.put("parsed_sample_count", analysis.parsedSampleCount());
            row.put("missing_sample_count", analysis.missingSampleCount());
            row.put("fingerprint", valueOrEmpty(event.crashFingerprint()));
            row.put("fingerprint_version", valueOrEmpty(event.fingerprintVersion()));
            row.put("jank_payload_json", write(payload));
            row.put("jank_analysis_json", write(analysis));
            body.append(write(row)).append('\n');
        }
        execute("INSERT INTO apm_jank_event FORMAT JSONEachRow", body.toString());
    }

    private void insertJankDetails(List<StoredEvent> events) {
        StringBuilder body = new StringBuilder();
        for (StoredEvent event : events) {
            JankAnalysis analysis = event.jankAnalysis();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", event.appId().toString());
            row.put("event_id", event.eventId());
            row.put("event_time", formatTime(event.occurredAt()));
            row.put("received_time", formatTime(event.receivedAt()));
            row.put("fingerprint", valueOrEmpty(event.crashFingerprint()));
            row.put("fingerprint_version", valueOrEmpty(event.fingerprintVersion()));
            row.put("stack_dictionary_json", write(analysis.stackDictionary()));
            row.put("samples_json", write(analysis.sampleSlices()));
            row.put("call_tree_json", write(analysis.callTree()));
            row.put("evidence_json", write(Map.of(
                    "exactMessageDurationNs", analysis.exactMessageDurationNs(),
                    "estimatedDurationNs", analysis.estimatedDurationNs(),
                    "estimatedUnattributedDurationNs", analysis.estimatedUnattributedDurationNs(),
                    "coveredDurationNs", analysis.coveredDurationNs(),
                    "uncoveredDurationNs", analysis.uncoveredDurationNs(),
                    "algorithmVersion", analysis.algorithmVersion(),
                    "warnings", analysis.warnings())));
            body.append(write(row)).append('\n');
        }
        execute("INSERT INTO apm_jank_detail FORMAT JSONEachRow", body.toString());
    }

    private void insertFrameSummaries(List<StoredEvent> events) {
        StringBuilder body = new StringBuilder();
        for (StoredEvent event : events) {
            FrameSceneSummaryPayload payload = event.frameSceneSummary();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", event.appId().toString());
            row.put("event_id", event.eventId());
            row.put("event_time", formatTime(event.occurredAt()));
            row.put("received_time", formatTime(event.receivedAt()));
            row.put("session_id", event.sessionId());
            row.put("anonymous_device_id", event.anonymousDeviceId());
            row.put("app_version", event.appVersion());
            row.put("channel", event.channel());
            row.put("environment", event.environment());
            row.put("os_version", event.osVersion());
            row.put("device_model", event.deviceModel());
            row.put("scene", payload.scene());
            row.put("algorithm_version", payload.algorithmVersion());
            row.put("active_duration_ms", payload.activeDurationMs());
            row.put("ui_refresh_frame_count", payload.uiRefreshFrameCount());
            row.put("refresh_rate_hz", payload.refreshRateHz());
            row.put("normalized_fps60", payload.normalizedFps60());
            row.put("frame_duration_histogram_json", write(payload.frameDurationHistogram()));
            body.append(write(row)).append('\n');
        }
        execute("INSERT INTO apm_frame_scene_summary FORMAT JSONEachRow", body.toString());
    }

    private void insertSuspensionSegments(List<StoredEvent> events) {
        StringBuilder body = new StringBuilder();
        for (StoredEvent event : events) {
            ForegroundSuspensionSummaryPayload payload = event.foregroundSuspensionSummary();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app_id", event.appId().toString());
            row.put("event_id", event.eventId());
            row.put("event_time", formatTime(event.occurredAt()));
            row.put("received_time", formatTime(event.receivedAt()));
            row.put("session_id", event.sessionId());
            row.put("anonymous_device_id", event.anonymousDeviceId());
            row.put("app_version", event.appVersion());
            row.put("channel", event.channel());
            row.put("environment", event.environment());
            row.put("os_version", event.osVersion());
            row.put("device_model", event.deviceModel());
            row.put("algorithm_version", payload.algorithmVersion());
            row.put("foreground_duration_ms", payload.foregroundDurationMs());
            row.put("suspension_duration_ms", payload.suspensionDurationMs());
            row.put("suspension_count", payload.suspensionCount());
            row.put("threshold_ms", payload.thresholdMs());
            body.append(write(row)).append('\n');
        }
        execute("INSERT INTO apm_device_suspension_segment FORMAT JSONEachRow", body.toString());
    }

    private List<StoredEvent> parseRows(String response) {
        List<StoredEvent> events = new ArrayList<>();
        for (JsonNode row : rows(response)) {
            try {
                String rawCrashJson = text(row, "raw_crash_json");
                CrashPayload crash = rawCrashJson == null || rawCrashJson.isBlank()
                        ? null : objectMapper.readValue(rawCrashJson, CrashPayload.class);
                String jankPayloadJson = text(row, "jank_payload_json");
                JankPayload jank = jankPayloadJson == null || jankPayloadJson.isBlank()
                        ? null : objectMapper.readValue(jankPayloadJson, JankPayload.class);
                String jankAnalysisJson = text(row, "jank_analysis_json");
                JankAnalysis jankAnalysis = jankAnalysisJson == null || jankAnalysisJson.isBlank()
                        ? null : objectMapper.readValue(jankAnalysisJson, JankAnalysis.class);
                events.add(new StoredEvent(
                        java.util.UUID.fromString(text(row, "app_id")), text(row, "package_name"), text(row, "event_id"), text(row, "event_type"),
                        parseTime(text(row, "event_time")), parseTime(text(row, "received_time")),
                        row.path("schema_version").asInt(), text(row, "session_id"), text(row, "anonymous_device_id"),
                        text(row, "app_version"), row.path("version_code").asInt(), text(row, "build_id"),
                        text(row, "environment"), text(row, "channel"), text(row, "os_version"),
                        text(row, "device_model"), text(row, "network_type"), Map.of(), Map.of(),
                        emptyToNull(text(row, "crash_kind")), row.path("crash_fatal").asInt() == 1,
                        emptyToNull(text(row, "crash_exception_type")), emptyToNull(text(row, "crash_fingerprint")),
                        emptyToNull(text(row, "fingerprint_version")), emptyToNull(text(row, "symbolication_status")), crash,
                        jank, jankAnalysis, null, null));
            } catch (RuntimeException ex) {
                throw new EventStoreUnavailableException();
            }
        }
        return List.copyOf(events);
    }

    private List<StoredEvent> parseJankRows(String response) {
        List<StoredEvent> events = new ArrayList<>();
        for (JsonNode row : rows(response)) {
            try {
                JankPayload jank = objectMapper.readValue(text(row, "jank_payload_json"), JankPayload.class);
                JankAnalysis analysis = objectMapper.readValue(text(row, "jank_analysis_json"), JankAnalysis.class);
                events.add(new StoredEvent(
                        java.util.UUID.fromString(text(row, "app_id")), text(row, "package_name"), text(row, "event_id"), "jank",
                        parseTime(text(row, "event_time")), parseTime(text(row, "received_time")),
                        row.path("schema_version").asInt(), text(row, "session_id"), text(row, "anonymous_device_id"),
                        text(row, "app_version"), row.path("version_code").asInt(), text(row, "build_id"),
                        text(row, "environment"), text(row, "channel"), text(row, "os_version"),
                        text(row, "device_model"), text(row, "network_type"), Map.of(), Map.of(),
                        null, null, null, emptyToNull(text(row, "fingerprint")),
                        emptyToNull(text(row, "fingerprint_version")), "raw_only", null, jank, analysis, null, null));
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

    private Double metricValue(JsonNode row, String name, long validCount) {
        if (validCount == 0) {
            return null;
        }
        JsonNode value = row.get(name);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        double result = value.asDouble();
        return Double.isFinite(result) ? result : null;
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
