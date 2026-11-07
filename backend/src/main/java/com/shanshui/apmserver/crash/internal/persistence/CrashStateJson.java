package com.shanshui.apmserver.crash.internal.persistence;

import com.shanshui.apmserver.crash.internal.domain.CrashState;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 当前状态的确定 JSONEachRow 编码，数据库发布重放不得重新计算材料。 */
@Component
public class CrashStateJson {
    /** UTC 毫秒编码。 */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** 当前应用 JSON 配置。 */
    private final ObjectMapper mapper;
    /** 注入固定编解码器。 */
    public CrashStateJson(ObjectMapper mapper) { this.mapper = mapper; }

    /** 为跨库发布登记提供确定的同字节载荷。 */
    public String encode(CrashState state) {
        // 插入顺序固定，发布器重试直接使用登记的原始 JSON 字节。
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("app_id", state.appId());
        row.put("event_id", state.eventId());
        row.put("build_id", state.buildId());
        row.put("event_time", time(state.eventTime()));
        row.put("state_version", state.stateVersion());
        row.put("symbolication_status", state.status());
        row.put("symbolication_reason", empty(state.reason()));
        row.put("stack_format", state.stackFormat());
        row.put("stack_payload", state.stackPayload());
        row.put("exception_type", empty(state.exceptionType()));
        row.put("fingerprint", state.fingerprint());
        row.put("fingerprint_version", state.fingerprintVersion());
        row.put("grouping_kind", state.groupingKind());
        row.put("symbol_id", state.symbolId());
        row.put("symbol_revision", state.symbolRevision());
        row.put("symbol_sha256", empty(state.symbolSha256()));
        row.put("processed_at", state.processedAt() == null ? null : time(state.processedAt()));
        row.put("retry_at", state.retryAt() == null ? null : time(state.retryAt()));
        return mapper.writeValueAsString(row);
    }

    /** 强制使用当前模型构造校验，不吞掉非法状态。 */
    public CrashState decode(JsonNode row) {
        return new CrashState(UUID.fromString(row.path("app_id").asText()), row.path("event_id").asText(),
                row.path("build_id").asText(), parseTime(row.path("event_time").asText()), row.path("state_version").asLong(),
                row.path("symbolication_status").asText(), nullableText(row, "symbolication_reason"),
                row.path("stack_format").asText(), row.path("stack_payload").asText(), nullableText(row, "exception_type"),
                row.path("fingerprint").asText(), row.path("fingerprint_version").asText(), row.path("grouping_kind").asText(),
                row.path("symbol_id").isNull() ? null : UUID.fromString(row.path("symbol_id").asText()),
                row.path("symbol_revision").isNull() ? null : row.path("symbol_revision").asInt(),
                nullableText(row, "symbol_sha256"), nullableTime(row, "processed_at"), nullableTime(row, "retry_at"));
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
