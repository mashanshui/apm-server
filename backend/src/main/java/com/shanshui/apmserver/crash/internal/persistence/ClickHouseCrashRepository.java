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

    /** 新接收只写当前事实和初始状态，确认两侧之后才计 accepted。 */
    @Override
    public synchronized AppendResult append(UUID appId, List<CrashStoredSignal> events) {
        int accepted = 0;
        for (CrashStoredSignal event : events) {
            if (!appId.equals(event.appId())) throw new IllegalArgumentException("事件应用不一致");
            var crash = event instanceof CrashEvent item ? item : null;
            boolean fresh = newStore().accept(new com.shanshui.apmserver.crash.internal.domain.CrashFact(
                    event.metadata(), event.crashKind(), Boolean.TRUE.equals(event.crashFatal()),
                    crash == null ? "" : crash.diagnosticsPayload(), crash == null ? "" : crash.state().captureSha256()),
                    crash == null ? null : crash.state());
            if (fresh) accepted++;
        }
        return new AppendResult(accepted, events.size() - accepted);
    }

    /** 生产存储不暴露全应用堆栈读取，所有业务查询必须使用有预算的领域端口。 */
    @Override
    public List<CrashStoredSignal> findAll(UUID appId) {
        throw new UnsupportedOperationException("ClickHouse Crash 必须使用有界聚合和分页查询");
    }

    /** 新事实和状态共用同一数据库通道与预算。 */
    private ClickHouseCrashStateStore newStore() {
        return new ClickHouseCrashStateStore(client, objectMapper, queryProperties);
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
                + "argMin(grouping_kind, tuple(event_time,event_id)) AS issue_grouping_kind, "
                + "if(argMin(grouping_kind,tuple(event_time,event_id))='temporary',argMin(build_id,tuple(event_time,event_id)),'') AS temporary_build_id, "
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
                        parseTime(text(row, "last_seen_at")), text(row, "issue_grouping_kind"), emptyToNull(text(row, "temporary_build_id")))).toList();
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
                        emptyToNull(text(row, "symbolication_status")), text(row, "grouping_kind"))).toList();
        boolean more = values.size() > filter.limit();
        List<CrashEventSummary> page = more ? values.subList(0, filter.limit()) : values;
        return new CrashPage<>(page, more ? CrashCursor.event(filter, page.getLast()) : null);
    }

    /** 仅在已验证的列上拼装查询，原始输入都通过单一字符串转义入口。 */
    private String logicalRows(CrashQueryFilter filter) {
        return logicalRows(filter, Projection.STATS);
    }

    /** 先确定最新状态再筛选指纹，当前启动分母来自同一事实表。 */
    private String logicalRows(CrashQueryFilter filter, Projection projection) {
        String scope = "app_id='" + escape(filter.appId().toString()) + "' AND event_time >= toDateTime64('"
                + formatTime(filter.from()) + "',3,'UTC') AND event_time < toDateTime64('" + formatTime(filter.to()) + "',3,'UTC')";
        StringBuilder factWhere = new StringBuilder(scope);
        dimension(factWhere, "app_version", filter.appVersion());
        dimension(factWhere, "channel", filter.channel());
        dimension(factWhere, "environment", filter.environment());
        dimension(factWhere, "os_version", filter.osVersion());
        dimension(factWhere, "device_model", filter.deviceModel());
        // 状态侧只投影轻量列，摘要和统计 SQL 根本不引用 stack_payload。
        String state = "SELECT app_id,build_id,event_time,event_id,fingerprint,fingerprint_version,exception_type,symbolication_status,grouping_kind"
                + " FROM apm_crash_state FINAL WHERE " + scope;
        // 显式投影事实列，不能依赖优化器从 SELECT * 中裁掉大诊断载荷。
        String fact = "SELECT app_id,build_id,event_time,event_id,event_type,session_id,anonymous_device_id,"
                + "app_version,version_code,channel,environment,os_version,device_model"
                + " FROM apm_crash_fact FINAL WHERE " + factWhere;
        String sql = "SELECT f.event_id AS event_id,f.event_type AS event_type,f.event_time AS event_time,"
                + "f.session_id AS session_id,f.anonymous_device_id AS anonymous_device_id,f.app_version AS app_version,"
                + "f.version_code AS version_code,f.build_id AS build_id,f.channel AS channel,f.environment AS environment,"
                + "f.os_version AS os_version,f.device_model AS device_model,s.fingerprint AS crash_fingerprint,"
                + "s.fingerprint_version AS fingerprint_version,s.exception_type AS crash_exception_type,"
                + "s.symbolication_status AS symbolication_status,s.grouping_kind AS grouping_kind"
                + " FROM (" + fact + ") AS f LEFT ANY JOIN (" + state + ") AS s"
                + " ON f.app_id=s.app_id AND f.build_id=s.build_id AND f.event_time=s.event_time AND f.event_id=s.event_id"
                + " WHERE (f.event_type='app_start' OR (f.event_type='crash' AND s.event_id!=''))";
        if (filter.fingerprint() != null) sql += " AND (f.event_type='app_start' OR s.fingerprint='" + escape(filter.fingerprint()) + "')";
        return sql;
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

    /** 详情先定位不变事实，再按同一定位读取最新完整状态。 */
    @Override
    public Optional<CrashStoredSignal> findByEventId(UUID appId, String eventId) {
        List<JsonNode> found = rows(client.executeQuery("SELECT * FROM apm_crash_fact FINAL WHERE app_id='"
                + escape(appId.toString()) + "' AND event_id='" + escape(eventId) + "' LIMIT 1 FORMAT JSONEachRow",
                budget(queryProperties.getDefaultTimeoutMs())));
        if (found.isEmpty()) return Optional.empty();
        JsonNode row = found.getFirst();
        EventMetadata metadata = metadata(row);
        if ("app_start".equals(metadata.eventType())) return Optional.of(new AppStartEvent(metadata));
        var state = newStore().current(appId, eventId).orElseThrow(EventStoreUnavailableException::new);
        return Optional.of(new CrashEvent(metadata, text(row, "crash_kind"), row.path("crash_fatal").asInt() == 1, state,
                text(row, "diagnostics_payload")));
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
