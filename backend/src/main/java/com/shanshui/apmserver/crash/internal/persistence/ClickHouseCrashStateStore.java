package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.internal.domain.CrashFact;
import com.shanshui.apmserver.crash.internal.domain.CrashCaptureIdentity;
import com.shanshui.apmserver.crash.internal.domain.CrashState;
import com.shanshui.apmserver.crash.internal.domain.CrashWorkItem;
import com.shanshui.apmserver.crash.internal.port.CrashStatePort;
import com.shanshui.apmserver.platform.api.ClickHouseHttpClient;
import com.shanshui.apmserver.platform.api.QueryBudget;
import com.shanshui.apmserver.platform.api.QueryProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 新事实/状态专属适配器；缺少新表时错误直接上抛，不回退旧表。 */
@Repository
@ConditionalOnProperty(name = "apm.storage.mode", havingValue = "clickhouse")
public class ClickHouseCrashStateStore implements CrashStatePort {
    /** 数据库 UTC 毫秒时间。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 通用受限传输。 */
    private final ClickHouseHttpClient client;
    /** JSONEachRow 编解码。 */
    private final ObjectMapper mapper;
    /** 所有查询共享的扫描、响应与时间预算。 */
    private final QueryProperties properties;

    /** 注入数据库、编解码器与有限预算。 */
    public ClickHouseCrashStateStore(ClickHouseHttpClient client, ObjectMapper mapper, QueryProperties properties) {
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 新链路启动时校验所需结构；缺表/缺列必须拒绝启动，禁止回退旧结构。 */
    @jakarta.annotation.PostConstruct
    public void requireCurrentSchema() {
        query("SELECT app_id,event_id,build_id,event_time,received_time,event_type,crash_kind,crash_fatal,diagnostics_payload,capture_sha256 FROM apm_crash_fact LIMIT 0 FORMAT JSONEachRow");
        query("SELECT state_version,symbolication_status,symbolication_reason,stack_format,stack_payload,exception_type,fingerprint,fingerprint_version,grouping_kind,symbol_id,symbol_revision,symbol_sha256,processed_at,retry_at,thread_stacks_payload,capture_sha256 FROM apm_crash_state LIMIT 0 FORMAT JSONEachRow");
        // 新代码不能连接尚未放行 raw_native 的旧状态表，否则 Native 接收会持续失败。
        var definitions = query("SELECT create_table_query FROM system.tables WHERE database=currentDatabase() AND name='apm_crash_state' LIMIT 1 FORMAT JSONEachRow");
        if (definitions.isEmpty() || !definitions.getFirst().path("create_table_query").asText().contains("native_symbolication_deferred")) {
            throw new com.shanshui.apmserver.platform.api.EventStoreUnavailableException("Crash 状态表尚未执行 Native 约束迁移 011");
        }
    }

    /** 每次接收同步确认两侧；重放版本 0 不能覆盖后台更高版本。 */
    @Override
    public synchronized boolean accept(CrashFact fact, CrashState initialState) {
        // 同一实例串行化初始身份检查；后台只追加版本，不更改事实。
        var metadata = fact.metadata();
        if (!("crash".equals(metadata.eventType()) && initialState != null && fact.fatal()
                && ("jvm".equals(fact.crashKind()) && "raw_chain".equals(initialState.stackFormat())
                || "native".equals(fact.crashKind()) && "raw_native".equals(initialState.stackFormat())))
                && !("app_start".equals(metadata.eventType()) && initialState == null)) {
            throw new IllegalArgumentException("当前事实必须是类型与状态一致的 fatal Crash 或启动事件");
        }
        String identity = "app_id=" + quote(metadata.appId().toString()) + " AND event_id=" + quote(metadata.eventId());
        List<JsonNode> existing = query("SELECT build_id,event_time,capture_sha256 FROM apm_crash_fact FINAL WHERE "
                + identity + " LIMIT 1 FORMAT JSONEachRow");
        boolean legacy = false;
        if (!existing.isEmpty()) {
            // 使用已确认事实的定位，拒绝改变事件身份的重试。
            JsonNode stored = existing.getFirst();
            if (!stored.path("build_id").asText().equals(metadata.buildId())
                    || !parseTime(stored.path("event_time").asText()).equals(metadata.occurredAt())) {
                throw new IllegalArgumentException("重复 eventId 不得改变构建或发生时间");
            }
            if (initialState != null) {
                String storedDigest = stored.path("capture_sha256").asText("");
                CrashCaptureIdentity.requireCompatible(storedDigest, initialState.captureSha256(),
                        !fact.diagnosticsPayload().isEmpty() || "native".equals(fact.crashKind()));
                legacy = storedDigest.isEmpty();
            }
        }
        if (initialState != null) {
            if (initialState.stateVersion() != 0 || !"pending".equals(initialState.status())
                    || !initialState.appId().equals(metadata.appId()) || !initialState.eventId().equals(metadata.eventId())
                    || !initialState.buildId().equals(metadata.buildId()) || !initialState.eventTime().equals(metadata.occurredAt())
                    || !initialState.captureSha256().equals(fact.captureSha256())) {
                throw new IllegalArgumentException("接收只能提交对应事实的初始 pending 状态");
            }
            // 客户端重复上传不能重新保存成功事件的原始链；孤立状态也可被重用。
            List<JsonNode> previousState = query("SELECT build_id,event_time,capture_sha256 FROM apm_crash_state FINAL WHERE " + identity + " LIMIT 1 FORMAT JSONEachRow");
            if (!previousState.isEmpty() && (!previousState.getFirst().path("build_id").asText().equals(metadata.buildId())
                    || !parseTime(previousState.getFirst().path("event_time").asText()).equals(metadata.occurredAt()))) {
                throw new IllegalArgumentException("重复 eventId 不得改变已保存状态的定位");
            }
            if (!previousState.isEmpty()) {
                String storedDigest = previousState.getFirst().path("capture_sha256").asText("");
                if (!existing.isEmpty() && !existing.getFirst().path("capture_sha256").asText("").equals(storedDigest)) {
                    throw new com.shanshui.apmserver.platform.api.EventStoreUnavailableException("Crash 事实与状态摘要不一致");
                }
                CrashCaptureIdentity.requireCompatible(storedDigest, initialState.captureSha256(),
                        !fact.diagnosticsPayload().isEmpty() || "native".equals(fact.crashKind()));
                legacy |= storedDigest.isEmpty();
            }
            if (previousState.isEmpty()) {
                publish(legacy ? CrashCaptureIdentity.withoutDigest(initialState) : initialState);
            }
        }
        if (!existing.isEmpty()) return false;
        // 状态已确认后才写事实，孤立状态永远不参与前台统计或后台选取。
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("app_id", metadata.appId());
        row.put("event_id", metadata.eventId());
        row.put("event_type", metadata.eventType());
        row.put("package_name", metadata.packageName());
        row.put("build_id", metadata.buildId());
        row.put("event_time", time(metadata.occurredAt()));
        row.put("received_time", time(metadata.receivedAt()));
        row.put("schema_version", metadata.schemaVersion());
        row.put("session_id", empty(metadata.sessionId()));
        row.put("process_id", empty(metadata.processId()));
        row.put("anonymous_device_id", empty(metadata.anonymousDeviceId()));
        row.put("app_version", empty(metadata.appVersion()));
        row.put("version_code", metadata.versionCode() == null ? 0 : metadata.versionCode());
        row.put("environment", empty(metadata.environment()));
        row.put("channel", empty(metadata.channel()));
        row.put("os_version", empty(metadata.osVersion()));
        row.put("device_model", empty(metadata.deviceModel()));
        row.put("network_type", empty(metadata.networkType()));
        row.put("crash_kind", empty(fact.crashKind()));
        row.put("crash_fatal", fact.fatal() ? 1 : 0);
        row.put("diagnostics_payload", legacy ? "" : fact.diagnosticsPayload());
        row.put("capture_sha256", legacy ? "" : fact.captureSha256());
        client.execute("INSERT INTO apm_crash_fact FORMAT JSONEachRow", mapper.writeValueAsString(row));
        return true;
    }

    /** 相同版本必须由调用方提供同一内容，最新状态由 state_version 决定。 */
    @Override
    public void publish(CrashState state) {
        client.execute("INSERT INTO apm_crash_state FORMAT JSONEachRow", encode(state));
    }

    /** 发布记录和 ClickHouse 使用相同确定编码。 */
    public String encode(CrashState state) { return new CrashStateJson(mapper).encode(state); }

    /** 将登记过的原始字节直接重放，不重新选择 mapping 或生成输出。 */
    @Override
    public void replay(String payload) { client.execute("INSERT INTO apm_crash_state FORMAT JSONEachRow", payload); }

    /** 当前模型严格解码。 */
    private CrashState decode(JsonNode row) { return new CrashStateJson(mapper).decode(row); }

    /** 只有已经确认事实的事件有可见状态；一次读取取得完整一致版本。 */
    @Override
    public Optional<CrashState> current(UUID appId, String eventId) {
        // 先定位不可变事实，避免 JOIN 全量状态时提前读取该构建所有堆栈。
        List<JsonNode> facts = query("SELECT build_id,event_time FROM apm_crash_fact FINAL WHERE app_id="
                + quote(appId.toString()) + " AND event_id=" + quote(eventId) + " AND event_type='crash' LIMIT 1 FORMAT JSONEachRow");
        if (facts.isEmpty()) return Optional.empty();
        JsonNode fact = facts.getFirst();
        return query("SELECT * FROM apm_crash_state FINAL PREWHERE app_id=" + quote(appId.toString())
                + " AND build_id=" + quote(fact.path("build_id").asText()) + " AND event_id=" + quote(eventId)
                + " AND event_time=" + date(parseTime(fact.path("event_time").asText()))
                + " LIMIT 1 FORMAT JSONEachRow").stream().map(this::decode).findFirst();
    }

    /** FINAL 在子查询中先确定最新版本，状态条件不能提前过滤旧 pending。 */
    @Override
    public List<CrashWorkItem> candidates(UUID appId, String buildId, UUID symbolId, int revision,
                                       Instant from, Instant now, int limit) {
        if (limit < 1 || limit > 100 || from.isBefore(now.minusSeconds(30L * 86400)) || !from.isBefore(now)) {
            throw new IllegalArgumentException("后台候选范围超过保留期或批次限制");
        }
        // 第一阶段只读取轻量列，取得有限身份后才关联载荷。
        String scope = "app_id=" + quote(appId.toString()) + " AND build_id=" + quote(buildId)
                + " AND event_time >= " + date(from) + " AND event_time < " + date(now.plusSeconds(901));
        String latest = "SELECT app_id,build_id,event_time,event_id,symbolication_status,symbol_id,symbol_revision,retry_at"
                + " FROM apm_crash_state FINAL WHERE " + scope;
        String selected = "SELECT l.app_id AS app_id,l.build_id AS build_id,l.event_time AS event_time,l.event_id AS event_id"
                + " FROM (" + latest + ") AS l INNER JOIN (SELECT app_id,build_id,event_time,event_id"
                + " FROM apm_crash_fact FINAL WHERE " + scope
                + " AND event_type='crash' AND crash_kind='jvm' AND crash_fatal=1) AS f"
                + " ON l.app_id=f.app_id AND l.build_id=f.build_id AND l.event_time=f.event_time AND l.event_id=f.event_id WHERE ("
                + "symbolication_status='pending' OR (symbolication_status='failed' AND ("
                + "symbol_id IS NULL OR symbol_id != " + quote(symbolId.toString())
                + " OR symbol_revision != " + revision + " OR retry_at <= " + date(now) + ")))"
                + " ORDER BY l.event_time,l.event_id LIMIT " + limit;
        String limitedState = "SELECT app_id,event_id,build_id,event_time,state_version,symbolication_status,symbolication_reason,"
                + "stack_format,stack_payload,exception_type,fingerprint,fingerprint_version,grouping_kind,symbol_id,"
                + "symbol_revision,symbol_sha256,processed_at,retry_at,capture_sha256,thread_stacks_payload"
                + " FROM apm_crash_state FINAL PREWHERE " + scope
                + " AND (app_id,build_id,event_time,event_id) IN (" + selected + ")"
                + " ORDER BY event_time,event_id LIMIT " + limit;
        return query("SELECT s.*,f.package_name FROM (" + limitedState + ") AS s INNER JOIN"
                + " (SELECT app_id,build_id,event_time,event_id,package_name FROM apm_crash_fact FINAL WHERE " + scope + ") AS f"
                + " ON s.app_id=f.app_id AND s.build_id=f.build_id AND s.event_time=f.event_time AND s.event_id=f.event_id"
                + " ORDER BY s.event_time,s.event_id LIMIT " + limit + " FORMAT JSONEachRow")
                .stream().map(row -> new CrashWorkItem(decode(row), row.path("package_name").asText())).toList();
    }

    /** 同一预算限制前台详情与后台候选，资源耗尽作为失败处理。 */
    private List<JsonNode> query(String sql) {
        return client.decodeJsonEachRow(client.executeQuery(sql, new QueryBudget(properties.getDefaultTimeoutMs(),
                properties.getMaxRowsToRead(), properties.getMaxBytesToRead(), properties.getMaxMemoryUsage(),
                properties.getMaxResponseBytes())), mapper);
    }

    /** 固定格式解析，保留毫秒身份。 */
    private static Instant parseTime(String value) {
        return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")).toInstant(ZoneOffset.UTC);
    }

    /** 可空时间只从有效数据库列读取。 */
    private static Instant nullableTime(JsonNode row, String field) {
        return row.path(field).isNull() ? null : parseTime(row.path(field).asText());
    }

    /** 空原因与空来源摘要统一为缺失值。 */
    private static String nullableText(JsonNode row, String field) {
        return row.path(field).isNull() || row.path(field).asText().isEmpty() ? null : row.path(field).asText();
    }

    /** SQL 日期只接受服务端时间类型。 */
    private static String date(Instant value) { return "toDateTime64(" + quote(time(value)) + ",3,'UTC')"; }
    /** SQL 字面量统一转义，用户输入不能充当标识符。 */
    private static String quote(String value) { return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"; }
    /** 毫秒 UTC 编码。 */
    private static String time(Instant value) { return TIME.format(LocalDateTime.ofInstant(value, ZoneOffset.UTC)); }
    /** ClickHouse 非空字符串列的缺失值。 */
    private static String empty(String value) { return value == null ? "" : value; }
}
