package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.api.CrashPayload;
import com.shanshui.apmserver.crash.api.CrashStats;
import com.shanshui.apmserver.crash.api.CrashTrendPoint;
import com.shanshui.apmserver.crash.api.CrashIssueSummary;
import com.shanshui.apmserver.crash.api.CrashEventSummary;
import com.shanshui.apmserver.crash.internal.application.CrashCursor;
import com.shanshui.apmserver.crash.internal.domain.CrashPage;
import com.shanshui.apmserver.crash.internal.domain.AppStartEvent;
import com.shanshui.apmserver.crash.internal.domain.CrashEvent;
import com.shanshui.apmserver.crash.internal.domain.CrashQueryFilter;
import com.shanshui.apmserver.crash.internal.domain.CrashStoredSignal;
import com.shanshui.apmserver.crash.internal.port.CrashQueryPort;
import com.shanshui.apmserver.crash.internal.port.CrashWritePort;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.QueryBudget;
import com.shanshui.apmserver.platform.api.QueryProperties;
import com.shanshui.apmserver.platform.api.EventStoreUnavailableException;
import com.shanshui.apmserver.telemetry.api.AppendResult;
import com.shanshui.apmserver.telemetry.api.EventMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
    private final QueryProperties queryProperties;

    @Autowired
    public ClickHouseCrashRepository(ClickHouseHttpClient client, ObjectMapper objectMapper,
                                     QueryProperties queryProperties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.queryProperties = queryProperties;
    }

    /** 测试可注入默认查询预算，不改变运行时的配置装配。 */
    public ClickHouseCrashRepository(ClickHouseHttpClient client, ObjectMapper objectMapper) {
        this(client, objectMapper, new QueryProperties());
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

    /** 将应用、时间窗和白名单维度下推，数据库内完成逻辑事件去重与精确统计。 */
    @Override
    public CrashStats overview(CrashQueryFilter filter) {
        String sql = "SELECT " + statColumns() + " FROM (" + logicalRows(filter) + ") FORMAT JSONEachRow";
        List<JsonNode> result = rows(client.executeQuery(sql, budget(filter.timeoutMs())));
        return result.isEmpty() ? emptyStats() : stats(result.getFirst());
    }

    /** 趋势桶在 ClickHouse 内聚合，跨桶会话仅在各自桶内计算。 */
    @Override
    public List<CrashTrendPoint> trend(CrashQueryFilter filter, String interval) {
        String bucket = "day".equals(interval) ? "toStartOfDay(event_time)" : "toStartOfHour(event_time)";
        String sql = "SELECT " + bucket + " AS bucket, " + statColumns()
                + " FROM (" + logicalRows(filter) + ") GROUP BY bucket ORDER BY bucket FORMAT JSONEachRow";
        return rows(client.executeQuery(sql, budget(filter.timeoutMs()))).stream().map(row -> {
            Instant start = parseTime(text(row, "bucket"));
            return new CrashTrendPoint(start, start.plus(1, "day".equals(interval) ? ChronoUnit.DAYS : ChronoUnit.HOURS),
                    stats(row));
        }).toList();
    }

    /** Issue 在数据库内聚合排序和续页，不读取异常链。 */
    @Override
    public CrashPage<CrashIssueSummary> issues(CrashQueryFilter filter) {
        String aggregates = "SELECT crash_fingerprint AS fingerprint, "
                + "argMin(fingerprint_version, tuple(event_time, event_id)) AS fingerprint_version, "
                + "argMin(crash_exception_type, tuple(event_time, event_id)) AS exception_type, "
                + "count() AS event_count, uniqExact(nullIf(session_id, '')) AS crashed_sessions, "
                + "uniqExact(nullIf(anonymous_device_id, '')) AS affected_devices, "
                + "min(event_time) AS first_seen_at, max(event_time) AS last_seen_at "
                + "FROM (" + logicalRows(filter, Projection.ISSUE) + ") WHERE event_type = 'crash' "
                + "GROUP BY crash_fingerprint";
        StringBuilder sql = new StringBuilder("SELECT * FROM (").append(aggregates).append(")");
        if (filter.cursor() != null) {
            CrashCursor.State position = CrashCursor.validate(filter.cursor(), CrashCursor.Kind.ISSUES, filter);
            sql.append(" WHERE event_count < ").append(position.count())
                    .append(" OR (event_count = ").append(position.count())
                    .append(" AND last_seen_at < toDateTime64('").append(formatTime(position.time()))
                    .append("', 3, 'UTC'))")
                    .append(" OR (event_count = ").append(position.count())
                    .append(" AND last_seen_at = toDateTime64('").append(formatTime(position.time()))
                    .append("', 3, 'UTC') AND fingerprint > '").append(escape(position.id())).append("')");
        }
        sql.append(" ORDER BY event_count DESC, last_seen_at DESC, fingerprint ASC LIMIT ")
                .append(filter.limit() + 1).append(" FORMAT JSONEachRow");
        List<CrashIssueSummary> values = rows(client.executeQuery(sql.toString(), budget(filter.timeoutMs()))).stream()
                .map(row -> new CrashIssueSummary(text(row, "fingerprint"),
                        emptyToNull(text(row, "fingerprint_version")), emptyToNull(text(row, "exception_type")),
                        row.path("event_count").asLong(), row.path("crashed_sessions").asLong(),
                        row.path("affected_devices").asLong(), parseTime(text(row, "first_seen_at")),
                        parseTime(text(row, "last_seen_at")))).toList();
        boolean more = values.size() > filter.limit();
        List<CrashIssueSummary> page = more ? values.subList(0, filter.limit()) : values;
        return new CrashPage<>(page, more ? CrashCursor.issue(filter, page.getLast()) : null);
    }

    /** 事件页仅投影原始事实中的摘要列，堆栈只在详情查询读取。 */
    @Override
    public CrashPage<CrashEventSummary> events(CrashQueryFilter filter, String fingerprint) {
        StringBuilder sql = new StringBuilder("SELECT * FROM (")
                .append(logicalRows(filter, Projection.EVENT))
                .append(") WHERE event_type = 'crash' AND crash_fingerprint = '")
                .append(escape(fingerprint)).append("'");
        if (filter.cursor() != null) {
            CrashCursor.State position = CrashCursor.validate(filter.cursor(), CrashCursor.Kind.EVENTS, filter);
            sql.append(" AND (event_time < toDateTime64('").append(formatTime(position.time()))
                    .append("', 3, 'UTC') OR (event_time = toDateTime64('")
                    .append(formatTime(position.time())).append("', 3, 'UTC') AND event_id > '")
                    .append(escape(position.id())).append("'))");
        }
        sql.append(" ORDER BY event_time DESC, event_id ASC LIMIT ")
                .append(filter.limit() + 1).append(" FORMAT JSONEachRow");
        List<CrashEventSummary> values = rows(client.executeQuery(sql.toString(), budget(filter.timeoutMs()))).stream()
                .map(row -> new CrashEventSummary(text(row, "event_id"), parseTime(text(row, "event_time")),
                        text(row, "app_version"), row.path("version_code").asInt(), text(row, "build_id"),
                        text(row, "channel"), text(row, "environment"), text(row, "os_version"),
                        text(row, "device_model"), emptyToNull(text(row, "session_id")),
                        emptyToNull(text(row, "anonymous_device_id")), emptyToNull(text(row, "crash_exception_type")),
                        emptyToNull(text(row, "crash_fingerprint")),
                        emptyToNull(text(row, "symbolication_status")))).toList();
        boolean more = values.size() > filter.limit();
        List<CrashEventSummary> page = more ? values.subList(0, filter.limit()) : values;
        return new CrashPage<>(page, more ? CrashCursor.event(filter, page.getLast()) : null);
    }

    /** 仅在已验证的列上拼装查询，原始输入都通过单一字符串转义入口。 */
    private String logicalRows(CrashQueryFilter filter) {
        return logicalRows(filter, Projection.STATS);
    }

    private String logicalRows(CrashQueryFilter filter, Projection projection) {
        StringBuilder rawWhere = new StringBuilder("r.app_id = '").append(escape(filter.appId().toString()))
                .append("' AND r.event_type IN ('crash','app_start')")
                .append(" AND r.event_time >= toDateTime64('").append(formatTime(filter.from()))
                .append("', 3, 'UTC') AND r.event_time < toDateTime64('").append(formatTime(filter.to()))
                .append("', 3, 'UTC')");
        StringBuilder select = new StringBuilder("SELECT r.event_id AS event_id, argMax(r.event_type, r.received_time) AS event_type, ")
                .append("argMax(r.event_time, r.received_time) AS event_time, ")
                .append("argMax(r.session_id, r.received_time) AS session_id, ")
                .append("argMax(r.anonymous_device_id, r.received_time) AS anonymous_device_id");
        if (projection != Projection.STATS || filter.fingerprint() != null) {
            select.append(", argMax(r.crash_fingerprint, r.received_time) AS crash_fingerprint");
        }
        if (projection != Projection.STATS) {
            select.append(", argMax(r.fingerprint_version, r.received_time) AS fingerprint_version")
                    .append(", argMax(r.crash_exception_type, r.received_time) AS crash_exception_type");
        }
        if (projection == Projection.EVENT || filter.appVersion() != null) {
            select.append(", argMax(r.app_version, r.received_time) AS app_version");
        }
        if (projection == Projection.EVENT || filter.channel() != null) {
            select.append(", argMax(r.channel, r.received_time) AS channel");
        }
        if (projection == Projection.EVENT || filter.environment() != null) {
            select.append(", argMax(r.environment, r.received_time) AS environment");
        }
        if (projection == Projection.EVENT || filter.osVersion() != null) {
            select.append(", argMax(r.os_version, r.received_time) AS os_version");
        }
        if (projection == Projection.EVENT || filter.deviceModel() != null) {
            select.append(", argMax(r.device_model, r.received_time) AS device_model");
        }
        if (projection == Projection.EVENT) {
            select.append(", argMax(r.version_code, r.received_time) AS version_code")
                    .append(", argMax(r.build_id, r.received_time) AS build_id")
                    .append(", argMax(r.symbolication_status, r.received_time) AS symbolication_status");
        }
        select.append(" FROM apm_event_raw AS r FINAL WHERE ").append(rawWhere)
                .append(" GROUP BY r.event_id");
        StringBuilder normalized = new StringBuilder("SELECT * FROM (").append(select).append(") WHERE 1 = 1");
        dimension(normalized, "app_version", filter.appVersion());
        dimension(normalized, "channel", filter.channel());
        dimension(normalized, "environment", filter.environment());
        dimension(normalized, "os_version", filter.osVersion());
        dimension(normalized, "device_model", filter.deviceModel());
        if (filter.fingerprint() != null) {
            normalized.append(" AND (event_type = 'app_start' OR crash_fingerprint = '")
                    .append(escape(filter.fingerprint())).append("')");
        }
        return normalized.toString();
    }

    private enum Projection { STATS, ISSUE, EVENT }

    /** 客户端只能降低超时，上限与扫描/内存预算始终来自服务配置。 */
    private QueryBudget budget(long timeoutMs) {
        return new QueryBudget(timeoutMs, queryProperties.getMaxRowsToRead(), queryProperties.getMaxBytesToRead(),
                queryProperties.getMaxMemoryUsage(), queryProperties.getMaxResponseBytes());
    }

    /** 固定列名由调用方给出，不接受用户输入充当 SQL 标识符。 */
    private void dimension(StringBuilder where, String column, String value) {
        if (value != null) where.append(" AND ").append(column).append(" = '").append(escape(value)).append("'");
    }

    /** exact 去重不能使用近似 uniqCombined，也不能先对趋势桶求和再算概览。 */
    private String statColumns() {
        return "count() AS signal_count, "
                + "uniqExactIf(nullIf(session_id, ''), event_type = 'app_start') AS started_sessions, "
                + "uniqExactIf(event_id, event_type = 'crash') AS crash_events, "
                + "uniqExactIf(nullIf(session_id, ''), event_type = 'crash') AS crashed_sessions, "
                + "uniqExactIf(nullIf(anonymous_device_id, ''), event_type = 'crash') AS affected_devices";
    }

    /** 按原 HTTP 契约区分无事件和缺少启动分母。 */
    private CrashStats stats(JsonNode row) {
        long starts = row.path("started_sessions").asLong();
        long crashed = row.path("crashed_sessions").asLong();
        long signals = row.path("signal_count").asLong();
        return new CrashStats(starts, row.path("crash_events").asLong(), crashed,
                row.path("affected_devices").asLong(),
                starts == 0 ? null : (double) crashed / starts * 1000.0,
                starts == 0 ? null : 1.0 - (double) crashed / starts,
                starts == 0 ? (signals == 0 ? "no_data" : "denominator_insufficient") : "ok");
    }

    private CrashStats emptyStats() {
        return new CrashStats(0, 0, 0, 0, null, null, "no_data");
    }

    @Override
    public Optional<CrashStoredSignal> findByEventId(UUID appId, String eventId) {
        String where = "r.app_id = '" + escape(appId.toString()) + "' AND r.event_id = '"
                + escape(eventId) + "' AND r.event_type IN ('crash','app_start')";
        return parseRows(client.executeQuery(selectSql(where) + " LIMIT 1 FORMAT JSONEachRow",
                budget(queryProperties.getDefaultTimeoutMs()))).stream().findFirst();
    }

    private String selectSql(String where) {
        return "SELECT r.app_id AS app_id, r.package_name AS package_name, r.event_id AS event_id, "
                + "r.event_type AS event_type, r.event_time AS event_time, r.received_time AS received_time, "
                + "r.schema_version AS schema_version, r.app_version AS app_version, r.version_code AS version_code, "
                + "r.build_id AS build_id, r.channel AS channel, r.environment AS environment, r.session_id AS session_id, "
                + "r.process_id AS process_id, r.anonymous_device_id AS anonymous_device_id, r.os_version AS os_version, "
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
        row.put("process_id", valueOrEmpty(event.processId()));
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
                emptyToNull(text(row, "process_id")), text(row, "anonymous_device_id"), text(row, "app_version"), row.path("version_code").asInt(),
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
